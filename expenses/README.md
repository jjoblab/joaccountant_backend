# Module : expenses

> Notes de frais employé et dépenses d'exploitation — cycle de vie DRAFT → SUBMITTED →
> APPROVED → PAID avec génération automatique de l'écriture comptable.

## Rôle du module

Le module `:expenses` gère les notes de frais soumises par les employés ainsi que les
dépenses d'exploitation générales. Il est **toujours-actif** (always-on — voir
`BusinessTypeModuleService.alwaysOnModules`) : toute entreprise a des dépenses, au même
titre qu'elle facture (`:invoicing`).

Le module distingue deux cas à l'approbation :
- `paidDirectly = false` (défaut) — dépense remboursable à l'employé. L'écriture génère
  une créance employé (`Crédit Tiers-Employé`).
- `paidDirectly = true` — dépense payée directement par la trésorerie au moment de la
  constitution. L'écriture crédite un compte de trésorerie (`Crédit ACTIF/CASH`).

## Ce qu'il fait précisément

### Entités principales

- `ExpenseReport` — note de frais. Champs : `thirdPartyId` (EMPLOYEE, **nullable** — une
  dépense d'exploitation générale n'est pas forcément liée à un employé), `status`
  (DRAFT/SUBMITTED/APPROVED/REJECTED/PAID), `expenseDate`, `currency`, `description`,
  `totalAmount`, `paidDirectly` (boolean), `journalEntryId`.
- `ExpenseLine` — `reportId`, `category` (TRAVEL/MEALS/SUPPLIES/OTHER ou code personnalisé
  créé via `ExpenseCategoryController` V43, nullable), `description`, `amount`,
  `expenseAccountId` (compte de charge cible, CHARGES).
- `ExpenseCategory` — **V43 — Finding #12 (audit batch B)** — catégorie de note de frais avec
  plafonds journaliers/mensuels configurables. Champs : `code` (unique par entreprise,
  ex. `TRAVEL`/`MEALS`/`SUPPLIES`/`OTHER` seedés + codes personnalisés `HOTEL`/`PARKING`),
  `label`, `dailyLimit` (BigDecimal, nullable), `monthlyLimit` (BigDecimal, nullable),
  `active`. Les 4 codes standards sont seedés par V43 avec `dailyLimit=NULL` /
  `monthlyLimit=NULL` (pas de validation) — l'administrateur les configure via
  `PUT /expenses/categories/{categoryId}`.

### Règles métier clés

1. **Le tiers doit être un `EMPLOYEE`** si `thirdPartyId` est précisé (sinon `422
   THIRD_PARTY_NOT_EMPLOYEE`).
2. **`paidDirectly = false` exige un `thirdPartyId`** — sinon `422
   EMPLOYEE_REQUIRED_FOR_REIMBURSEMENT` à la soumission. Une note à rembourser doit
   désigner l'employé à rembourser.
3. **Cycle de vie strict** : DRAFT → SUBMITTED → APPROVED → PAID. REJECTED est possible
   uniquement au stade SUBMITTED. Chaque transition lève `409 EXPENSE_NOT_*` si le
   statut courant ne correspond pas.
4. **Approbation délègue à `JOURNAL_ENTRY_POST`** (§2.2 du prompt — choix de cohérence
   avec `:invoicing`/`:fixed-assets`/`:inventory`). Pas de `ApprovalActionType` dédié.
5. **Résolution des comptes référentiel-agnostique** (calquée sur audit B4) :
   - Compte de charges par ligne : `expenseAccountId` si précisé (doit être CHARGES),
     sinon `CHARGES + taxMappingCode="OPERATING_EXPENSE"` → `CHARGES` actif quelconque
     → fallback SYSCOHADA `"601000"/"601"`.
   - Compte de trésorerie (si `paidDirectly = true`) : `ACTIF + taxMappingCode="CASH"`
     → fallback SYSCOHADA `"570000"/"57"`.
   - Compte employé (si `paidDirectly = false`) : compte dédié du tiers (ou collectif).
