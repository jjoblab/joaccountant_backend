# Module : financial-statements

> Bilan, compte de résultat et snapshots figés — générés exclusivement à partir de `ReportingClass`, référentiel-agnostiques.

## Rôle du module

Le module `:financial-statements` produit les états financiers de synthèse à partir des
écritures POSTED du moteur comptable. Il est **always-on** (activé pour tous les types métier
via `BusinessTypeModuleService.alwaysOnModules()`) et fonctionne pour les 6 référentiels
comptables — il utilise **uniquement** `Account.reportingClass` et
`Account.reportingSubcategory` pour classifier les comptes, jamais le code du référentiel.

Cette indépendance au référentiel est la **règle fondamentale §4** : c'est ce qui permet au
même moteur de produire un bilan correct pour SYSCOHADA (où la classe 1 = Capitaux propres,
2 = Actif immobilisé), pour PCGR_CANADA (où la classe 1 = Actif court terme, 5 = Avoir des
actionnaires) et pour IFRS (5 classes correspondant aux 5 `ReportingClass`).

## Ce qu'il fait précisément

### Entités principales

- `FinancialStatementSnapshot` — snapshot figé d'un état financier pour une période. Unique
  par `(companyId, type, periodId)`. Champs : `type` (`BALANCE_SHEET` /
  `INCOME_STATEMENT` / **`CASH_FLOW_STATEMENT`** depuis audit v4.7 §3.1 Finding #5),
  `periodId`, `generatedAt`, `frozen` (toujours true), `contentJson` (JSONB de l'état),
  `asOfDate` (pour le bilan), `fromDate`/`toDate` (pour le compte de résultat / flux de
  trésorerie).
- `FinancialStatementType` (enum) — `BALANCE_SHEET`, `INCOME_STATEMENT`, **`CASH_FLOW_STATEMENT`**
  (audit v4.7 §3.1 Finding #5 — obligatoire en IFRS IAS 7 et SYSCOHADA TAFIRE).
- `BalanceSheet` (DTO) — bilan à une date. Sections `assets` (ACTIF), `liabilities`
  (PASSIF), `equity` (CAPITAUX_PROPRES). Chaque section groupe par `reportingSubcategory`
  (COURANT/NON_COURANT/N_A) puis par compte. Porte `totalAssets`, `totalLiabilities`,
  `totalEquity`, `balanced` (boolean — `totalAssets == totalLiabilities + totalEquity`).
- `IncomeStatement` (DTO) — compte de résultat sur une plage. Sections `products`
  (PRODUITS), `charges` (CHARGES). Porte `totalProducts`, `totalCharges`, `netResult`.
- `CashFlowStatement` (DTO) — **audit v4.7 §3.1 Finding #5** — tableau de flux de trésorerie
  (IAS 7 / SYSCOHADA TAFIRE). Méthode indirecte : résultat net ± amortissements et
  dépréciations ± variations BFR ± cessions + investissements + financements. Structure :
  - `OperatingFlows { netIncome, depreciationAmortization, accountsReceivableVariation,
    inventoryVariation, accountsPayableVariation, otherWorkingCapitalVariation, total }`.
  - `InvestingFlows { fixedAssetsAcquisitions, fixedAssetsDisposals, otherInvestingFlows,
    total }`.
  - `FinancingFlows { capitalVariation, loansVariation, dividendsPaid,
    otherFinancingFlows, total }`.
  - `netCashFlow` (= operating.total + investing.total + financing.total), `openingCash`,
    `closingCash`, `balanced` (`closingCash == openingCash + netCashFlow`).

### Règles métier clés

1. **Référentiel-agnostique (§4)** — le calcul utilise UNIQUEMENT `reportingClass` et
   `reportingSubcategory`. Aucun `if (framework == SYSCOHADA)` nulle part. Si le bilan est
   faux pour un référentiel, c'est le `reportingClass` des comptes (initialisé par
   `:chart-of-accounts`) qui est en cause, pas ce module.
2. **Solde par compte selon `normalBalance`** :
   - Compte `DEBIT` (ACTIF, CHARGES) : solde = débit − crédit
   - Compte `CREDIT` (PASSIF, CAPITAUX_PROPRES, PRODUITS) : solde = crédit − débit
3. **Invariant bilan** : `totalAssets == totalLiabilities + totalEquity`. Le flag
   `balanced` l'indique à l'appelant. Peut être `false` si l'exercice n'est pas encore
   clôturé (écritures de résultat non postées).
4. **Invariant compte de résultat** : `netResult == totalProducts − totalCharges`.
5. **Snapshot immuable** — une fois créé, un `FinancialStatementSnapshot` ne peut pas être
   modifié ni supprimé. 409 `SNAPSHOT_ALREADY_EXISTS` si on tente d'en créer un pour le même
   `(type, periodId)`.
6. **Filtrage par date côté DB** (Vague 2 item 2.1) — `findAllPostedUpToDate` et
   `findAllPostedBetweenDates` filtrent en SQL, pas en Java (perf).

### Cycle de vie des objets

- `FinancialStatementSnapshot` : créé → immuable (pas de transition).
- `BalanceSheet` / `IncomeStatement` : DTOs recalculés à chaque appel (pas de persistance
  sauf si l'utilisateur crée un snapshot).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/financial-statements/balance-sheet?asOf=` | Génère le bilan à une date donnée | 422 `AS_OF_REQUIRED` |
| GET | `/api/v1/companies/{companyId}/financial-statements/income-statement?from=&to=` | Génère le compte de résultat sur une plage | 422 dates invalides |
| GET | `/api/v1/companies/{companyId}/financial-statements/cash-flow-statement?from=&to=` | **audit v4.7 §3.1 Finding #5** — Tableau de flux de trésorerie (IAS 7 / SYSCOHADA TAFIRE), méthode indirecte. Retourne `CashFlowStatement {operating, investing, financing, netCashFlow, openingCash, closingCash, balanced}`. | 422 dates invalides |
| POST | `/api/v1/companies/{companyId}/financial-statements/snapshots` | Crée un snapshot figé. Corps : `{type, periodId, asOfDate? / fromDate?, toDate?}` (`type` ∈ `BALANCE_SHEET` / `INCOME_STATEMENT` / `CASH_FLOW_STATEMENT`). | 404 `FiscalPeriod`/`Snapshot`, 409 `SNAPSHOT_ALREADY_EXISTS`, 422 champs invalides |
| POST | `/api/v1/companies/{companyId}/financial-statements/snapshots/closing/periods/{periodId}` | **audit v4.7 §3.1 Finding #9** — Crée automatiquement les snapshots de clôture `BALANCE_SHEET` + `INCOME_STATEMENT` pour la période donnée. Idempotent (si les snapshots existent déjà, ne renvoie pas 409 — retourne les snapshots existants). À appeler après `POST /fiscal-years/{id}/close` pour figer les états valables à la clôture (piste d'audit). | 404 `FiscalPeriod` |
| GET | `/api/v1/companies/{companyId}/financial-statements/snapshots` | Liste les snapshots figés (⚠️ non paginé) | — |
| GET | `/api/v1/companies/{companyId}/financial-statements/snapshots/{snapshotId}` | Récupère un snapshot par ID | 404 `Snapshot` |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ReportingClass`, `ApplicationEventPublisher`.
- `:chart-of-accounts` — `Account`, `AccountRepository`, `ReportingSubcategory`.
- `:accounting-engine` — `JournalLine`, `JournalLineRepository`
  (`findAllPostedUpToDate`, `findAllPostedBetweenDates`), `FiscalPeriod`,
  `FiscalPeriodRepository`.

### Modules qui dépendent de celui-ci

- `:reporting` — consomme les DTOs `BalanceSheet`/`IncomeStatement` (ou recalcule
  directement) pour les exports PDF/CSV et le dashboard.
- `:app` — tests d'intégration `FinancialStatementsIntegrationTest`.

### Événements publiés / consommés

- **Publie** : `FinancialStatementSnapshotCreatedEvent` (à la création d'un snapshot).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V18__financial_statements.sql` — table
  `financial_statement_snapshot`. Unique `(company_id, type, period_id)`. CHECK sur `type`
  (2 valeurs). Index sur `company_id` et `period_id`. Le contenu de l'état est sérialisé en
  JSONB (`content_json`).

## Points d'attention (hérités de l'audit)

- ⚠️ **M8 — Aucune pagination sur `GET /snapshots`** — retourne tous les snapshots de
  l'entreprise. Sur un historique long (10 ans × 12 périodes × 2 types = 240 snapshots), la
  réponse reste gérable mais le client mobile doit prévoir un filtre par période côté UI.
- ⚠️ **Bilan potentiellement déséquilibré** — le flag `balanced` peut être `false` si
  l'exercice n'est pas clôturé (écritures de résultat non postées via
  `POST /accounting-engine/fiscal-years/{id}/close`). Le client mobile doit afficher ce
  flag clairement et suggérer à l'utilisateur de clôturer l'exercice si besoin.
- ⚠️ **Pas de TAFIRE / liasse fiscale** — le module ne produit QUE le bilan et le compte de
  résultat. Les états réglementaires spécifiques (TAFIRE SYSCOHADA, liasse fiscale PCG,
  annexes PCGR_CANADA) ne sont **pas implémentés** (BACKLOG). Le client mobile ne doit pas
  annoncer ces états comme disponibles.
- ⚠️ **Pas de comparatif N-1** — les endpoints ne supportent pas la comparaison avec
  l'exercice précédent. L'utilisateur doit récupérer deux snapshots séparés et faire la
  comparaison côté client.
- ⚠️ **`reportingSubcategory` doit être posée sur les comptes** — si l'utilisateur crée un
  sous-compte de niveau 4 sans `reportingSubcategory` (COURANT/NON_COURANT), il sera groupé
  sous `N_A` dans le bilan. Le client mobile doit guider l'utilisateur lors de la création
  de comptes pour qu'il renseigne cette classification.

## Tests

Couvert par `FinancialStatementsIntegrationTest` dans `:app` — test de bilan équilibré,
test de compte de résultat, test de snapshot figé (immuabilité + 409 sur doublon). Tests
principalement en SYSCOHADA (audit 3.3 — couverture référentielle déséquilibrée).
