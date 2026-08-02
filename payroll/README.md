# Module : payroll

> Paie consolidée — calcul brut→net via `:tax` (WithholdingRule), écriture comptable
> consolidée à l'approbation, bulletin de paie PDF via `:document-generation`.

## Rôle du module

Le module `:payroll` génère les campagnes de paie mensuelles pour une entreprise. Il est
**toujours-actif** (always-on — voir `BusinessTypeModuleService.alwaysOnModules`) : toute
entreprise qui a des employés (`:employees`) paie des salaires.

Le module **génère des écritures comptables** (contrairement à `:employees` qui est
purement informatif) à l'approbation d'une campagne — une écriture consolidée par
campagne (pas une écriture par employé, pour limiter le volume).

## Ce qu'il fait précisément

### Entités principales

- `PayrollRun` — campagne de paie pour une période (mois/année). Unique par entreprise
  et par période (`uc_pr_company_period`). Champs : `periodMonth`, `periodYear`, `status`
  (DRAFT/CALCULATED/APPROVED/PAID/CLOSED), `totalGross`, `totalNet`,
  `totalEmployerContributions`, `journalEntryId`.
- `Payslip` — bulletin de paie pour un employé sur une campagne. Champs : `runId`,
  `employeeId`, `grossSalary`, `deductions` (JSONB — liste des retenues salariales),
  `employerContributions` (JSONB — liste des charges patronales), `netPay`,
  `payslipNumber` (via `:document-numbering`, `DocumentType.PAYSLIP`, scopeKey=`"PA"`).

### Structure des lignes `deductions` / `employerContributions` (V51)

Les champs `deductions` (cotisations salariales) et `employerContributions` (cotisations
patronales) sont des listes JSONB d'objets `DeductionLine`. Chaque ligne a la structure :

```json
{"code":"URSSAF","label":"URSSAF maladie","rate":7.50,"amount":289.80}
```

- `code` — code court de la `ContributionRule` (ou `WithholdingRule.code` en legacy).
- `label` — libellé lisible (ex. « URSSAF maladie », « Retraite Tranche A », « CSG déductible »).
- `rate` — taux appliqué (en %), `null` si non applicable.
- `amount` — montant calculé (BigDecimal arrondi selon la devise).

Cette structure est **requise pour C. trav. R3243-1** (bulletin de paie doit détailler les
cotisations par libellé + montant). Elle alimente le bulletin PDF via `:document-generation`.

> **V51** : avant cette version, `DeductionLine` était une structure simplifiée sans `code`
> ni `label`. Le `PayrollCalculator` enrichit désormais chaque ligne avec le `code` et le
> `label` de la `ContributionRule` (ou `WithholdingRule`) source.

### Règles métier clés

1. **Une seule campagne par période** par entreprise — `uc_pr_company_period` bloque les
   doublons (lève `409 PAYROLL_RUN_ALREADY_EXISTS`).
2. **`calculate()` génère un Payslip par employé `ACTIVE`** — filtre
   `EmployeeRepository.findByCompanyIdAndStatusOrderByIdAsc(companyId, ACTIVE)`. Si aucun
   employé ACTIVE → `422 NO_ACTIVE_EMPLOYEES`.
3. **Calcul brut→net via `WithholdingRule`** du module `:tax` dont
   `applicableThirdPartyTypes` contient `"EMPLOYEE"`. Le calcul est simple au MVP :
   `deduction = grossSalary × rate / 100`. Le net = `grossSalary - sum(deductions)`.
   **V51 — audit v4.7 §4.1 #3** : si une `ContributionRule` est configurée pour l'entreprise,
   le service délègue à **`PayrollCalculator`** qui applique les cotisations par tranches
   (PMSS, CSG abattue, Tranche A/B, charges patronales détaillées par régime
   `FR_GENERAL`/`FR_CADRE`/`FR_NON_CADRE`/`HT_GENERAL`). Les heures sup (+25 % / +50 %),
   les absences et les congés payés (V60 sur `Employee`) sont intégrés au calcul du brut.
   Fallback sur `WithholdingRule` (legacy) si aucune `ContributionRule` n'est configurée.
4. **Charges patronales** — un taux global configurable via
   `?employerContributionRate=14` à l'appel `calculate` (legacy). Si des `ContributionRule`
   `contributionType=EMPLOYER` sont configurées, le `PayrollCalculator` les applique en
   remplacement du taux global.
5. **Approbation délègue à `JOURNAL_ENTRY_POST`** (§2.4 du prompt — choix de cohérence
   avec §2.2). La transition CALCULATED → APPROVED génère l'écriture consolidée en une
   seule étape côté service (la validation par seuil reste gérée par
   `:accounting-engine` au postage).
