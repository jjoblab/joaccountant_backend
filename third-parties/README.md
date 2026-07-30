# Module : third-parties

> Tiers (clients, fournisseurs, donateurs, salariés), comptes dédiés auto-générés, lettrage et balance âgée.

## Rôle du module

Le module `:third-parties` gère les tiers de l'entreprise et leur lettrage comptable. Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`) et
fonctionne pour les 6 référentiels comptables — les types de tiers et le mécanisme de
lettrage sont référentiel-agnostiques. Le type `DONOR` est spécifiquement utile au secteur
ONG (en complément de `:funds-grants`).

Le module ne **génère aucune écriture** — il crée des tiers (avec compte dédié
auto-généré sous le compte collectif) et lettre des `JournalLine` existantes. Les écritures
sont créées par `:accounting-engine` (saisie manuelle), `:invoicing` (factures clients) ou
`:bank-reconciliation` (règlements).

## Ce qu'il fait précisément

### Entités principales

- `ThirdParty` — tiers rattaché à un compte collectif. Si le compte collectif a
  `isCollective = true`, un compte dédié de niveau 4 est auto-généré sous le collectif
  (ex. `411000001` pour le client "Boutique Pétion-Ville" sous `411000`). Champs : `type`
  (CLIENT/SUPPLIER/DONOR/EMPLOYEE/OTHER), `name`, `collectiveAccountId`,
  `dedicatedAccountId`, `active`, `email`, `address`. **V42 (audit v4.7 §4.2)** :
  `siret` (VARCHAR 14, 14 chiffres), `vatNumber` (VARCHAR 20, pattern `[A-Z]{2}[0-9A-Z]+`),
  `nif` (VARCHAR 30 — numéro d'identification fiscale). Ces champs alimentent les mentions
  légales des factures (CGI art. 289), le Factur-X (`BuyerTradeParty` / `SellerTradeParty`)
  et l'autoliquidation V45 (détection automatique d'un VAT number des deux côtés).
- `LettrageMatch` — lettrage d'un ensemble de `JournalLine` d'un même tiers. Champs :
  `thirdPartyId`, `matchCode` (séquentiel A, B, C, ...), `status` (FULL/PARTIAL),
  `matchedAmount`, `journalLineIds` (JSONB).
- `ThirdPartyType` (enum) — `CLIENT`, `SUPPLIER`, `DONOR`, `EMPLOYEE`, `OTHER`.
- `LettrageStatus` (enum) — `FULL` (somme débit = somme crédit), `PARTIAL` (sinon).

### Règles métier clés

1. **Compte dédié auto-généré** — si le `collectiveAccountId` a `isCollective = true`, la
   création du tiers génère un sous-compte de niveau 4 sous le collectif, avec un code
   incrémental (ex. `411000` → `411000001`, `411000002`, ...).
2. **`ThirdParty.dedicatedAccountId` unique par entreprise** — contrainte DB
   `uc_tp_company_dedicated_account`. Un compte dédié ne peut être partagé par deux tiers.
3. **Lettrage FULL si équilibre** — somme débit = somme crédit → `status = FULL`. Sinon
   `PARTIAL` (lettrage partiel, useful pour rapprocher une facture avec un règlement
   partiel).
4. **Code de lettrage séquentiel** — A, B, C, ... attribué automatiquement. Le code est
   unique par entreprise (mais pas par tiers — un lettrage peut théoriquement couvrir
   plusieurs tiers, bien que ce ne soit pas l'usage).
5. **Ligne déjà lettrée → 422** — une `JournalLine` ne peut pas être lettrée deux fois.
   Dé-lettrer d'abord via `DELETE /lettrage/{lettrageId}`.
6. **Dé-lettrage autorisé** — `DELETE /lettrage/{lettrageId}` supprime le lettrage et
   libère les lignes (elles redeviennent non lettrées). Utile pour corriger une erreur.
7. **Suggestions de lettrage** — `GET /{thirdPartyId}/suggested-matches` propose des paires
   de lignes à lettrer basées sur montant identique et date proche (±7 jours). L'utilisateur
   valide manuellement via `POST /lettrage`.
8. **Balance âgée** — `GET /{thirdPartyId}/aged-balance` répartit le solde non lettré par
   tranche d'âge : 0-30, 31-60, 61-90, 90+ jours (calculé depuis `entryDate` par rapport à
   `asOf`).

### Cycle de vie des objets

- `ThirdParty` : `ACTIVE` → `INACTIVE` (désactivation, pas d'endpoint public — opération DB
  manuelle). Pas de suppression physique.