6. **Code journal `DP` (dépenses)** — doit exister (sinon `422 JOURNAL_DP_NOT_FOUND`).

### Écriture comptable à l'approbation

- Débit : Charges (par ligne, sur `expenseAccountId` ou fallback générique) pour le total.
- Crédit :
  - Si `paidDirectly = false` : Tiers-Employé (compte dédié du tiers).
  - Si `paidDirectly = true` : Trésorerie (compte ACTIF marqué `taxMappingCode="CASH"`).

### Cycle de vie des objets

- `ExpenseReport` : DRAFT → SUBMITTED → APPROVED → PAID (ou REJECTED depuis SUBMITTED,
  revient à DRAFT pour correction côté utilisateur).
- `ExpenseLine` : créées en même temps que la note, immuables après SUBMITTED.

## Endpoints exposés

### ExpenseCategoryController — `/api/v1/companies/{companyId}/expenses/categories`

**V43 — Finding #12 (audit batch B)**. CRUD des catégories de notes de frais et de leurs
plafonds journaliers/mensuels. `GET` : `VIEWER` ; `POST`/`PUT` : `ADMIN`.

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/expenses/categories` | Liste les catégories (standards seedés par V43 + personnalisées). Triées par `code`. | — |
| POST | `/api/v1/companies/{companyId}/expenses/categories` | Crée une catégorie personnalisée avec plafonds journaliers/mensuels. Le code doit être unique par entreprise (les codes standards `TRAVEL`/`MEALS`/`SUPPLIES`/`OTHER` sont déjà seedés — utiliser `PUT` pour configurer leurs plafonds). | 409 code existe déjà |
| PUT | `/api/v1/companies/{companyId}/expenses/categories/{categoryId}` | Modifie les plafonds d'une catégorie existante. Le code n'est PAS modifiable (intégrité référentielle avec `expense_line.category`). Pour désactiver un plafond, passer `null` explicitement. | 404 |

### ExpensesController — `/api/v1/companies/{companyId}/expense-reports`

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/expense-reports` | Crée une note DRAFT | 422 `THIRD_PARTY_NOT_EMPLOYEE` |
| GET | `/api/v1/companies/{companyId}/expense-reports?fiscalYearId=&from=&to=&page=&size=` | **Paginé (Finding #3)** — retourne une `Page<ExpenseReportResponse>`. `?page=0&size=20` (défaut, `size` capped à 200). `?fiscalYearId=` filtre par exercice (prévalence sur `from`/`to`). `?from=`/`?to=` plage de dates sur `expenseDate`. | — |
| GET | `/api/v1/companies/{companyId}/expense-reports/{id}` | Détail d'une note | 404 `ExpenseReport` |
| POST | `/api/v1/companies/{companyId}/expense-reports/{id}/submit` | DRAFT → SUBMITTED | 409 `EXPENSE_NOT_DRAFT`, 422 `EMPLOYEE_REQUIRED_FOR_REIMBURSEMENT` |
| POST | `/api/v1/companies/{companyId}/expense-reports/{id}/approve` | SUBMITTED → APPROVED, génère l'écriture | 409 `EXPENSE_NOT_SUBMITTED`, 422 `JOURNAL_DP_NOT_FOUND`/`CASH_ACCOUNT_NOT_FOUND`/`EXPENSE_ACCOUNT_NOT_FOUND` |
| POST | `/api/v1/companies/{companyId}/expense-reports/{id}/reject` | SUBMITTED → REJECTED | 409 `EXPENSE_NOT_SUBMITTED` |
| POST | `/api/v1/companies/{companyId}/expense-reports/{id}/payments` | APPROVED → PAID | 409 `EXPENSE_NOT_APPROVED` |

> Pas de `403 MODULE_NOT_ENABLED` — le module est **toujours-actif** (always-on —
> `BusinessTypeModuleService.alwaysOnModules`).

> **Stabilisation 2026-07-25 (suite 4)** — ajout du paramètre optionnel `?fiscalYearId=`
> sur `GET /expense-reports`. Le filtre s'applique sur `expenseDate` (date à laquelle la
> dépense a été engagée, pas la date de soumission/approbation). Combiné avec
> `?from=`/`?to=` : si `?fiscalYearId=` est présent, il a la prévalence sur les filtres
> dates (les bornes de l'exercice résolu remplacent `from`/`to`).

## Relations avec les autres modules

### Dépendances

- `:core` — `TenantAwareEntity`, exceptions, `ReportingClass`.
- `:audit-trail` — auditing.
- `:chart-of-accounts` — `Account`, `AccountRepository`.
- `:accounting-engine` — `AccountingEngineService`, `JournalRepository`,
  `JournalEntrySourceModule.EXPENSES`.
- `:approval-workflow` — délègue à `JOURNAL_ENTRY_POST` (§2.2 du prompt).
- `:third-parties` — `ThirdParty` (type `EMPLOYEE`), `ThirdPartyRepository`.
- `:company` — `BusinessTypeModuleService.alwaysOnModules` référence `EXPENSES`.

### Modules qui dépendent de celui-ci

- Aucun au MVP.

### Événements publiés / consommés

- **Publie** : aucun au MVP.
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V25__expenses.sql` — tables `expense_report` et
  `expense_line`. CHECK sur `status` et `category`. Index sur `(company_id, status)` et
  `third_party_id`.
- `src/main/resources/db/migration/V43__expense_category_limits.sql` — **V43 — Finding #12
  (audit batch B)**. Crée la table `expense_category` (catégorie de note de frais avec
  plafonds). Colonnes : `id`, `company_id`, `code` (unique par entreprise), `label`,
  `daily_limit` (NUMERIC 19,4, nullable), `monthly_limit` (NUMERIC 19,4, nullable), `active`,
  `created_at`, `updated_at`, `version`. Seed des 4 codes standards `TRAVEL`/`MEALS`/
  `SUPPLIES`/`OTHER` avec plafonds `NULL` (pas de validation par défaut — l'administrateur
  les configure via `PUT /expenses/categories/{categoryId}`).

## Repository — méthodes de lecture (suite 4)

`ExpenseReportRepository` (Spring Data JPA) expose les méthodes suivantes :

| Méthode | Usage |
|---|---|
| `findByCompanyIdOrderByExpenseDateDesc(companyId)` | `GET /expense-reports` sans filtre (comportement historique). |
| `findByCompanyIdAndStatus(companyId, status)` | Filtrage par statut — utilisé en interne par le dashboard et les exports. |
| `findByCompanyIdAndExpenseDateBetweenOrderByExpenseDateDesc(companyId, start, end)` | **Nouveau (suite 4)** — `GET /expense-reports?fiscalYearId=` : résout l'exercice via `AccountingEngineService.resolveFiscalYear` puis appelle cette méthode avec `start = fy.startDate` et `end = fy.endDate`. Aussi utilisé par `:reporting` pour l'export `expense_register` quand `?from=`/`?to=` sont fournis. |

## Points d'attention

- ⚠️ **Pas de PATCH implémenté** — la modification d'une note DRAFT n'est pas implémentée
  au MVP. L'utilisateur doit recréer la note si elle doit être modifiée.
- ⚠️ **Pas de lettrage automatique** — `pay()` met à jour le statut mais ne lettre pas
  automatiquement la ligne employé. L'utilisateur lettre via `:third-parties`.
- ⚠️ **Pas de scan de justificatifs** — pas de gestion de pièces jointes au MVP. Le
  rattachement d'un justificatif PDF/image serait à ajouter via `:core`/`FileStoragePort`.

## Tests

Couvert par `ExpensesIntegrationTest` dans `:app` — cycle de vie complet (création →
soumission → approbation → paiement), vérification de l'écriture comptable, cas
`paidDirectly = true` et `paidDirectly = false`, refus de soumission d'une note à
rembourser sans employé.
