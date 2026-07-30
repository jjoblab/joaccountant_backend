# Module : document-numbering

> Génération atomique, sans trou et configurable des numéros de documents (factures, écritures, reçus).

## Rôle du module

Le module `:document-numbering` est responsable de l'émission unique et continue des numéros
de documents visibles par un tiers externe (client, bailleur, administration fiscale). Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`) et
fonctionne pour les 6 référentiels comptables — le format du numéro est configurable par
l'entreprise, indépendamment du référentiel.

Le module expose des endpoints de configuration et d'aperçu, mais **l'émission effective**
d'un numéro (`DocumentNumberingService.nextNumber`) est appelée directement par les modules
consommateurs (`:accounting-engine`, `:invoicing`, `:funds-grants`) au moment précis de la
transition qui rend le document définitif — jamais à l'état brouillon.

## Ce qu'il fait précisément

### Entités principales

- `DocumentSequenceConfig` — configuration d'une séquence. Une ligne par
  `(companyId, documentType, scopeKey)`. Champs : `documentType`, `scopeKey` (ex. code
  journal `"VT"` pour les écritures de vente), `prefix`, `includeYear`, `padding` (1-12),
  `resetPolicy` (NEVER/YEARLY/MONTHLY).
- `DocumentSequenceCounter` — compteur d'émission. Une ligne active par
  `(sequenceConfigId, periodKey)`. `lastValue` est incrémenté atomiquement.
- `DocumentType` (enum) — `JOURNAL_ENTRY`, `SALES_INVOICE`, `CREDIT_NOTE`,
  `DONATION_RECEIPT`.
- `ResetPolicy` (enum) — `NEVER`, `YEARLY`, `MONTHLY`.

### Règles métier clés

1. **Atomicité** — deux créations concurrentes ne produisent jamais le même numéro.
   Implémentée via `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` PostgreSQL (testée par
   50 threads réellement parallèles, pas simulés séquentiellement).
2. **Aucune réutilisation** — un document annulé conserve son numéro. Cette règle s'applique
   côté consommateurs (invoicing, accounting-engine), pas dans ce module.
3. **Format configurable** — `{prefix}[-{year}]-{number padded}`. Ex. `FAC-2026-000143`.
4. **Aperçu non consommateur** — `previewNextNumber` ne touche jamais au compteur, ne pose
   aucun verrou. L'utilisateur n'a aucune garantie que ce sera le numéro réellement attribué
   (acceptable : l'aperçu est purement informatif).
5. **Une seule config active par `(documentType, scopeKey)`** — `SEQUENCE_CONFIG_ALREADY_EXISTS`
   409 si elle existe déjà. Pas d'édition soft (pour éviter une incohérence de format avec les
   numéros déjà émis) ; pour modifier : supprimer et recréer (avec audit).
6. **`periodKey` calculé depuis `asOfDate`** — pas depuis `now()`. Une écriture datée du
   31/12 doit prendre son numéro dans la séquence de cette date, pas dans celle du jour de
   saisie.

### Cycle de vie des objets

- `DocumentSequenceConfig` : créé → immuable (pas d'update endpoint).
- `DocumentSequenceCounter` : créé à la première émission de la période → `lastValue`
  incrémenté à chaque `nextNumber` → nouvelle ligne créée au changement de période
  (l'ancienne ligne reste pour audit).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/document-numbering/sequences` | Crée une config de séquence. Corps : `{documentType, scopeKey?, prefix, includeYear, padding, resetPolicy}` | 409 `SEQUENCE_CONFIG_ALREADY_EXISTS`, 422 `DOCUMENT_TYPE_REQUIRED`/`PREFIX_REQUIRED`/`PREFIX_TOO_LONG`/`PREFIX_INVALID`/`PADDING_INVALID`/`RESET_POLICY_REQUIRED`/`SCOPE_KEY_TOO_LONG` |
| GET | `/api/v1/companies/{companyId}/document-numbering/sequences` | Liste les configs du tenant courant | — |
| GET | `/api/v1/companies/{companyId}/document-numbering/sequences/{documentType}/next-preview?scopeKey=&asOf=` | Aperçu NON consommateur du prochain numéro | 404 `SEQUENCE_CONFIG_NOT_FOUND` |

> Il n'y a **pas** d'endpoint `consume`. La consommation effective se fait via
> `DocumentNumberingService.nextNumber(companyId, documentType, scopeKey, asOfDate)`,
> appelé directement par les modules Phase 5 (postage d'écriture), 12 (émission de facture)
> et 14 (création de reçu de don).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, `TenantContext`, exceptions (`ConflictException`,
  `NotFoundException`, `ValidationException`), `ApplicationEventPublisher`.

### Modules qui dépendent de celui-ci

- `:accounting-engine` — appelle `nextNumber(JOURNAL_ENTRY, journalCode, entryDate)` au
  postage d'une écriture pour générer la référence visible.
- `:invoicing` — appelle `nextNumber(SALES_INVOICE, "", invoiceDate)` à l'émission d'une
  facture, et `nextNumber(CREDIT_NOTE, "", creditNoteDate)` pour les avoirs.
- `:funds-grants` — appelle `nextNumber(DONATION_RECEIPT, "", receiptDate)` à la création
  d'un reçu de don.

### Événements publiés / consommés

- **Publie** : `SequenceConfigCreatedEvent` (à la création d'une config),
  `NumberIssuedEvent` (à chaque émission — porte le numéro, la valeur numérique, le
  `periodKey`, le `documentType`, le `scopeKey`).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V4_001__document_numbering.sql` — tables
  `document_sequence_config` et `document_sequence_counter`. Contrainte unique
  `(company_id, document_type, scope_key)` sur la config ; `(sequence_config_id, period_key)`
  sur le compteur. CHECK sur `document_type`, `reset_policy`, `padding` (1-12),
  `last_value >= 0`. FK `counter.sequence_config_id → config(id) ON DELETE CASCADE`.

## Points d'attention (hérités de l'audit)

- ⚠️ **`previewNextNumber` non garanti** — l'aperçu peut différer du numéro réellement émis si
  une autre émission se produit entre l'aperçu et la validation. Le client mobile doit
  afficher cet aperçu comme "indicatif" et récupérer le numéro réel dans la réponse de
  l'endpoint d'émission (ex. `POST /invoices/{id}/issue`).
- ⚠️ **Pas d'édition de config** — pour modifier un format, il faut supprimer la config
  existante (pas d'endpoint de suppression exposé en Phase 2 — opération DB manuelle) et en
  recréer une. Les numéros déjà émis restent valides mais le format change. À documenter
  côté mobile.
- ⚠️ **`scopeKey` obligatoire pour `JOURNAL_ENTRY`** — chaque journal (VT, OD, AC, BQ)
  devrait avoir sa propre séquence. Si l'utilisateur crée une seule config avec `scopeKey=""`
  pour `JOURNAL_ENTRY`, tous les journaux partageront la même séquence — ce qui n'est pas
  l'usage comptable habituel. Le client mobile doit guider l'utilisateur vers une config par
  journal.

## Tests

Couvert par `DocumentNumberingIntegrationTest` dans `:app` — test d'atomicité (50 threads
parallèles), test de reset YEARLY/MONTHLY, test d'aperçu non consommateur.
