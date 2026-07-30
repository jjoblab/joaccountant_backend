# Module : chart-of-accounts

> Plan comptable multi-référentiel hiérarchique 4 niveaux, classé par `ReportingClass` universelle consommée par les états financiers.

## Rôle du module

Le module `:chart-of-accounts` est le référentiel de comptes d'une entreprise. Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`) et
porte la structure sur laquelle tous les autres modules comptables s'appuient.

Il supporte les **6 référentiels** seedés en V1_002 :
- **MANDATED** (`SYSCOHADA_REVISED`, `PCG_FRANCE`, `PCN_HAITI`, `PCGR_CANADA`) — les classes
  de niveau 1 sont issues du `mandatedClassSeed` du référentiel.
- **FREE** (`IFRS_FULL`, `IFRS_SME`) — un gabarit de numérotation (`AccountNumberingTemplate`)
  doit être fourni à l'initialisation ; 5 classes sont générées correspondant aux 5
  `ReportingClass` (Actif, Passif, Capitaux propres, Produits, Charges).

La `ReportingClass` est la **seule classification consommée par les états financiers**
(§4) — c'est ce qui permet au même moteur de produire un bilan correct pour les 6
référentiels malgré des nomenclatures de classes différentes (ex. PCGR_CANADA inverse
Produits/Charges par rapport à SYSCOHADA).

## Ce qu'il fait précisément

### Entités principales

- `Account` — compte du plan comptable, hiérarchie auto-référentielle 4 niveaux (classe →
  rubrique → compte principal → sous-compte). Porte `code`, `label`, `level` (1-4),
  `reportingClass`, `reportingSubcategory` (COURANT/NON_COURANT/N_A), `normalBalance`
  (DEBIT/CREDIT), `locked`, `active`, `isCollective`, `path` (ex. `1.10.101.101100`),
  `taxMappingCode` (référence opaque vers une `TaxRule.code`), `requiresAnalyticalTagPlanIds`
  (JSONB — plans analytiques obligatoires sur ce compte).
- `AccountNumberingTemplate` — gabarit de numérotation pour les référentiels FREE (IFRS).
  Relation 1-1 avec `companies`. Définit `codeLengthLevel1..4` et `spacingStep`. Ignoré pour
  MANDATED.
- `ReportingClass` (enum du `:core`) — `ACTIF`, `PASSIF`, `CAPITAUX_PROPRES`, `PRODUITS`,
  `CHARGES`. C'est l'unique classification lue par `:financial-statements`.
- `NormalBalance` (enum) — `DEBIT`, `CREDIT`.
- `ReportingSubcategory` (enum) — `COURANT`, `NON_COURANT`, `N_A`.
- `AccountBalanceGuard` (interface) — implémentée dans `:accounting-engine`
  (`JournalBasedAccountBalanceGuard`) pour vérifier le solde d'un compte avant désactivation.

### Règles métier clés

1. Le `code` compte est unique par entreprise (contrainte DB `uc_account_company_code` +
   validation applicative).
2. Un compte `locked = true` ne peut pas être renommé ni supprimé (409 `ACCOUNT_LOCKED`).
3. La désactivation (`active = false`) requiert un solde nul, vérifié via
   `AccountBalanceGuard.hasNonZeroBalance` (409 `ACCOUNT_NOT_BALANCED`).
4. Aucun compte de niveau > 4 (CHECK DB `chk_account_level`).
5. La suppression physique est **toujours interdite** ; seule la désactivation est permise.
6. `POST /initialize` est **idempotent** : 409 `CHART_OF_ACCOUNTS_ALREADY_INITIALIZED` si un
   compte de niveau 1 existe déjà.
7. Pour un référentiel FREE, un `template` doit être fourni dans le corps de `POST /initialize`
   (422 `TEMPLATE_REQUIRED_FOR_FREE_FRAMEWORK` sinon).

### Cycle de vie des objets

- `Account` :
  - `LOCKED + ACTIVE` (état initial des classes/rubriques générées) — non modifiable.
  - `UNLOCKED + ACTIVE` (comptes principaux et sous-comptes) — éditable via `PATCH /{accountId}`.
  - `UNLOCKED + INACTIVE` — désactivé (solde nul requis).
