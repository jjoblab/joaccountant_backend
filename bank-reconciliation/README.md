# Module : bank-reconciliation

> Comptes bancaires, import de relevés CSV/OFX, rapprochement automatique et manuel des lignes.

## Rôle du module

Le module `:bank-reconciliation` permet de rapprocher les écritures comptables bancaires
avec les lignes de relevé bancaire importées. Il est **sectoriel transversal** : activé
pour les secteurs `RETAIL_COMMERCE`, `PROFESSIONAL_SERVICES`, `NGO_HUMANITARIAN` via le mapping `business_type_module` (restructuration :company §6).
Il fonctionne pour les 6 référentiels — les comptes de trésorerie sont référencés par ID.

Le module **ne génère aucune écriture comptable** — il importe des relevés et rapproche
des `JournalLine` existantes (écrites manuellement via `:accounting-engine` ou par
`:invoicing` pour les règlements). L'objectif est de vérifier que le solde du compte de
trésorerie en comptabilité correspond au solde du relevé bancaire.

## Ce qu'il fait précisément

### Entités principales

- `BankAccount` — compte bancaire rattaché à un compte de trésorerie du plan comptable.
  Champs : `treasuryAccountId` (compte `ACTIF` de trésorerie — ex. 521 SYSCOHADA), `label`,
  `accountNumber` (IBAN ou numéro de compte).
- `BankStatementImport` — import d'un relevé. Champs : `bankAccountId`, `format` (CSV/OFX),
  `filename`, `storageKey` (clé opaque dans `FileStoragePort` pour le fichier brut),
  `lineCount`, `importedAt`.
- `BankStatementLine` — ligne de relevé. Champs : `importId`, `bankAccountId`, `lineDate`,
  `amount` (positif = crédit/entrée, négatif = débit/sortie), `description`, `matched`
  (default false), `matchedJournalLineId` (null si non rapprochée), `matchedAt`.
- `BankStatementFormat` (enum) — `CSV`, `OFX`.

### Règles métier clés

1. **Parseurs COMPLETS pour CSV et OFX** dès cette phase — pas de stub. Le format CSV est
   attendu avec colonnes `date,description,amount` (séparateur configurable). Le format
   OFX respecte la spec OFX 2.x.
2. **Fichier brut conservé** via `FileStoragePort` pour audit (clé opaque `storageKey`).
3. **Rapprochement automatique** à l'import : pour chaque ligne, tentative de
   correspondance avec une `JournalLine` du compte de trésorerie par montant exact (et date
   proche ±3 jours). Si correspondance trouvée, `matched=true` et `matchedJournalLineId`
   renseigné.
4. **Correspondance floue sur libellé** — si aucune correspondance exacte, tentative de
   matching flou sur le libellé (ex. "VIREMENT CLIENT DUPONT" ↔ "Règlement Dupont"). Le
   client mobile doit exposer ces suggestions à l'utilisateur pour validation manuelle.
5. **Rapprochement manuel** — `POST /lines/{lineId}/match` avec `{journalLineId}` permet
   à l'utilisateur de forcer la correspondance.
6. **Validation manuelle obligatoire avant clôture** — les lignes non rapprochées doivent
   être traitées (rapprochées manuellement ou justifiées) avant la clôture de période. Le
   statut `ReconciliationStatus` indique le nombre de lignes non rapprochées.
