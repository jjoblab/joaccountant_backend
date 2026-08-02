# Module : funds-grants

> Subventions, dons, reçus et mécanisme des fonds dédiés pour le secteur ONG.

## Rôle du module

Le module `:funds-grants` gère les subventions et dons reçus par une ONG, ainsi que le
mécanisme comptable des fonds dédiés à la clôture d'exercice. Il est **sectoriel** :
activé uniquement pour le type métier `NGO_HUMANITARIAN` via le mapping `business_type_module` (restructuration :company §6). Il
fonctionne pour les 6 référentiels — les numérotations de comptes ne sont pas figées en
dur, seuls les `reportingClass` cibles sont fixes.

Le module est le **seul module métier à appeler explicitement `ApprovalWorkflowService.evaluate`**
avec un `ApprovalActionType` typé (`GRANT_DISBURSEMENT_PROPOSAL`). Les autres modules
métier (`invoicing`, `fixed-assets`, `inventory`) délèguent l'approbation au moteur
comptable via `JOURNAL_ENTRY_POST`. Cette particularité fait de `:funds-grants` le seul
module qui propose une écriture soumise à approbation explicite avant postage.

## Ce qu'il fait précisément

### Entités principales

- `Grant` — subvention/don d'un bailleur. `(companyId, code)` unique. Champs :
  `donorThirdPartyId` (bailleur `DONOR`), `code`, `label`, `totalAmount`, `currency`,
  `startDate`, `endDate`, `restrictionType` (RESTRICTED/UNRESTRICTED), `analyticalValueId`
  (valeur du plan "Fonds/Projets" qui trace les charges liées).
- `DonationReceipt` — reçu de don émis. Champs : `grantId` (nullable), `donorThirdPartyId`,
  `amount`, `receiptNumber` (généré via `:document-numbering` au moment de la création,
  type `DONATION_RECEIPT`), `receiptDate`, `description`.
- `RestrictionType` (enum) — `RESTRICTED` (affectée — déclenche les fonds dédiés à la
  clôture), `UNRESTRICTED` (non affectée).

### Règles métier clés

1. **Subvention rattachée à un bailleur `DONOR`** — validation du `ThirdPartyType` à la
   création de la `Grant`.
2. **`receiptNumber` généré à la création du reçu** — via `:document-numbering`
   (`DocumentType.DONATION_RECEIPT`). Numérotation sans trou.
3. **Pas d'écriture comptable à la création du reçu** (audit) — `createDonationReceipt`
   ne poste pas l'écriture (pas de D Banque / C Subvention). L'utilisateur doit poster
   manuellement l'écriture de réception du don via `:accounting-engine`.
4. **Mécanisme des fonds dédiés à la clôture** — pour une subvention `RESTRICTED`,
   `closeFiscalYear` :
   - Calcule `products` (total des `DonationReceipt.amount` du grant) − `charges` (somme
     des débits des `JournalLine` POSTED taguées avec `analyticalValueId` du grant, sur
     des comptes `CHARGES`).
   - Si `balance > 0` (ressource affectée non utilisée), soumet une `ApprovalRequest`
     `GRANT_DISBURSEMENT_PROPOSAL` via `:approval-workflow`.
   - Si `balance ≤ 0`, retourne un message informatif — pas de fonds dédiés à constituer.
5. **Auto-approved sans règle → pas de postage automatique** — si aucune
   `ApprovalRule GRANT_DISBURSEMENT_PROPOSAL` n'est active, `evaluate` retourne
   `autoApproved=true` MAIS le service ne poste pas l'écriture (le mécanisme des fonds
   dédiés exige une approbation explicite, même sans règle configurée). Message retourné à
   l'utilisateur : "Créer une règle `GRANT_DISBURSEMENT_PROPOSAL` pour soumettre l'écriture
   de fonds dédiés."
6. **`approverEmails = List.of()`** — même `:funds-grants` passe `List.of()` à `evaluate`
   (audit M12). Si une règle `GRANT_DISBURSEMENT_PROPOSAL` s'active, l'`ApprovalRequest`
   est créée sans approbateur notifié.
7. **Rapport bailleur** — `GET /grants/{id}/donor-report` calcule le montant reçu, les
   dépenses (par tag analytique) et le solde restant. Pour la reddition de comptes.

### Cycle de vie des objets

- `Grant` : créé → `ACTIVE` → `CLOSED` (à la clôture d'exercice si `RESTRICTED` et
  `balance ≤ 0`, ou après approbation de la proposition de fonds dédiés). Pas d'endpoint
  public de transition — opération DB.
