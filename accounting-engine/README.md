# Module : accounting-engine

> Cœur comptable : exercices, périodes, journaux, écritures (DRAFT → POSTED → VOIDED), grand livre et balance.

## Rôle du module

Le module `:accounting-engine` est le cœur comptable du projet. Il est **always-on** (activé
pour tous les secteurs via `BusinessTypeModuleService.alwaysOnModules()`) et fonctionne pour les
6 référentiels — il utilise uniquement `Account.reportingClass` pour classifier les comptes,
jamais le code du référentiel.

Il porte l'entité `JournalEntry` qui est la **source de vérité comptable** : toute écriture
dans le système (manuelle, facture, amortissement, COGS, clôture d'exercice) aboutit à une
`JournalEntry` POSTED. Les états financiers (`:financial-statements`) et le reporting
(`:reporting`) consomment les `JournalLine` POSTED pour produire bilan, compte de résultat,
grand livre et balance.

Le module implémente aussi la **clôture d'exercice** (`closeFiscalYear`) qui solde les
comptes de produits/charges contre le compte de résultat — le seul endpoint du module qui
génère des écritures automatiquement (les autres écritures auto sont générées par les modules
métier `invoicing`, `fixed-assets`, `inventory`, `funds-grants`).

## Ce qu'il fait précisément

### Entités principales

- `FiscalYear` — exercice fiscal. `(startDate, endDate)` unique par entreprise. Statut
  `OPEN` → `LOCKED` (verrouillé manuellement) → `CLOSED` (clôture via `closeFiscalYear`).
  Génère 12 `FiscalPeriod` mensuelles à la création.
- `FiscalPeriod` — période mensuelle. Statut `OPEN` → `LOCKED`. Une période LOCKED refuse
  tout postage d'écriture (409 `PERIOD_LOCKED`).
- `Journal` — journal comptable (ex. `VT` ventes, `AC` achats, `BQ` banque, `OD` opérations
  diverses). `(companyId, code)` unique.
- `JournalEntry` — écriture comptable. Statut `DRAFT`/`PENDING_APPROVAL`/`POSTED`/`VOIDED`.
  Porte `journalId`, `fiscalPeriodId`, `entryDate`, `reference` (numéro attribué au postage
  via `:document-numbering`), `description`, `postedAt`, `postedBy`,
  `reversalOfEntryId` (si contre-passation), `sourceModule` (MANUAL/FIXED_ASSETS/INVENTORY/
  INVOICING/FUNDS_GRANTS/REVERSAL), `idempotencyKey` (unique par entreprise).
- `JournalLine` — ligne d'écriture. `accountId`, `accountCode`, `thirdPartyId` (nullable),
  `debit`, `credit` (mutuellement exclusifs, CHECK DB `chk_jl_exclusive`), `lineNumber`,
  `description`. Champs multi-devises posés mais inactifs (`amountTransactionCurrency`,
  `transactionCurrency`, `exchangeRateUsed` default 1).
- `JournalLineAnalyticalTag` — ventilation analytique d'une ligne. `(journalLineId, planId,
  valueId)` unique. `allocationPercentage` (0-100).

### Règles métier clés

1. **Équilibre débit = crédit** vérifié à 3 niveaux : applicatif à la création, applicatif au
   postage, et **trigger DB statement-level (V36)** `trg_journal_entry_balance_ins` / `_upd` /
   `_del` (3 triggers `FOR EACH STATEMENT` avec transition tables — complexité O(N) au lieu
   de O(N²)). Le trigger original (V7_002) était `FOR EACH ROW` et exécutait un `SELECT SUM`
   par ligne — sur une écriture de 500 lignes (paie), cela faisait 500 `SUM` queries × scan
   moyen ~250 lignes = 125K lignes lues (latence >10s). Le nouveau trigger ne fait qu'un seul
   `SUM` par `journal_entry_id` distinct touché par la statement.
   **Correction critique (session 22)** : les 3 triggers sont séparés car PostgreSQL ne
   supporte pas `REFERENCING transition tables` sur un trigger multi-événement
   (`INSERT OR UPDATE OR DELETE`). Règle PostgreSQL : `INSERT` ne référence que `NEW TABLE`,
   `DELETE` ne référence que `OLD TABLE`, `UPDATE` référence les deux. Bug antérieur :
   l'`INSERT` trigger référençait incorrectement `OLD TABLE` → erreur `42P17`.
   Le trigger ne vérifie l'équilibre QUE sur les écritures `POSTED` (audit M3 — ne couvre pas
   la transition `DRAFT → POSTED`, les `DRAFT`/`PENDING_APPROVAL` peuvent être
   temporairement déséquilibrées).
2. **`debit` et `credit` mutuellement exclusifs** sur une ligne — CHECK DB
   `chk_jl_exclusive` (sauf si les deux sont à 0, ce qui est autorisé mais inutile).
3. **Idempotence client** : `Idempotency-Key` header obligatoire sur `POST /journal-entries`
   (422 `IDEMPOTENCY_KEY_REQUIRED` si absent). Unique par entreprise
   (`uc_je_company_idempotency`). Un rejeu renvoie l'écriture existante.
4. **Postage** : `DRAFT → POSTED` (si auto-approved) ou `DRAFT → PENDING_APPROVAL` (si
   montant > seuil). Le `reference` n'est attribué qu'au passage à POSTED (via
   `:document-numbering`).
5. **Contre-passation idempotente** (audit M2) : `POST /journal-entries/{id}/reverse` crée
   une écriture inversée avec `idempotencyKey = "reversal-" + originalId` (déterministe). Un
   retry renvoie l'écriture de contre-passation existante (409 si la clé est déjà utilisée).
6. **Période LOCKED → refus de postage** (409 `PERIOD_LOCKED`). Exercice CLOSED → refus
   (409 `FISCAL_YEAR_CLOSED`).
7. **Compte inactif → refus de postage** (422 `ACCOUNT_INACTIVE`).
8. **Tags analytiques obligatoires** : si le compte porte `requiresAnalyticalTagPlanIds`,
   chaque plan listé doit avoir au moins un tag sur la ligne, et la somme des
   `allocationPercentage` par plan doit être 100% (422 `ANALYTICAL_TAG_REQUIRED`).
9. **Clôture d'exercice** : solde les comptes PRODUITS/CHARGES contre un compte de
   CAPITAUX_PROPRES. Résolution du compte de résultat référentiel-agnostique (audit B1) :
   (a) `taxMappingCode = "FISCAL_RESULT"`, (b) sinon compte racine (level=1) de
   `CAPITAUX_PROPRES`, (c) sinon n'importe quel compte de `CAPITAUX_PROPRES` actif. Lève
   `RESULT_ACCOUNT_NOT_FOUND` si rien trouvé.
10. **Rôle requis** sur les endpoints mutatifs (S1 fix) : `closeFiscalYear` → ADMIN,
    `postJournalEntry` → ACCOUNTANT, `reverseJournalEntry` → ADMIN.

### Cycle de vie des objets

- `JournalEntry` : `DRAFT → PENDING_APPROVAL → POSTED → VOIDED`
  - `DRAFT → POSTED` : via `POST /journal-entries/{id}/post` (si auto-approved)
  - `DRAFT → PENDING_APPROVAL` : via `POST /journal-entries/{id}/post` (si montant > seuil)
  - `PENDING_APPROVAL → POSTED` : via `@EventListener(ApprovalDecidedEvent)` après APPROVED
    (audit B2 — `postJournalEntryAfterApproval`)
  - `PENDING_APPROVAL → DRAFT` : via `@EventListener(ApprovalDecidedEvent)` après REJECTED
    ou CANCELLED (audit B2 — `revertToDraftAfterRejection`)
  - `POSTED → VOIDED` : via `POST /journal-entries/{id}/reverse` (contre-passation). Crée
    une nouvelle écriture POSTED avec `sourceModule = REVERSAL` et `reversalOfEntryId`
    pointant vers l'originale.
- `FiscalYear` : `OPEN → LOCKED → CLOSED`
  - `OPEN → LOCKED` : via `PATCH /fiscal-years/{id}/lock`
  - `OPEN → CLOSED` : via `POST /fiscal-years/{id}/close` (génère l'écriture de clôture)
- `FiscalPeriod` : `OPEN → LOCKED` via `PATCH /fiscal-periods/{id}/lock`

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years` | Crée un exercice + 12 périodes mensuelles | 422 `FISCAL_YEAR_DATES_INVALID`/`FISCAL_YEAR_OVERLAP` |
| GET | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years` | Liste les exercices de l'entreprise (suite 3 — exposition manquante) | — |
| GET | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years/{fiscalYearId}` | Détail d'un exercice (suite 3 — exposition manquante) | 404 `FiscalYear` |
| GET | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years/{fiscalYearId}/periods` | Liste les périodes d'un exercice (suite 3 — exposition manquante) | 404 `FiscalYear` |
| GET | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years/active` | **DÉPRÉCIÉ** (suite 4) — Récupère l'exercice fiscal actif. Ne plus utiliser côté mobile : `resolveFiscalYear` résout désormais l'exercice par défaut (OPEN contenant aujourd'hui, sinon dernier OPEN). | 404 `NO_ACTIVE_FISCAL_YEAR` |
| POST | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years/{fiscalYearId}/activate` | **DÉPRÉCIÉ** (suite 4) — Active un exercice fiscal. Ne plus appeler côté mobile — la colonne `active_fiscal_year_id` n'est plus lue par les endpoints de données. | 404 `FiscalYear` |
| PATCH | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years/{fiscalYearId}/lock` | Verrouille un exercice | 404, 409 |
| POST | `/api/v1/companies/{companyId}/accounting-engine/fiscal-years/{fiscalYearId}/close` | Clôture un exercice (génère écriture de résultat) — ADMIN | 403 `INSUFFICIENT_ROLE`, 404, 409 `NO_RESULT_TO_CLOSE`/`NO_ENTRIES_TO_CLOSE`, 422 `RESULT_ACCOUNT_NOT_FOUND`/`JOURNAL_OD_NOT_FOUND`/`PERIOD_NOT_FOUND` |
| PATCH | `/api/v1/companies/{companyId}/accounting-engine/fiscal-periods/{periodId}/lock` | Verrouille une période | 404 |
| POST | `/api/v1/companies/{companyId}/accounting-engine/journals` | Crée un journal | 409 `JOURNAL_CODE_ALREADY_EXISTS` |
| POST | `/api/v1/companies/{companyId}/accounting-engine/journal-entries` | Crée une écriture DRAFT — header `Idempotency-Key` obligatoire | 422 `IDEMPOTENCY_KEY_REQUIRED`/`UNBALANCED_ENTRY`/`ACCOUNT_NOT_FOUND`/`JOURNAL_NOT_FOUND`/`PERIOD_NOT_FOUND`, 409 `PERIOD_LOCKED`/`FISCAL_YEAR_CLOSED` |
| GET | `/api/v1/companies/{companyId}/accounting-engine/journal-entries` | Liste toutes les écritures (⚠️ non paginé — historique) | — |
| GET | `/api/v1/companies/{companyId}/accounting-engine/journal-entries/paged?page=&size=` | Liste paginée des écritures (audit M8) — `size` max 200 | — |
| GET | `/api/v1/companies/{companyId}/accounting-engine/journal-entries/search?from=&to=&journalCode=&sourceModule=&status=&page=&size=` | Recherche filtrée + paginée des écritures (suite 3). Tous les filtres optionnels et combinés par AND. Si `from`/`to` absents → résout l'exercice par défaut via `resolveFiscalYear`. Recommandé pour le mobile. `size` max 200. | — |
| POST | `/api/v1/companies/{companyId}/accounting-engine/journal-entries/{entryId}/post` | Poste une écriture — ACCOUNTANT | 403 `INSUFFICIENT_ROLE`, 404, 409 `ENTRY_NOT_DRAFT`/`PERIOD_LOCKED`/`FISCAL_YEAR_CLOSED`, 422 `ENTRY_TOO_FEW_LINES`/`UNBALANCED_ENTRY`/`ACCOUNT_INACTIVE`/`ANALYTICAL_TAG_REQUIRED` |
| POST | `/api/v1/companies/{companyId}/accounting-engine/journal-entries/{entryId}/reverse?reason=` | Contre-passe une écriture POSTED — ADMIN | 403 `INSUFFICIENT_ROLE`, 404, 409 `ENTRY_NOT_POSTED`/`reversal-...` déjà utilisé |
| GET | `/api/v1/companies/{companyId}/accounting-engine/ledger?accountId=&from=&to=&fiscalYearId=` | Grand livre filtré. `accountId` requis. `from`/`to` **optionnels** depuis la suite 4. `?fiscalYearId=` optionnel (prévalence sur `from`/`to` — utilise les bornes start/end de l'exercice résolu). Si aucun filtre → résolution par défaut via `resolveFiscalYear`. ⚠️ non paginé. | — |
| GET | `/api/v1/companies/{companyId}/accounting-engine/trial-balance?from=&to=&fiscalYearId=` | Balance générale. `from`/`to` et `fiscalYearId` tous optionnels. Si `fiscalYearId` fourni → utilise les bornes de l'exercice (prévalence sur `from`/`to`). Si aucun filtre → résolution par défaut via `resolveFiscalYear`. ⚠️ non paginé. | — |

> **Stabilisation 2026-07-25 (suite 4)** — `from`/`to` sur `GET /ledger` sont devenus
> **optionnels** (étaient requis). Si le mobile continue de les passer explicitement, le
> comportement est inchangé. Si le mobile omet tous les filtres, le service résout
> l'exercice par défaut (OPEN contenant aujourd'hui, sinon dernier OPEN).

## resolveFiscalYear — contrat centralisé (suite 4, §2 Option A)

La résolution de l'exercice fiscal à utiliser pour les endpoints de données est désormais
**centralisée** dans `AccountingEngineService.resolveFiscalYear(companyId, fiscalYearId)`.
C'est le **§2 Option A** du prompt de stabilisation : **résolution par requête, sans état
partagé**. Aucune colonne shared-state n'est lue par les endpoints de données — la colonne
`active_fiscal_year_id` (V32) est conservée uniquement pour rétro-compatibilité avec les
endpoints dépréciés `POST /fiscal-years/{id}/activate` et `GET /fiscal-years/active`.

### Algorithme de résolution (ordre strict)

1. **Si `fiscalYearId` est fourni** (paramètre explicite de l'endpoint), l'utiliser après
   vérification d'appartenance à l'entreprise. Si l'exercice n'existe pas ou n'appartient
   pas à l'entreprise → retourne `Optional.empty()`.
2. **Sinon**, chercher l'exercice **`OPEN`** dont la plage `[startDate, endDate]` contient
   la date du jour.
3. **Sinon**, prendre le **dernier exercice `OPEN`** (le plus récent, par `startDate`).
4. **Sinon** (aucun exercice `OPEN`), retourner `Optional.empty()`.

### Pourquoi l'Option A plutôt que l'Option B (colonne shared-state)

L'Option B (colonne `active_fiscal_year_id` mutable via `POST /activate`) a été écartée car :

- **Concurrence** : deux utilisateurs qui switchent d'exercice en parallèle se marchent
  dessus — le dernier `POST /activate` gagne, et l'autre utilisateur voit ses listes
  filtrer sur le mauvais exercice sans préavis.
- **Sessions HTTP stateless** : la résolution per-request épouse le modèle Spring
  (chaque requête est indépendante), pas besoin de synchroniser un état partagé.
- **Simplicité côté mobile** : un sélecteur d'exercice dans l'UI qui transmet
  `?fiscalYearId=` est plus prévisible que demuter un état serveur.
- **Pas de migration de données** : on n'a pas besoin de backfiller `active_fiscal_year_id`
  pour les sociétés existantes (la colonne V32 reste nullable et peut être `NULL` pour
  toutes les entreprises — les endpoints de données n'en ont pas besoin).

### Endpoints consommateurs du paramètre `?fiscalYearId=`

| Endpoint | Comportement si `fiscalYearId` absent | Comportement si `fiscalYearId` présent |
|---|---|---|
| `GET /trial-balance` | Si `from`/`to` présents → filtrer par dates. Sinon → résolution par défaut. Si aucun exercice → `404 NO_FISCAL_YEAR`. | Utilise `startDate`/`endDate` de l'exercice comme plage. Prévalence sur `from`/`to`. |
| `GET /ledger` | Si `from`/`to` présents → filtrer par dates. Sinon → résolution par défaut (bornes passées au service `getLedger`). | Idem — prévalence sur `from`/`to`. |
| `GET /purchase-invoices` | Retourne toutes les factures (comportement historique). | Filtre par `issueDate ∈ [startDate, endDate]` de l'exercice. |
| `GET /expense-reports` | Retourne toutes les notes (comportement historique). | Filtre par `expenseDate ∈ [startDate, endDate]` de l'exercice. |
| `GET /journal-entries/search` | Si `from`/`to` présents → filtrer. Sinon → résolution par défaut (en place depuis la suite 3). | Non supporté (utilise `from`/`to` uniquement). |

### Dépréciations (suite 4)

Les méthodes suivantes de `AccountingEngineService` sont **dépréciées** (`@Deprecated`)
mais conservées pour rétro-compatibilité avec les endpoints dépréciés. Ne plus appeler
directement depuis le code applicatif — préférer `resolveFiscalYear(companyId, fiscalYearId)`.

| Méthode | Statut | Raison du remplacement |
|---|---|---|
| `getActiveFiscalYear(companyId)` | **Dépréciée** | Lisait la colonne shared-state `active_fiscal_year_id`. Remplacée par `resolveFiscalYear`. |
| `getActiveFiscalYearForRead(companyId)` | **Dépréciée** | Variante read-only de la précédente. Même raison. |
| `checkActiveFiscalYearWritable(companyId)` | **Dépréciée** (no-op) | Le concept de "check writable" basé sur l'exercice actif partagé est obsolète. La validation réelle se fait via `findPeriodForDate` dans `createJournalEntry` (vérifie l'exercice réel de la date d'écriture, lève `409 PERIOD_LOCKED` / `409 FISCAL_YEAR_CLOSED`). Ne fait plus rien — conservée pour rétro-compat. |
| `activateFiscalYear(companyId, fiscalYearId)` | **Dépréciée** | Mutait la colonne shared-state. Remplacée par la résolution per-request (le paramètre `?fiscalYearId=` sur chaque endpoint remplace le "set once, read everywhere"). |

### Recommandations mobile

- **Ne plus appeler** `POST /fiscal-years/{id}/activate` après création d'un exercice.
  L'exercice créé est `OPEN` par défaut et sera automatiquement résolu par les endpoints
  de données s'il contient aujourd'hui.
- **Ne plus appeler** `GET /fiscal-years/active` pour déterminer l'exercice courant. Si
  l'écran a besoin d'afficher le libellé de l'exercice courant, appeler `GET /fiscal-years`
  et filtrer côté client.
- **Préfixer les listes** (`purchase-invoices`, `expense-reports`, `trial-balance`,
  `ledger`) par un sélecteur d'exercice fiscal qui appelle `GET /fiscal-years` puis
  transmet `?fiscalYearId=` à l'endpoint de liste.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, `TenantContext`, exceptions, `ReportingClass`,
  `ApproverEmailResolverPort`, `RoleChecker`, `ApplicationEventPublisher`.
- `:chart-of-accounts` — `Account`, `AccountRepository`, `AccountBalanceGuard`
  (implémenté ici par `JournalBasedAccountBalanceGuard`).
- `:approval-workflow` — `ApprovalWorkflowService.evaluate`,
  `ApprovalDecidedEvent` (consommé via `@EventListener` — audit B2), `ApprovalRequest`,
  `ApprovalStatus`, `ApprovalActionType`.
- `:document-numbering` — `DocumentNumberingService.nextNumber(JOURNAL_ENTRY, ...)`.
- `:analytics` — `AnalyticsService.validateValue`, `findPlanById`,
  `hasTooManyActivePlans`.

### Modules qui dépendent de celui-ci

- `:financial-statements` — consomme `JournalLineRepository.findAllPostedUpToDate` /
  `findAllPostedBetweenDates` pour générer bilan et compte de résultat.
- `:reporting` — consomme `JournalLineRepository` pour le dashboard et les exports.
- `:invoicing`, `:fixed-assets`, `:inventory`, `:funds-grants` — appellent
  `AccountingEngineService.createJournalEntry` + `postJournalEntry` pour générer leurs
  écritures automatiques.
- `:bank-reconciliation`, `:third-parties` — lisent les `JournalLine` (lecture seule, pas
  d'écriture).

### Événements publiés / consommés

- **Publie** : `JournalEntryPostedEvent` (au passage à POSTED, direct ou après approbation),
  `JournalEntryReversedEvent` (à la contre-passation).
- **Consomme** : `ApprovalDecidedEvent` (de `:approval-workflow`) via `@EventListener` —
  audit B2. Si `resourceType == "JournalEntry"` et `newStatus == APPROVED`, appelle
  `postJournalEntryAfterApproval` (PENDING_APPROVAL → POSTED). Si `newStatus ∈ {REJECTED,
  CANCELLED}`, appelle `revertToDraftAfterRejection` (PENDING_APPROVAL → DRAFT). Les
  exceptions ne sont pas propagées (l'écriture reste dans son état courant et un opérateur
  doit intervenir).

## Tables / migrations Flyway

- `src/main/resources/db/migration/V7_002__accounting_engine.sql` — tables `fiscal_year`,
  `fiscal_period`, `journal`, `journal_entry`, `journal_line`,
  `journal_line_analytical_tag`. Contraintes CHECK sur `status` (4 valeurs pour
  `journal_entry`, 3 pour `fiscal_year`, 2 pour `fiscal_period`), `source_module` (6
  valeurs), `chk_jl_debit_credit` (≥ 0), `chk_jl_exclusive` (mutuellement exclusifs),
  `chk_jlat_percentage` (0 < p ≤ 100). Unique `(company_id, idempotency_key)` sur
  `journal_entry`. **Trigger DB original `check_journal_entry_balance`** (FOR EACH ROW,
  remplacé en V36).
- `src/main/resources/db/migration/V7_003__accounting_engine_trigger_post.sql` — activation
  du trigger original (V7_002) — désormais remplacé par V36.
- `src/main/resources/db/migration/V28__accounting_engine_source_module_expansion.sql` —
  élargit le CHECK `source_module` pour accepter les nouveaux modules `PURCHASING`,
  `EXPENSES`, `PAYROLL`, `FX_OPERATIONS`.
- `src/main/resources/db/migration/V36__audit_perf_indexes_and_stmt_trigger.sql` —
  **audit v4.7 §7.2 #4 + §7.3 (session 18)**. Remplace le trigger `FOR EACH ROW` par 3
  triggers `FOR EACH STATEMENT` (un par événement `INSERT`/`UPDATE`/`DELETE`) utilisant les
  transition tables `new_rows`/`old_rows`. La fonction `check_journal_entry_balance_stmt()`
  exécute un seul `SELECT SUM` par `journal_entry_id` distinct. Ajoute également les index
  composites critiques manquants : `journal_line (company_id, third_party_id)`,
  `journal_line (company_id, account_id)`, `journal_entry (company_id, entry_date) WHERE
  status='POSTED'`, `journal_entry (company_id, status, entry_date)`,
  `sales_invoice (company_id, due_date) WHERE status IN ('ISSUED','PARTIALLY_PAID')`,
  `purchase_invoice (company_id, due_date) WHERE status IN ('RECEIVED','PARTIALLY_PAID')`,
  `bank_statement_line (bank_account_id, amount, line_date) WHERE matched=FALSE`,
  `audit_log (entity_type, entity_id, occurred_at DESC)`, `audit_log (actor_user_id,
  occurred_at DESC) WHERE actor_user_id IS NOT NULL`, `audit_log (action, occurred_at DESC)
  WHERE entity_type='SecurityEvent'`.
- `src/main/resources/db/migration/V38__audit_perf_indexes_complement.sql` — **audit v4.7
  §7.3 (session 19)**. Index composites complémentaires : `tb_timesheet_entry (company_id,
  resource_user_id, entry_date)`, `account (company_id, reporting_class, active)`,
  `account (parent_id) WHERE company_id IS NOT NULL`.
- `src/main/resources/db/migration/V51__postgres_rls.sql` — **V51 (session 25) — PostgreSQL
  Row-Level Security**. Active RLS + policy `tenant_isolation` + `FORCE ROW LEVEL SECURITY`
  sur 6 tables financières à fort impact réglementaire : `journal_line`, `journal_entry`,
  `sales_invoice`, `purchase_invoice`, `third_party`, `expense_report`. La policy filtre par
  `company_id = current_setting('app.current_tenant', true)::uuid` (fail-closed si la GUC
  n'est pas posée). **Defense in depth** en plus du filtre JWT claim `TenantContextFilter`.
  Câblage côté Java : `TenantContextFilter` exécute `SET app.current_tenant = ?` sur la
  connexion JDBC au début de chaque requête HTTP. Tant que le câblage n'est pas en place,
  RLS reste "armed but inactive" (la GUC vaut NULL → policy FALSE → toutes les lignes
  filtrées). Le rôle Flyway doit disposer de `BYPASSRLS` pour que les migrations futures
  puissent `INSERT`/`UPDATE` ces tables sans être bloquées par RLS.
- `company/src/main/resources/db/migration/V32__company_active_fiscal_year.sql` — **suite 4**
  (migration du module `:company`, mais documentée ici car elle porte l'état que
  `resolveFiscalYear` remplace). Ajoute la colonne nullable `active_fiscal_year_id` (UUID)
  sur `companies`. Depuis la suite 4, **cette colonne n'est plus lue par les endpoints de
  données** (trial-balance, ledger, purchase-invoices, expense-reports, journal-entries/search
  utilisent tous `resolveFiscalYear`). Elle est conservée uniquement pour la
  rétro-compatibilité des endpoints dépréciés `POST /fiscal-years/{id}/activate` et
  `GET /fiscal-years/active` (qui mutent/lisent toujours cette colonne). Aucune nouvelle
  migration côté `:accounting-engine` — la résolution per-request ne nécessite pas de
  persistance.

### PostgreSQL Row-Level Security (V51 — defense in depth)

Le module `:accounting-engine` héberge la migration **V51__postgres_rls.sql** qui active RLS
sur 6 tables financières critiques (`journal_line`, `journal_entry`, `sales_invoice`,
`purchase_invoice`, `third_party`, `expense_report`). Voir la section « Tables / migrations
Flyway » ci-dessus pour le détail. RLS est une **deuxième ligne de défense** en plus du filtre
JWT claim `TenantContextFilter` — si un bug applicatif oumet de filtrer par `companyId`, la
policy PostgreSQL bloque quand même la fuite de données cross-tenant. Comportement
**fail-closed** : si la GUC `app.current_tenant` n'est pas posée sur la session, la policy
retourne FALSE et aucune ligne n'est visible.

## Points d'attention (hérités de l'audit)

- ⚠️ **M8 — Aucune pagination** sur `GET /journal-entries`, `GET /ledger`,
  `GET /trial-balance`. Sur une entreprise avec plusieurs exercices, la réponse peut être
  volumineuse (potentiellement des milliers de lignes). Le client mobile doit implémenter
  un cache local et une limite UI, et idéalement filtrer par plage de dates côté query
  string. **Pour `GET /journal-entries`** : préférer `GET /journal-entries/paged`
  (audit M8) ou `GET /journal-entries/search` (suite 3) qui supportent la pagination + le
  filtrage par `journalCode`/`sourceModule`/`status`/plage de dates. **Pour `GET /ledger`**
  et **`GET /trial-balance`** : aucun endpoint paginé n'existe au MVP — utiliser
  `?fiscalYearId=` ou `?from=&to=` pour limiter la période retournée.
- ⚠️ **B1 — `closeFiscalYear` corrigé** : la résolution du compte de résultat utilisait
  auparavant les codes en dur `"12"` (SYSCOHADA) / `"110"` (PCG). Sur 6 référentiels, seul
  SYSCOHADA fonctionnait ; PCG_FRANCE écrivait sur le mauvais compte "110" (report à
  nouveau) ; IFRS, PCN_HAITI, PCGR_CANADA levaient `RESULT_ACCOUNT_NOT_FOUND`. La version
  corrigée utilise `reportingClass = CAPITAUX_PROPRES` + `taxMappingCode = "FISCAL_RESULT"`
  (puis fallback sur level=1, puis n'importe quel CAPITAUX_PROPRES actif). **Non-breaking**
  côté API.
- ⚠️ **B2 — `PENDING_APPROVAL → POSTED` corrigé** : avant la correction, une écriture
  passée à `PENDING_APPROVAL` y restait bloquée à vie car aucun listener ne consommait
  `ApprovalDecidedEvent`. Désormais, `@EventListener onApprovalDecided` finalise
  automatiquement l'écriture après APPROVED. Côté mobile, un polling sur
  `GET /journal-entries/{id}` est nécessaire pour voir la transition (pas de WebSocket).
- ⚠️ **M2 — Contre-passation idempotente corrigée** : la clé d'idempotence passe de
  `"reversal-" + originalId + "-" + UUID.randomUUID()` (non-déterministe) à
  `"reversal-" + originalId` (déterministe). Un retry renvoie l'écriture existante (409
  Conflict si la clé est déjà utilisée — breaking pour un client qui s'appuyait sur
  l'ancien comportement buggy).
- ⚠️ **M3 — Trigger DB incomplet** : `check_journal_entry_balance_stmt` (V36) ne vérifie
  l'équilibre QUE sur les écritures POSTED. Une écriture peut être postée via SQL direct en
  violant l'invariant (le trigger laisse passer), mais en usage API normal la vérification
  applicative au postage empêche cela. Pas d'impact direct sur un client mobile conforme.
  **Amélioration V36 (session 18)** : le trigger est désormais `FOR EACH STATEMENT` avec
  transition tables (3 triggers séparés `INSERT`/`UPDATE`/`DELETE`). Complexité O(N) au lieu
  de O(N²) — sur une écriture de 500 lignes, 1 `SUM` au lieu de 500.
- ⚠️ **M12 — `approverEmails = List.of()` chez les modules métier** : `invoicing`,
  `fixed-assets`, `inventory` passent `approverEmails = List.of()` au postage de leurs
  écritures auto. Si une `ApprovalRule` `JOURNAL_ENTRY_POST` s'active, l'`ApprovalRequest`
  est créée sans approbateur notifié. Seul `:accounting-engine` lui-même (via le
  contrôleur `postJournalEntry`) résout correctement les emails via
  `ApproverEmailResolverPort`.
- ⚠️ **A-4 — `If-Match` collecté mais jamais utilisé** : `POST /journal-entries` accepte
  un header `If-Match` mais ne fait rien avec — pas de concurrence optimiste. Aucun ETag
  généré nulle part.
- ⚠️ **M14 — Multi-devises inactif** : les champs `amountTransactionCurrency`,
  `transactionCurrency`, `exchangeRateUsed` (default 1) existent sur `JournalLine` mais
  ne sont jamais lus ni écrits par le service. Toute écriture est en devise fonctionnelle.
- ⚠️ **Clôture d'exercice non testée** (audit 3.7) : `POST /fiscal-years/{id}/close` n'a
  aucun test d'intégration. Un bug dans `closeFiscalYear` (mauvais compte de résultat,
  mauvais signe) ne serait pas attrapé.

## Tests

Couvert par `AccountingEngineIntegrationTest` dans `:app` (~28 tests) — création d'écriture
DRAFT, postage direct, postage avec seuil (PENDING_APPROVAL), contre-passation, verrouillage
de période (refus de postage), grand livre, balance. **0 test sur `closeFiscalYear`** (audit
3.7 — écart critique).