- `LettrageMatch` : créé (`FULL` ou `PARTIAL`) → supprimé (dé-lettrage). Pas de transition
  `PARTIAL → FULL` automatique — si l'utilisateur ajoute des lignes à un lettrage partiel,
  il faut supprimer et recréer.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/third-parties?type=&page=&size=` | **Paginé (Finding #3)** — retourne une `Page<ThirdPartyResponse>`. `?type=` filtre par type (CLIENT/SUPPLIER/DONOR/EMPLOYEE/OTHER). `?page=0&size=20` (défaut, `size` capped à 200). La réponse porte désormais `siret`/`vatNumber`/`nif` (V42). | — |
| GET | `/api/v1/companies/{companyId}/third-parties/{thirdPartyId}` | Récupère un tiers par ID (deep-linking mobile). | 404 |
| POST | `/api/v1/companies/{companyId}/third-parties` | Crée un tiers + auto-génère le compte dédié si collectif. Corps : `{type, name, collectiveAccountId, email?, address?, siret?, vatNumber?, nif?}` (champs V42 optionnels). | 422 `THIRD_PARTY_NAME_REQUIRED`/`COLLECTIVE_ACCOUNT_NOT_FOUND`/`COLLECTIVE_ACCOUNT_NOT_COLLECTIVE`, 409 `DEDICATED_ACCOUNT_CODE_COLLISION` |
| GET | `/api/v1/companies/{companyId}/third-parties/{thirdPartyId}/statement?from=&to=` | Relevé de compte d'un tiers (écritures POSTED + solde lettré/non lettré) | 404 `ThirdParty` |
| POST | `/api/v1/companies/{companyId}/third-parties/lettrage` | Lettre un ensemble de `JournalLine` d'un même tiers. Rôle `BOOKKEEPER`. | 422 `LINE_ALREADY_LETTERED`/`WRONG_THIRD_PARTY`/`NO_LINES` |
| DELETE | `/api/v1/companies/{companyId}/third-parties/lettrage/{lettrageId}` | Supprime un lettrage (dé-lettrage). **Rôle ADMIN désormais requis** (audit v4.7 §6.1 — le dé-lettrage est une opération sensible qui peut masquer une fraude). | 404 `LettrageMatch`, 403 `INSUFFICIENT_ROLE` |
| GET | `/api/v1/companies/{companyId}/third-parties/{thirdPartyId}/suggested-matches` | Suggère des paires de lignes à lettrer | 404 `ThirdParty` |
| GET | `/api/v1/companies/{companyId}/third-parties/{thirdPartyId}/aged-balance?asOf=` | Balance âgée par tranche (0-30, 31-60, 61-90, 90+) | 404 `ThirdParty` |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ApplicationEventPublisher`.
- `:chart-of-accounts` — `Account`, `AccountRepository` (pour créer le compte dédié sous le
  collectif, et vérifier que le collectif a `isCollective = true`).
- `:accounting-engine` — `JournalLine`, `JournalLineRepository` (pour le relevé de compte,
  le lettrage et la balance âgée — lecture seule, pas d'écriture).

### Modules qui dépendent de celui-ci

- `:invoicing` — référence un `ThirdParty` (client) sur les factures.
- `:funds-grants` — référence un `ThirdParty` (donateur) sur les reçus de dons.
- `:bank-reconciliation` — référence un `ThirdParty` (fournisseur ou client) sur les lignes
  bancaires pour faciliter le rapprochement.
- `:reporting` — utilise les tiers pour le rapport bailleur (ventilation par donateur).

### Événements publiés / consommés

- **Publie** : `ThirdPartyCreatedEvent`, `LettrageCreatedEvent`.
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V9_001__third_parties.sql` — tables `third_party` et
  `lettrage_match`. Unique `(company_id, dedicated_account_id)` sur `third_party`.
  `lettrage_match.journal_line_ids` stocké en JSONB. Index sur `company_id` et
  `third_party_id`.
- `src/main/resources/db/migration/V42__company_thirdparty_legal_fields.sql` — **V42 — audit
  v4.7 §4.2 (session 7)**. Migration partagée avec le module `:company`. Ajoute sur
  `third_party` : `siret` (VARCHAR 14, pattern 14 chiffres), `vat_number` (VARCHAR 20,
  pattern `[A-Z]{2}[0-9A-Z]+`), `nif` (VARCHAR 30, NIF), `address` (TEXT). Ces champs
  alimentent le Factur-X (`BuyerTradeParty` / `SellerTradeParty`) et la détection
  d'autoliquidation V45 (VAT number des deux côtés → `isReverseCharge=true`).

## Points d'attention (hérités de l'audit)

- ⚠️ **Balance âgée non implémentée côté reporting** (audit M5) — l'endpoint
  `GET /{thirdPartyId}/aged-balance` existe dans `:third-parties`, mais le dashboard du
  `:reporting` ne ventile pas `totalReceivables` et `totalPayables` par tranche d'âge. Le
  client mobile doit appeler l'endpoint par tiers individuellement pour obtenir la balance
  âgée — pas de vue agrégée.
- ⚠️ **Aucune pagination sur `GET /third-parties`** — **CORRIGÉ (Finding #3)** : `GET /third-parties`
  retourne désormais une `Page<ThirdPartyResponse>` avec `?type=&page=&size=` (défaut 0/20,
  size capped à 200).
- ⚠️ **Pas de contrôle de rôle** sur les endpoints mutatifs (audit B5) — un `VIEWER` peut
  techniquement créer un tiers, lettrer, dé-lettrer. À corriger.
- ⚠️ **Code de lettrage global par entreprise** — le code (A, B, C, ...) est séquentiel par
  entreprise, pas par tiers. Si deux utilisateurs lettrent en parallèle, ils peuvent
  théoriquement obtenir le même code (race condition non testée). En pratique, le lettrage
  est rarement concurrent.
- ⚠️ **Compte dédié jamais désactivé** — quand un tiers est désactivé, son compte dédié
  reste `active = true`. Le client mobile doit filtrer les tiers inactifs côté UI.

## Tests

Couvert par `ThirdPartiesIntegrationTest` dans `:app` — création de tiers (client,
fournisseur, donateur), génération du compte dédié, lettrage FULL/PARTIAL, dé-lettrage,
relevé de compte, suggestions de lettrage, balance âgée.