- `DonationReceipt` : créé → immuable. Pas de modification ni suppression (numérotation
  sans trou).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/funds-grants/grants` | Liste les subventions | — |
| POST | `/api/v1/companies/{companyId}/funds-grants/grants` | Crée une subvention. Corps : `{code, label, donorThirdPartyId, totalAmount, currency, startDate, endDate?, restrictionType, analyticalValueId?}` | 404 `ThirdParty`, 409 `GRANT_CODE_EXISTS`, 422 `DONOR_TYPE_REQUIRED` |
| POST | `/api/v1/companies/{companyId}/funds-grants/donation-receipts` | Crée un reçu de don + génère le numéro. Corps : `{grantId?, donorThirdPartyId, amount, receiptDate, description?}` | 404, 422 `AMOUNT_INVALID`/`DOCUMENT_NUMBERING_CONFIG_NOT_FOUND` |
| GET | `/api/v1/companies/{companyId}/funds-grants/grants/{grantId}/donor-report` | Rapport bailleur (reçu, dépenses, solde) | 404 |
| POST | `/api/v1/companies/{companyId}/funds-grants/grants/{grantId}/close-fiscal-year` | Clôture d'exercice — fonds dédiés (RESTRICTED) | 404, 422 (pas de règle d'approbation active) |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ApplicationEventPublisher`.
- `:third-parties` — `ThirdParty`, `ThirdPartyRepository` (bailleur `DONOR`).
- `:chart-of-accounts` — `Account`, `AccountRepository`.
- `:accounting-engine` — `JournalLine`, `JournalLineRepository`,
  `JournalLineAnalyticalTagRepository` (calcul des charges par tag analytique).
- `:document-numbering` — `DocumentNumberingService.nextNumber(DONATION_RECEIPT, ...)`.
- `:approval-workflow` — `ApprovalWorkflowService.evaluate(GRANT_DISBURSEMENT_PROPOSAL, ...)`
  — **seul module métier à appeler explicitement `evaluate`**.

### Modules qui dépendent de celui-ci

- `:reporting` — rapport bailleur agrégé pour le dashboard et les exports PDF.
- `:notifications` — alerte de seuil de consommation (`getConsumptionPercentage`).

### Événements publiés / consommés

- **Publie** : `GrantCreatedEvent`.
- **Consomme** : `ApprovalDecidedEvent` (de `:approval-workflow`) — non, en réalité ce sont
  les modules qui déclenchent des écritures qui écoutent. `:funds-grants` ne consomme pas
  `ApprovalDecidedEvent` car il ne poste pas l'écriture lui-même (il délègue au moteur).

## Tables / migrations Flyway

- `src/main/resources/db/migration/V38__funds_grants.sql` — tables `fg_grant` et
  `fg_donation_receipt`. Unique `(company_id, code)` sur grant. FK
  `grant.donor_third_party_id → third_party(id)`. CHECK sur `restriction_type` (2 valeurs),
  `total_amount >= 0`, `amount >= 0`. Index sur `(company_id, grant_id)`.

## Points d'attention (hérités de l'audit)

- ⚠️ **Aucune écriture à la réception du don** — `createDonationReceipt` ne poste pas
  l'écriture (pas de D Banque / C Subvention). L'utilisateur doit poster manuellement
  l'écriture via `:accounting-engine`. Le client mobile doit informer l'utilisateur de
  cette étape manuelle — sinon le solde bancaire ne reflète pas le don reçu.
- ⚠️ **M12 — `approverEmails = List.of()`** — `:funds-grants` passe `List.of()` à
  `evaluate`, comme les autres modules. Si une règle `GRANT_DISBURSEMENT_PROPOSAL`
  s'active, l'`ApprovalRequest` est créée sans approbateur notifié. Le client mobile doit
  proposer un écran "Demandes en attente" consultable régulièrement par les ADMIN/OWNER.
- ⚠️ **M7 — Rapport bailleur sources non reconciliables** — `getDonorReport` calcule le
  `balanceRemaining` à partir de `DonationReceipt.amount` (côté bailleur) et des
  `JournalLine` (côté comptable). Si l'utilisateur a posté une écriture de don sans créer
  le `DonationReceipt` correspondant (ou inversement), le `balanceRemaining` peut être
  incohérent. À corriger en session dédiée.
- ⚠️ **M14 — Arrondis à 2 décimales** — `getConsumptionPercentage` utilise
  `divide(grant.getTotalAmount(), 2, HALF_UP)` — précision insuffisante pour les grosses
  subventions (1 000 000 HTG / 7 = 0.14 % d'erreur).
- ⚠️ **Auto-approved sans règle = pas de postage** — si l'utilisateur oublie de créer une
  règle `GRANT_DISBURSEMENT_PROPOSAL`, la clôture ne poste pas l'écriture de fonds dédiés
  et retourne un message informatif. Le client mobile doit afficher ce message
  clairement et guider vers la création de la règle.
- ⚠️ **Pas de contrôle de rôle** sur les endpoints (audit B5) — un `VIEWER` peut créer une
  subvention, un reçu de don, déclencher la clôture.

## Tests

Couvert par `FundsGrantsIntegrationTest` dans `:app` (9 tests) — création de subvention
RESTRICTED/UNRESTRICTED, reçu de don, rapport bailleur, clôture d'exercice (RESTRICTED
avec règle, RESTRICTED sans règle, UNRESTRICTED), multi-tenant.

## Activation (restructuration :company §7)

Le module `:funds-grants` est **sectoriel** : son utilisation exige que le module
`FUNDS_GRANTS` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `FUNDS_GRANTS` (voir `V8__business_type.sql`).
