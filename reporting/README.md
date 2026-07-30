# Module : reporting

> Exports PDF/CSV et tableau de bord de synthèse — orchestration des autres modules pour produire les états.

## Rôle du module

Le module `:reporting` est la dernière phase du projet (Phase 17). Il est **always-on**
(activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`) et fonctionne
pour les 6 référentiels — il utilise `reportingClass` et `taxMappingCode` des comptes
pour classifier (audit M4), jamais le code du référentiel.

C'est un module d'**orchestration** : il ne persiste aucune donnée propre (la migration
V19_001 est vide — `SELECT 1`). Il délègue :
- Les exports PDF à `:document-generation` (bilan, compte de résultat, grand livre,
  rapport bailleur).
- Les exports CSV à son propre service (grand livre, balance générale).
- Le calcul du bilan/compte de résultat à `:financial-statements`.
- Le calcul du grand livre/balance à `:accounting-engine`.
- Le rapport bailleur à `:funds-grants`.

## Ce qu'il fait précisément

### Composants clés

- `ReportingService` — orchestre les exports et le dashboard. **17 statements** supportés
  au total (5 d'origine + 12 ajoutés par les vagues Parts C/D/E — voir §« Endpoints
  exposés » pour la matrice complète).
- `Dashboard` (DTO) — tableau de bord de synthèse. Champs : `cashPosition`,
  `totalReceivables`, `totalPayables`, `topExpenses` (top 5), `topRevenues` (top 5),
  `pendingApprovals` (compté réellement depuis `ApprovalRequestRepository` — Part C3,
  correction de l'audit M6), `overdueInvoices`.
- `AgedBalance` (DTO) — balance âgée (clients ou fournisseurs). Champs : `current`,
  `d0_30`, `d31_60`, `d61_90`, `d90_plus`, `totalBalanceDue`, `invoiceCount`.
- `ExportResult` (DTO) — résultat d'un export. Champs : `companyId`, `statement`,
  `format`, `content` (byte[]), `contentType`, `filename`.

### Règles métier clés

1. **Exports PDF via `:document-generation`** — `balance_sheet`, `income_statement`,
   `donor_report` délèguent à `DocumentGenerationService.generateDocument`. Le PDF est
   immuable (voir `:document-generation`).
2. **Exports CSV générés directement** — `general_ledger` et `trial_balance` produisent un
   CSV à la volée (pas de stockage). Format : `Code compte;Libellé;Total débit;Total
   crédit;Solde`.
3. **Dashboard référentiel-agnostique (audit M4)** — la version corrigée filtre par
   `reportingClass` et `taxMappingCode` au lieu de préfixes de code SYSCOHADA :
   - `cashPosition` : `ACTIF` + `taxMappingCode = "CASH"`, fallback `ACTIF` commençant par
     `"5"` (rétro-compat SYSCOHADA).
   - `totalReceivables` : `ACTIF` + `taxMappingCode = "ACCOUNTS_RECEIVABLE"`, fallback
     `ACTIF` commençant par `"411"`.
   - `totalPayables` : `PASSIF` + `taxMappingCode = "ACCOUNTS_PAYABLE"`, fallback `PASSIF`
     commençant par `"40"`.
   - `topExpenses` : tous les comptes `CHARGES` (top 5 par `totalDebit`).
   - `topRevenues` : tous les comptes `PRODUITS` (top 5 par `totalCredit`).
4. **`pendingApprovals` corrigé (Part C3)** — le dashboard ne ment plus. La valeur est
   désormais calculée via `approvalRequestRepository.countByCompanyIdAndStatus(companyId,
   ApprovalStatus.PENDING)` (un `COUNT` SQL, pas une matérialisation de la liste). Avant
   cette correction, le KPI était hardcodé à 0.
5. **Factures échues** — `overdueInvoices` = count des factures `ISSUED` dont `dueDate <
  today`.
6. **Gating des exports sectoriels (Part C2)** — les statements `donor_report`,
   `tax_declaration`, `purchase_register`, `aged_balance_suppliers` (CSV),
   `inventory_valuation`, `stock_movement_register`, `time_billing_utilization`,
   `fixed_assets_register`, `fx_operations_register` exigent que le module sectoriel
   correspondant soit activé pour la société. Sinon : `403 MODULE_NOT_ENABLED`. Voir
   §« Gating des exports par ModuleCode » ci-dessous pour la matrice complète.
7. **resourceId policy (Part C4)** — pour les périodes OPEN (cas par défaut),
   `resourceId = UUID.randomUUID()` à chaque appel (les données sous-jacentes sont
   mutables, donc un cache déterministe renverrait un PDF périmé). Pour les périodes
   CLOSED (policy future, non implémentée), `resourceId` déterministe dérivé du snapshot
   figé. Voir §« resourceId policy » ci-dessous.

### Cycle de vie des objets

Pas de cycle de vie — le module est stateless (pas de persistance propre).

## Endpoints exposés

### Exports — `GET /api/v1/companies/{companyId}/reporting/exports/{statement}`

Paramètres : `?format=` (pdf/csv selon le statement), `?from=`, `?to=`, `?resourceId=`
(requis pour `donor_report` — c'est le `grantId`).

Le tableau ci-dessous récapitule **les 17 statements** supportés (5 d'origine + 12 ajoutés
par les vagues 2026-07-25 Parts C/D/E). La colonne **Gate** indique le `ModuleCode` requis
(vérifié via `ModuleAccessGuard.ensureEnabled` en tête du `export()`). « aucun » = statement
toujours accessible (module always-on ou vue agrégée transverse).

| Statement | Format | Gate | Vue JSON source (mobile) | Description |
|---|---|---|---|---|
| `balance_sheet` | PDF | aucun | `GET /financial-statements/balance-sheet` | Bilan |
| `income_statement` | PDF | aucun | `GET /financial-statements/income-statement` | Compte de résultat |
| `general_ledger` | CSV | aucun | `GET /accounting-engine/ledger` | Grand livre |
| `trial_balance` | CSV | aucun | `GET /accounting-engine/trial-balance` | Balance générale |
| `dashboard` | JSON | aucun | (direct — `GET /reporting/dashboard`) | KPIs de synthèse |
| `aged_balance` | JSON | aucun | (direct — `GET /reporting/aged-balance`) | Balance âgée clients |
| `aged_balance_suppliers` | JSON/CSV | `PURCHASING` | (direct — `GET /reporting/aged-balance-suppliers`) | Balance âgée fournisseurs |
| `tax_declaration` | CSV | `TAX` | `GET /tax/declarations` | Déclaration TVA |
| `purchase_register` | CSV | `PURCHASING` | `GET /purchase-invoices` | Registre achats |
| `expense_register` | CSV | aucun | `GET /expense-reports` | Registre dépenses |
| `payroll_summary` | CSV | aucun | `GET /payroll-runs` | État masse salariale |
| `inventory_valuation` | CSV | `INVENTORY` | `GET /inventory/valuation` | Valorisation stock |
| `stock_movement_register` | CSV | `INVENTORY` | `GET /inventory/stock-moves` | Mouvements de stock |
| `time_billing_utilization` | CSV | `TIME_BILLING` | `GET /time-billing/utilization` | Utilisation temps |
| `fixed_assets_register` | CSV | `FIXED_ASSETS` | `GET /fixed-assets` | Registre immobilisations |
| `fx_operations_register` | CSV | `FX_OPERATIONS` | `GET /fx-operations` | Registre opérations change |
| `donor_report` | PDF | `FUNDS_GRANTS` | `GET /funds-grants/grants/{id}/donor-report` | Rapport bailleur |

> **Vue JSON vs export** : pour chaque statement, le mobile dispose de **deux chemins** —
> (1) un endpoint **JSON** pour l'affichage in-app (tableau/graphe interactif), et (2) le
> endpoint **CSV/PDF** ci-dessus pour le bouton « Télécharger ». Les endpoints JSON vivent
> dans leurs modules respectifs (`:financial-statements`, `:accounting-engine`, `:tax`,
> `:purchasing`, `:expenses`, `:payroll`, `:inventory`, `:time-billing`, `:fixed-assets`,
> `:fx-operations`, `:funds-grants`) — les 3 endpoints JSON `:reporting` (dashboard,
> aged-balance, aged-balance-suppliers) sont listés dans la colonne « Vue JSON source ».

> **Codes d'erreur** : `404` (resource introuvable), `422 UNKNOWN_STATEMENT` (statement
> inconnu), `422 GRANT_ID_REQUIRED` (`resourceId` manquant pour `donor_report`),
> `403 MODULE_NOT_ENABLED` (module sectoriel non activé — voir §« Gating » ci-dessous).

### Autres endpoints — JSON direct (dashboard + balances âgées)

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/reporting/dashboard` | Tableau de bord de synthèse (KPIs). Référentiel-agnostique depuis l'audit M4 (filtre par `reportingClass`/`taxMappingCode`, pas par préfixe SYSCOHADA). | — |
| GET | `/api/v1/companies/{companyId}/reporting/aged-balance` | Balance âgée clients (audit M5) — ventile le solde dû des factures `ISSUED`/`PARTIALLY_PAID` par tranche d'âge. | — |
| GET | `/api/v1/companies/{companyId}/reporting/aged-balance-suppliers` | Balance âgée fournisseurs (Part D1) — symétrique côté `:purchasing`. Le gate `PURCHASING` est appliqué **au niveau du statement CSV `aged_balance_suppliers`** ; cet endpoint JSON est laissé accessible aux `VIEWER` même si `PURCHASING` n'est pas activé (le résultat sera vide). | — |

## Gating des exports par ModuleCode (Part C2)

Avant de dispatcher vers la méthode d'export spécialisée, `ReportingService.export()`
appelle `ensureModuleEnabledForStatement(companyId, statement)` qui déclenche
`moduleAccessGuard.ensureEnabled(companyId, ModuleCode.XXX)` pour les statements
sectoriels. La liste exhaustive :

| Statement(s) | ModuleCode gate |
|---|---|
| `donor_report` | `FUNDS_GRANTS` |
| `tax_declaration` | `TAX` |
| `purchase_register`, `aged_balance_suppliers` (CSV) | `PURCHASING` |
| `inventory_valuation`, `stock_movement_register` | `INVENTORY` |
| `time_billing_utilization` | `TIME_BILLING` |
| `fixed_assets_register` | `FIXED_ASSETS` |
| `fx_operations_register` | `FX_OPERATIONS` |

Les statements communs ne sont pas gated — ils s'appuient sur des modules always-on
(`EXPENSES` pour `expense_register`, `PAYROLL` pour `payroll_summary`) ou sur des vues
agrégées transverses (`balance_sheet`, `income_statement`, `general_ledger`,
`trial_balance`, `dashboard`, `aged_balance` — qui consomment uniquement des modules
always-on comme `FINANCIAL_STATEMENTS`, `ACCOUNTING_ENGINE`, `INVOICING`, `THIRD_PARTIES`).

Si le module sectoriel n'est pas activé pour la société, l'endpoint retourne
**`403 MODULE_NOT_ENABLED`** (même contrat d'erreur que les modules sectoriels eux-mêmes —
voir `MOBILE_SYNC_2026-07-24_business-type-restructuring.md` §1.3).

## resourceId policy (Part C4)

La colonne `resourceId` (UUID) est utilisée par `:document-generation` comme clé de cache
pour le PDF généré. La politique dépend du caractère figé des données sous-jacentes :

- **Période OPEN (cas par défaut)** : `resourceId = UUID.randomUUID()` à chaque appel. Les
  données sous-jacentes (écritures, factures, etc.) peuvent changer à tout moment, donc un
  cache déterministe renverrait un PDF périmé. L'UUID aléatoire force `:document-generation`
  à régénérer le PDF à chaque export.
- **Période CLOSED (policy future, non implémentée)** : `resourceId` déterministe dérivé du
  snapshot figé (`FinancialStatementSnapshot.id` pour le bilan/compte de résultat, ou
  `grantId` pour le rapport bailleur). Les données étant immuables une fois la période
  clôturée, le cache devient légitime — tous les appelants reçoivent le même PDF.

Concernant `donor_report` (rapport bailleur) : la même politique s'applique — `UUID.randomUUID()`
tant que la subvention est en cours (les écritures de don/dépense affectée évoluent en
continu), `grantId` déterministe une fois la subvention clôturée (période `CLOSED`). Non
implémenté pour le MVP — à faire dans un futur prompt (il faut d'abord exposer un moyen de
résoudre le snapshot pour une période donnée depuis `:financial-statements`).

## pendingApprovals — correction (Part C3)

Le KPI `Dashboard.pendingApprovals` était **hardcodé à 0** (audit M6 — le dashboard
mentait). La version corrigée (Part C3) calcule le nombre réel d'`ApprovalRequest`
`PENDING` via `approvalRequestRepository.countByCompanyIdAndStatus(companyId,
ApprovalStatus.PENDING)`. Côté mobile, le KPI est désormais fiable — plus besoin de
contourner en appelant `GET /approval-workflow/requests?status=PENDING` et de compter
côté client.

## Pourquoi SANTE / EDUCATION n'ont pas de rapports dédiés

Les secteurs `SANTE` (hôpitaux) et `EDUCATION` (écoles) ne disposent pas de statements de
reporting dédiés dans le tableau ci-dessus. C'est volontaire au MVP :

- Les deux types métier (`HOSPITAL`, `SCHOOL`) partagent le **même socle comptable** que
  les autres entreprises lucratives (`FOR_PROFIT`) — bilan, compte de résultat, grand
  livre, balance, dashboard, balances âgées leur suffisent.
- Les spécificités sectorielles (stocks de médicaments pour `HOSPITAL` → `inventory_valuation`
  et `stock_movement_register` via le gate `INVENTORY` ; immobilisations pour `SCHOOL` →
  `fixed_assets_register`) sont déjà couvertes par les statements sectoriels existants quand
  le module correspondant est activé. `HOSPITAL` active `INVENTORY` par défaut (correction
  V23 — stocks de médicaments/consommables).
- Les rapports réglementaires spécifiques (ex. déclaration d'agrément sanitaire, livret
  scolaire) sont hors périmètre v1 — à cadrer dans un futur prompt dédié une fois le socle
  validé en production.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — exceptions, `ReportingClass`, `TenantContext`.
- `:company` — `ModuleAccessGuard`, `ModuleCode` (gating des exports sectoriels — Part C2).
- `:accounting-engine` — `AccountingEngineService.getLedger`, `getTrialBalance` (pour les
  exports CSV grand livre / balance et le dashboard).
- `:financial-statements` — `FinancialStatementsService.getBalanceSheet`,
  `getIncomeStatement` (pour les exports PDF bilan / compte de résultat).
- `:document-generation` — `DocumentGenerationService.generateDocument`,
  `getDocumentContent` (pour les exports PDF bilan / compte de résultat / rapport bailleur).
- `:funds-grants` — `FundsGrantsService.getDonorReport` (pour l'export `donor_report`).
- `:chart-of-accounts` — `Account`, `AccountRepository` (pour récupérer la
  `reportingClass` des comptes dans le dashboard — la `TrialBalanceLine` ne la porte pas).
- `:invoicing` — `SalesInvoiceRepository` (pour `overdueInvoices` et la balance âgée
  clients dans le dashboard).
- `:approval-workflow` — `ApprovalRequestRepository` (pour `pendingApprovals` dans le
  dashboard — Part C3).
- `:tax` — `TaxService.getDeclaration` (pour l'export `tax_declaration` — Part D2).
- `:third-parties` — `ThirdPartyRepository` (pour résoudre les noms de fournisseurs et
  d'employés dans les exports `purchase_register` et `expense_register`).
- `:purchasing` — `PurchaseInvoiceRepository` (pour `purchase_register` et la balance âgée
  fournisseurs — Parts D1/D3).
- `:expenses` — `ExpenseReportRepository`, `ExpenseLineRepository` (pour `expense_register`
  — Part D4).
- `:payroll` — `PayrollRunRepository`, `PayslipRepository` (pour `payroll_summary` — Part D5).
- `:inventory` — `InventoryService.getAggregatedValuation`, `listStockMoves`,
  `ItemRepository`, `WarehouseRepository` (pour `inventory_valuation` et
  `stock_movement_register` — Part E4).
- `:time-billing` — `TimeBillingService.getUtilization` (pour `time_billing_utilization`
  — Part E4).
- `:fixed-assets` — `AssetRepository`, `DepreciationScheduleLineRepository` (pour
  `fixed_assets_register` — Part E4).
- `:fx-operations` — `FxOperationRepository` (pour `fx_operations_register` — Part E4).

### Modules qui dépendent de celui-ci

- `:app` — tests d'intégration `ReportingIntegrationTest`.
- L'application mobile consomme directement ces endpoints pour le dashboard et les exports.

### Événements publiés / consommés

- **Publie** : aucun (le module ne fait que des lectures et des générations PDF via
  `:document-generation`, qui lui publie `DocumentGeneratedEvent`).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V19_001__reporting.sql` — **vide** (`SELECT 1`). Le
  module ne persiste aucune donnée propre. Les PDF générés sont stockés via
  `:document-generation` (table `generated_document`). Les exports CSV sont générés à la
  volée et renvoyés directement dans la réponse HTTP.

## Points d'attention (hérités de l'audit)

- ⚠️ **M4 — Dashboard corrigé** : la version initiale filtrait les comptes par préfixe de
  code SYSCOHADA (`"5*"` trésorerie, `"411*"` clients, `"40*"` fournisseurs, `"6*"`
  charges, `"7*"` produits). Cela cassait entièrement le dashboard pour les autres
  référentiels — PCGR_CANADA a `5 = Avoir des actionnaires`, `6 = Produits`, `7 = Charges`
  (inversé !). La version corrigée filtre par `reportingClass` et `taxMappingCode`. **Non-breaking**
  côté API (format de réponse `Dashboard` inchangé), mais les valeurs retournées peuvent
  différer pour les entreprises non-SYSCOHADA (elles sont désormais correctes).
- ✅ **M5 — Balance âgée corrigée (Part D1)** : la balance âgée clients est désormais
  exposée via `GET /reporting/aged-balance` (ventilation par tranche d'âge depuis
  `dueDate`). La balance âgée fournisseurs est exposée via `GET /reporting/aged-balance-suppliers`
  (symétrique côté `:purchasing`). Le dashboard affiche toujours des totaux agrégés pour
  `totalReceivables`/`totalPayables` (sans ventilation) — c'est volontaire (KPI de synthèse,
  pas un rapport détaillé). Pour la balance âgée par tiers individuel, utiliser toujours
  `GET /third-parties/{id}/aged-balance`.
- ✅ **M6 — `pendingApprovals` corrigé (Part C3)** : le KPI `Dashboard.pendingApprovals`
  n'est plus hardcodé à 0. Il est calculé via
  `approvalRequestRepository.countByCompanyIdAndStatus(companyId, ApprovalStatus.PENDING)`.
  Voir §« pendingApprovals — correction » ci-dessus.
- ⚠️ **M7 — Rapport bailleur sources non reconciliables (toujours ouvert)** —
  `exportDonorReportPdf` délègue à `:funds-grants.getDonorReport` qui calcule
  `balanceRemaining` à partir de sources non reconciliables (`DonationReceipt` vs
  `JournalLine`). Le PDF peut afficher un solde incohérent. À corriger en session dédiée.
- ⚠️ **M8 — Aucune pagination sur `GET /exports/general_ledger`** — le CSV généré peut être
  très volumineux (toutes les écritures POSTED sur la période). Sur une entreprise avec
  5 ans d'historique, le CSV peut faire plusieurs Mo. Le client mobile doit implémenter un
  cache local et prévoir un timeout long. Pour limiter la taille, passer `?from=&to=` (le
  filtrage par exercice via `?fiscalYearId=` n'est pas disponible sur cet endpoint — le
  filtrage se fait par `from`/`to` uniquement).
- ⚠️ **Pas de TAFIRE / liasse fiscale** — les formats réglementaires par pays ne sont pas
  implémentés (BACKLOG). Le client mobile ne doit pas annoncer ces exports comme
  disponibles.
- ⚠️ **`resourceId` aléatoire pour `donor_report`** — `exportDonorReportPdf` génère un
  `resourceId = UUID.randomUUID()` à chaque appel, ce qui crée un nouveau
  `GeneratedDocument` à chaque export (contournant l'immuabilité). À corriger en utilisant
  le `grantId` comme `resourceId` (la policy déterministe pour les périodes CLOSED est
  documentée §« resourceId policy » ci-dessus — non implémentée pour le MVP).

## Tests

Couvert par `ReportingIntegrationTest` dans `:app` — export PDF bilan, export CSV grand
livre, export CSV balance, dashboard (cashPosition, totalReceivables, overdueInvoices,
pendingApprovals ≠ 0), rapport bailleur, balance âgée clients/fournisseurs, et les 12
nouveaux exports CSV des vagues Parts C/D/E (tax_declaration, purchase_register,
expense_register, payroll_summary, inventory_valuation, stock_movement_register,
time_billing_utilization, fixed_assets_register, fx_operations_register). Tests
principalement en SYSCOHADA (audit 3.3). Le gating `MODULE_NOT_ENABLED` sur les exports
sectoriels est couvert par `ModuleToggleIntegrationTest` (activer/désactiver un module
sectoriel → vérifier que l'export correspondant retourne 403 puis 200).