7. **Montant signé** — `amount` positif = crédit (entrée d'argent), négatif = débit
   (sortie). Convention inverse de `JournalLine.debit/credit` (où débit et credit sont
   séparés et positifs).

### Cycle de vie des objets

- `BankAccount` : créé → immuable (pas de modification ni suppression via API).
- `BankStatementImport` : créé → immuable. Les `BankStatementLine` associées peuvent être
  rapprochées/dé-rapprochées mais l'import lui-même ne peut pas être annulé (pour audit).
- `BankStatementLine` : `unmatched → matched`.
  - `unmatched → matched` : automatiquement à l'import, ou manuellement via
    `POST /lines/{lineId}/match`.
  - `matched → unmatched` : via `POST /lines/{lineId}/unmatch` (audit v4.7 §3.1 Finding
    #8.6). Passe `matched=false`, `matchedJournalLineId=null`, `matchedAt=null`. Permet
    d'annuler un rapprochement erroné (manuel ou auto) sans intervention DBA.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/bank-reconciliation/accounts` | Crée un compte bancaire rattaché à un compte de trésorerie | 404 `Account`, 422 `BANK_ACCOUNT_LABEL_REQUIRED` |
| POST | `/api/v1/companies/{companyId}/bank-reconciliation/accounts/{bankAccountId}/imports` | Importe un relevé (CSV ou OFX) — parse, stocke le brut, crée les lignes, tente le rapprochement auto. Corps : `{format, filename, content}` | 404 `BankAccount`, 422 `FORMAT_INVALID`/`PARSE_ERROR` |
| POST | `/api/v1/companies/{companyId}/bank-reconciliation/lines/{lineId}/match` | Rapprochement manuel d'une ligne avec une `JournalLine`. Corps : `{journalLineId}` | 404 `BankStatementLine`/`JournalLine`, 409 `LINE_ALREADY_MATCHED` |
| POST | `/api/v1/companies/{companyId}/bank-reconciliation/lines/{lineId}/unmatch` | **audit v4.7 §3.1 Finding #8.6** — Annule le rapprochement d'une ligne de relevé (passe `matched=false`, `matchedJournalLineId=null`, `matchedAt=null`). Si un rapprochement est erroné (manuel ou auto), l'utilisateur peut l'annuler sans intervention DBA. | 404 ligne introuvable ou non rapprochée |
| GET | `/api/v1/companies/{companyId}/bank-reconciliation/accounts/{bankAccountId}/status` | Statut de rapprochement (nombre de lignes matched/unmatched, solde bank vs solde comptable) | 404 |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `FileStoragePort`,
  `ApplicationEventPublisher`.
- `:chart-of-accounts` — `Account`, `AccountRepository` (vérification du compte de
  trésorerie).
- `:accounting-engine` — `JournalLine`, `JournalLineRepository` (recherche des écritures
  à rapprocher — lecture seule).

### Modules qui dépendent de celui-ci

- `:reporting` — recense les comptes bancaires et le statut de rapprochement pour le
  dashboard.

### Événements publiés / consommés

- **Publie** : `BankStatementImportedEvent` (à chaque import).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V15_001__bank_reconciliation.sql` — tables
  `bank_account`, `bank_statement_import`, `bank_statement_line`. FK
  `bank_account.treasury_account_id → account(id)`. CHECK sur `format` (2 valeurs). Index
  sur `(company_id, bank_account_id)` et `(bank_account_id, line_date)`.

## Points d'attention (hérités de l'audit)

- ⚠️ **Aucune écriture générée** — le module ne poste pas d'écriture de régularisation
  (frais bancaires, intérêts, etc.) pour les lignes non rapprochées. L'utilisateur doit
  poster manuellement ces écritures via `:accounting-engine` avant de rapprocher les lignes
  correspondantes. Le client mobile doit informer l'utilisateur de cette étape manuelle.
- ⚠️ **Pas de pagination sur `GET /status`** — retourne le statut global, pas la liste des
  lignes. Pour consulter les lignes non rapprochées, il faut interroger la base directement
  (pas d'endpoint public). À ajouter (backlog).
- ⚠️ **Correspondance floue non configurable** — le seuil de similarité du libellé est
  codé en dur. Pour un relevé avec des libellés très différents de ceux saisis en
  comptabilité, le matching flou peut ne rien trouver. L'utilisateur doit alors
  rapprocher manuellement.
- ⚠️ **Pas de contrôle de rôle** sur les endpoints (audit B5) — un `VIEWER` peut créer un
  compte bancaire, importer un relevé, rapprocher des lignes.
- ⚠️ **IBAN non validé** — `accountNumber` est stocké tel quel, sans validation de format
  IBAN. Le client mobile doit valider le format côté UI.

## Tests

Couvert par `BankReconciliationIntegrationTest` dans `:app` (9 tests) — création de compte
bancaire, import CSV, import OFX, rapprochement automatique (montant exact), rapprochement
manuel, statut de rapprochement, lignes non rapprochées.

## Activation (restructuration :company §7)

Le module `:bank-reconciliation` est **sectoriel** : son utilisation exige que le module
`BANK_RECONCILIATION` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.BANK_RECONCILIATION)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `BANK_RECONCILIATION` (voir `V3_003__business_type.sql`).