6. **Écriture consolidée à l'approbation** :
   - Débit Charges de personnel [brut + charges patronales].
   - Crédit Salaires à payer [net] — **par employé** (avec `thirdPartyId` pour lettrage).
   - Crédit Organismes sociaux à payer [charges patronales] — si > 0.
   - Crédit État — retenues fiscales [somme des retenues] — si > 0.
7. **Numérotation des bulletins** — `payslipNumber` attribué à l'approbation via
   `DocumentType.PAYSLIP`, scopeKey `"PA"`.
8. **Résolution des comptes référentiel-agnostique** (calquée sur audit B4) :
   - Charges de personnel : `CHARGES + taxMappingCode="PERSONNEL_EXPENSE"` → `CHARGES`
     quelconque → fallback SYSCOHADA `"661000"/"661"`.
   - Salaires à payer : `PASSIF + taxMappingCode="SALARIES_PAYABLE"` → fallback
     SYSCOHADA `"422000"/"422"`.
   - Organismes sociaux : `PASSIF + taxMappingCode="SOCIAL_SECURITY_PAYABLE"` → fallback
     SYSCOHADA `"433000"/"433"`.
   - État (retenues fiscales) : `PASSIF + taxMappingCode="VAT_COLLECTED"` (réutilisé par
     convention — en SYSCOHADA, classe 443 = "Etat, impôts et taxes") → fallback
     SYSCOHADA `"443000"/"443"`.
9. **Code journal `PA` (paie)** — doit exister (sinon `422 JOURNAL_PA_NOT_FOUND`).

### Cycle de vie des objets

- `PayrollRun` : DRAFT → CALCULATED → APPROVED → PAID → CLOSED. Irréversible au MVP
  (pas de reset possible — une fois CALCULATED, on ne peut pas revenir à DRAFT).