- Pas de cycle de statut explicite ; la transition se fait par `PATCH` sur `active`.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/chart-of-accounts/initialize` | Génère les niveaux 1 et 2 verrouillés depuis le référentiel. Corps : `{accountingFrameworkId, template?}` | 404 `ACCOUNTING_FRAMEWORK_NOT_FOUND`, 409 `CHART_OF_ACCOUNTS_ALREADY_INITIALIZED`, 422 `TEMPLATE_REQUIRED_FOR_FREE_FRAMEWORK` |
| GET | `/api/v1/companies/{companyId}/chart-of-accounts?format=tree\|flat&search=` | Liste tous les comptes (arbre ou à plat, avec filtre full-text) | — |
| POST | `/api/v1/companies/{companyId}/chart-of-accounts/{parentId}/children` | Crée un compte enfant sous le parent. Corps : `{code?, label, reportingClass, normalBalance, ...}` | 404 `Account`, 409 `ACCOUNT_CODE_ALREADY_EXISTS`, 422 `ACCOUNT_LEVEL_TOO_DEEP`/champs invalides |
| PATCH | `/api/v1/companies/{companyId}/chart-of-accounts/{accountId}` | Met à jour un compte (label, reportingSubcategory, taxMappingCode, requiresAnalyticalTagPlanIds, active) | 404 `Account`, 409 `ACCOUNT_LOCKED`/`ACCOUNT_NOT_BALANCED` |
| GET | `/api/v1/companies/{companyId}/chart-of-accounts/{accountId}/descendants-count` | Compte les descendants d'un compte | 404 `Account` |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, `TenantContext`, exceptions, `AccountingFramework`,
  `AccountingFrameworkRepository`, `ReportingClass`, `NumberingMode`,
  `ApplicationEventPublisher`.

### Modules qui dépendent de celui-ci

- `:accounting-engine` — implémente `AccountBalanceGuard` via `JournalBasedAccountBalanceGuard`
  et consomme `Account`/`AccountRepository` pour valider les comptes au postage.
- `:invoicing`, `:fixed-assets`, `:inventory`, `:funds-grants`, `:tax`, `:third-parties` —
  tous consomment le plan comptable pour résoudre les comptes à créditer/débiter.
- `:financial-statements` — agrège les soldes par `ReportingClass` (seule classification
  lue) pour produire bilan et compte de résultat.
- `:reporting` — utilise `reportingClass` et `taxMappingCode` pour les KPIs du dashboard
  (correction M4).

### Événements publiés / consommés

- **Publie** : `ChartOfAccountsInitializedEvent`, `AccountCreatedEvent`, `AccountUpdatedEvent`.
  Tous implémentent `AuditableAction` (audit B2 indirect).
- **Consomme** : `CompanyWizardCompletedEvent` (de `:company`) — pour auto-initialiser le plan
  comptable avec le référentiel choisi par l'utilisateur (lié au wizard step 3).

## Tables / migrations Flyway

- `src/main/resources/db/migration/V5_001__chart_of_accounts.sql` — tables `account` et
  `account_numbering_template`. Contraintes CHECK sur `level` (1-4), `reporting_class` (5
  valeurs), `reporting_subcategory`, `normal_balance`. 4 index dont `path` pour la recherche
  hiérarchique.

## Points d'attention (hérités de l'audit)

- ⚠️ **B3 — `inferReportingClass` corrigé** : la version initiale ignorait le paramètre
  `framework` et appliquait un mapping unique pensé pour SYSCOHADA. Conséquence avant
  correction : 5 classes sur 8 mal classées pour PCGR_CANADA, inversion Produits ↔ Charges
  (classe 7 = CHARGES au Canada, pas Produits) — bilans et comptes de résultat canadiens
  totalement faux. La version corrigée spécialise le mapping par référentiel. **Non-breaking**
  côté API (format de réponse inchangé, mais les `reportingClass`/`normalBalance` des comptes
  créés sont désormais corrects pour PCGR_CANADA).
- ⚠️ **Aucune pagination sur `GET /chart-of-accounts`** — retourne tous les comptes. Sur un
  plan comptable SYSCOHADA complet (≈ 200-500 comptes), la réponse reste gérable ; sur une
  entreprise qui a créé beaucoup de sous-comptes de niveau 4, le client mobile doit
  implémenter une recherche côté UI (paramètre `search`).
- ⚠️ **`requiresAnalyticalTagPlanIds` en JSONB** — pas de table de jointure dédiée. La
  validation est faite au postage par `:accounting-engine` (lève `ANALYTICAL_TAG_REQUIRED`),
  mais les modules métier qui créent des écritures automatiques (invoicing, fixed-assets,
  inventory) passent `analyticalTags = List.of()` — un compte exigeant un tag fera échouer
  le postage a posteriori, sans message clair (audit M12).
- ⚠️ **`taxMappingCode` est opaque** — pas de FK dure vers `TaxRule.code`. La suppression
  d'une `TaxRule` ne casse pas le plan comptable, mais un `taxMappingCode` orphelin pointe
  vers rien. Les modules consommateurs (invoicing, reporting) doivent gérer ce cas.

## Tests

Couvert par `ChartOfAccountsIntegrationTest` dans `:app` (test négatif IFRS sans gabarit +
test de création/modification/recherche SYSCOHADA). Le test PCGR_CANADA post-correction B3
n'a pas été ajouté (couverture référentielle déséquilibrée — audit 3.3).