- `Payslip` : créé à `calculate()`, immuable après. Numéro attribué à `approve()`.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/payroll-runs` | Crée une campagne DRAFT pour une période | 409 `PAYROLL_RUN_ALREADY_EXISTS` |
| GET | `/api/v1/companies/{companyId}/payroll-runs?limit=N` | Liste les campagnes — **défaut 12 dernières campagnes** triées par `periodYear` DESC puis `periodMonth` DESC (suite 4). `?limit=N` (Integer, optionnel) pour demander plus ou moins de campagnes ; une valeur `<= 0` retombe sur le défaut 12. La paie n'est pas rattachée à un exercice fiscal (les campagnes sont identifiées par `periodYear` + `periodMonth`), donc pas de paramètre `?fiscalYearId=` ici. | — |
| GET | `/api/v1/companies/{companyId}/payroll-runs/{id}` | Détail d'une campagne | 404 `PayrollRun` |
| POST | `/api/v1/companies/{companyId}/payroll-runs/{id}/calculate` | DRAFT → CALCULATED, génère un Payslip par employé ACTIVE. `?employerContributionRate=14` optionnel | 409 `PAYROLL_RUN_NOT_DRAFT`, 422 `NO_ACTIVE_EMPLOYEES` |
| POST | `/api/v1/companies/{companyId}/payroll-runs/{id}/approve` | CALCULATED → APPROVED, génère l'écriture consolidée, attribue les numéros de bulletin | 409 `PAYROLL_RUN_NOT_CALCULATED`, 422 `JOURNAL_PA_NOT_FOUND`/`PERSONNEL_ACCOUNT_NOT_FOUND`/`SALARIES_PAYABLE_ACCOUNT_NOT_FOUND`/`SOCIAL_SECURITY_ACCOUNT_NOT_FOUND`/`STATE_TAX_ACCOUNT_NOT_FOUND` |
| POST | `/api/v1/companies/{companyId}/payroll-runs/{id}/pay` | APPROVED → PAID | 409 `PAYROLL_RUN_NOT_APPROVED` |
| POST | `/api/v1/companies/{companyId}/payroll-runs/{id}/close` | PAID → CLOSED | 409 `PAYROLL_RUN_NOT_PAID` |
| GET | `/api/v1/companies/{companyId}/payroll-runs/{id}/payslips` | Liste les bulletins | — |
| GET | `/api/v1/companies/{companyId}/payroll-runs/payslips/{payslipId}/pdf` | Génère le PDF d'un bulletin | 404 `Payslip` |

> Pas de `403 MODULE_NOT_ENABLED` — le module est **toujours-actif** (always-on).

> **Stabilisation 2026-07-25 (suite 4)** — `GET /payroll-runs` ne retourne plus la
> totalité de l'historique mais les **12 dernières campagnes** par défaut, triées par
> `periodYear` DESC puis `periodMonth` DESC. Le paramètre optionnel `?limit=N` permet de
> demander plus (ou moins) de campagnes ; une valeur `<= 0` retombe sur le défaut 12.
> Pour un écran « historique de paie » : ne pas paginer par `?limit=` ; à la place,
> appeler `?limit=12` (défaut) pour l'écran d'accueil et exposer un bouton « Charger plus »
> qui incrémente `?limit` par 12 à chaque clic. Pour récupérer l'historique complet en un
> appel, passer `?limit=1000` (par exemple).
>
> **BREAKING pour pagination côté client** : si le mobile chargait l'intégralité de
> l'historique de paie en un appel (sans pagination), il ne recevra plus que les 12
> dernières campagnes. Voir `MOBILE_SYNC_2026-07-25_bonus-modules-and-fiscal-year.md` §5.3.

## Relations avec les autres modules

### Dépendances

- `:core` — `TenantAwareEntity`, exceptions, `ReportingClass`.
- `:audit-trail` — auditing.
- `:chart-of-accounts` — `Account`, `AccountRepository`.
- `:accounting-engine` — `AccountingEngineService`, `JournalRepository`,
  `JournalEntrySourceModule.PAYROLL`.
- `:document-numbering` — `DocumentNumberingService`, `DocumentType.PAYSLIP`.
- `:document-generation` — `DocumentGenerationService`, `DocumentType.PAYSLIP` (PDF).
- `:approval-workflow` — délègue à `JOURNAL_ENTRY_POST` (§2.4 du prompt).
- `:employees` — `Employee`, `EmployeeRepository` (filtre `ACTIVE`).
- `:tax` — `WithholdingRule`, `WithholdingRuleRepository` (calcul des retenues).
- `:third-parties` — `ThirdParty`, `ThirdPartyRepository` (compte dédié employé pour
  lettrage).
- `:company` — `BusinessTypeModuleService.alwaysOnModules` référence `PAYROLL`.

### Modules qui dépendent de celui-ci

- Aucun au MVP.

### Événements publiés / consommés

- **Publie** : aucun au MVP.
- **Consomme** : aucun directement (mais lit les `WithholdingRule` de `:tax` et les
  `Employee` de `:employees`).

## Tables / migrations Flyway

- `src/main/resources/db/migration/V38__payroll.sql` — tables `payroll_run` et `payslip`.
  CHECK sur `status` et `period`. Contrainte unique `(company_id, period_year, period_month)`.
  `deductions` et `employer_contributions` en JSONB.

## Points d'attention

- ⚠️ **Pas de détail des cotisations sociales** — au MVP, charges patronales = un taux
  global simple. Une ventilation URSS/OFATMA/retraite/assurance santé nécessiterait une
  table dédiée `PayrollContributionRate` et un calcul par cotisation. Hors scope.
- ⚠️ **Pas de génération de fichier de virement** — `pay()` est un marquage manuel. La
  génération d'un fichier de virement bancaire (format XML SEPA ou autre) serait à ajouter.
- ⚠️ **Pas de reset de campagne** — une fois CALCULATED, on ne peut pas revenir à DRAFT.
  En cas d'erreur de calcul, il faut clôturer la campagne et en créer une nouvelle (mais
  la contrainte d'unicité `(company_id, period_year, period_month)` bloque — il faudrait
  ajouter un statut `CANCELLED` et permettre la recréation, hors scope MVP).
- ⚠️ **Pas d'historique des salaires** — le calcul utilise `Employee.baseSalary` au
  moment du calcul. Si le salaire change entre deux campagnes, le passé n'est pas
  recalculé (comportement attendu — la paie passée est figée).
- ⚠️ **Pas de gestion des congés payés** — pas de calcul d'indemnité de congés payés au
  MVP. Le `Employee.baseSalary` est utilisé tel quel.
- ⚠️ **PDF bulletin requires a template PAYSLIP** — `document-generation` doit avoir un
  template par défaut pour `DocumentType.PAYSLIP` (créé par l'utilisateur ou seedé).
  Si aucun template n'existe, l'appel à `getPayslipPdf` lève `422 TEMPLATE_NOT_FOUND`.

## Tests

Couvert par `PayrollIntegrationTest` dans `:app` — cycle de vie complet (création →
calcul → approbation → paiement → clôture), vérification de l'écriture consolidée
(équilibre débit/crédit, comptes attendus), génération PDF d'un bulletin.
