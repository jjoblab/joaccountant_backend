# Module : fixed-assets

> Immobilisations, échéanciers d'amortissement (linéaire / dégressif) et cession avec génération d'écritures.

## Rôle du module

Le module `:fixed-assets` gère le cycle de vie des immobilisations : création avec
génération automatique de l'échéancier d'amortissement, postage période par période, et
cession avec calcul de plus/moins-value. Il est **sectoriel** : activé pour les secteurs
`RETAIL_COMMERCE`, `PROFESSIONAL_SERVICES`, `NGO_HUMANITARIAN` via le mapping `business_type_module` (restructuration :company §6). Il fonctionne
pour les 6 référentiels — les comptes d'actif/charge/amortissement sont référencés par ID
et validés sémantiquement via `ReportingClass` (audit M9).

Le module **génère des écritures comptables** via `:accounting-engine` :
- `postPeriodDepreciation` : Débit charge d'amortissement / Crédit amortissement cumulé.
- `dispose` : sortie actif + reprise amortissement + prix de cession + plus/moins-value.
- (manquant — audit M10) : `generateAcquisitionEntry` — l'acquisition n'est PAS
  comptabilisée automatiquement à la création de l'asset.

## Ce qu'il fait précisément

### Entités principales

- `Asset` — immobilisation. Champs : `label`, `acquisitionDate`, `acquisitionCost`,
  `usefulLifeMonths` (durée de vie en mois), `residualValue`, `depreciationMethod`
  (STRAIGHT_LINE / DECLINING_BALANCE), `assetAccountId` (compte d'actif — ex. 244),
  `depreciationExpenseAccountId` (compte de charge — ex. 631),
  `accumulatedDepreciationAccountId` (compte d'amortissement cumulé — ex. 2844), `status`
  (ACTIVE/DISPOSED), `disposalDate`, `disposalAmount`, `gainOrLoss`.
  **V58 (audit v4.7 §3.2 — IAS 36)** : `impairmentAmount` (dépréciation IAS 36 cumulée,
  défaut 0), `impairmentExpenseAccountId` (compte de CHARGES pour la dépréciation — ex. 6816,
  fallback `depreciationExpenseAccountId`), `accumulatedImpairmentAccountId` (compte d'ACTIF
  pour la dépréciation cumulée — ex. 291, fallback `accumulatedDepreciationAccountId`).
- `AssetComponent` — **V58 (IAS 16)** : composant d'une immobilisation (ex. structure,
  toiture, installations techniques). Champs : `assetId`, `label`, `componentCost`,
  `usefulLifeMonths`, `depreciationMethod`. Chaque composant a sa propre durée de vie et sa
  propre méthode d'amortissement. L'échéancier de l'asset parent est regénéré par composant
  après ajout.
- `DepreciationScheduleLine` — ligne d'échéancier d'amortissement. Champs : `assetId`,
  `periodDate`, `amount`, `cumulativeAmount`, `periodId` (résolu au postage), `posted`
  (boolean). Une ligne par mois pour `usefulLifeMonths` mois. **V58** : `componentId`
  (nullable — null si l'amortissement est calculé globalement sur l'asset, sinon référence
  l'`AssetComponent` dont la ligne amortit la part).
- `AssetStatus` (enum) — `ACTIVE`, `DISPOSED`.
- `DepreciationMethod` (enum) — `STRAIGHT_LINE`, `DECLINING_BALANCE`.

### Règles métier clés

1. **Échéancier généré à la création** — une ligne par mois pour `usefulLifeMonths` mois,
  avec `amount` et `cumulativeAmount` calculés selon la méthode.
2. **STRAIGHT_LINE** : montant constant = `(acquisitionCost − residualValue) /
  usefulLifeMonths`. La dernière ligne ajuste pour absorber l'arrondi.
3. **DECLINING_BALANCE** : taux dégressif = `coefficient × (12 / usefulLifeMonths)` appliqué
   au solde net comptable restant. **Coefficient variable selon la durée** (Vague 3 item
   3.3) : 1.25 si 3-4 ans (36-48 mois), 1.75 si 5-6 ans (60-72 mois), 2.25 si > 6 ans
   (> 72 mois). En dessous de 3 ans, coefficient minimum 1.25.
4. **`residualValue ≤ acquisitionCost`** — 422 `RESIDUAL_TOO_HIGH` sinon.
5. **Postage période par période** — `POST /{assetId}/post-period-depreciation?periodId=`
   génère une écriture `sourceModule = FIXED_ASSETS` (Débit charge / Crédit amortissement
   cumulé). Une seule période à la fois, jamais tout l'échéancier d'un coup.
6. **Période déjà postée → 409** `SCHEDULE_LINE_ALREADY_POSTED`.
7. **Asset DISPOSED → 409** `ASSET_DISPOSED` sur `postPeriodDepreciation`.
8. **Cession immuable** — `POST /{assetId}/dispose` passe l'asset à `DISPOSED` (ne peut
   plus être amorti ni cédé à nouveau). 409 `ASSET_ALREADY_DISPOSED` si déjà cédé.
9. **Validation sémantique des comptes** (audit M9) — `validateAccount` vérifie désormais
   la `reportingClass` attendue pour chaque rôle :
   - `assetAccountId` → doit être `ACTIF`
   - `depreciationExpenseAccountId` → doit être `CHARGES`
   - `accumulatedDepreciationAccountId` → doit être `ACTIF`
   422 `ACCOUNT_WRONG_REPORTING_CLASS` si un compte n'a pas la classe attendue.
10. **Idempotence synthétique** — `postPeriodDepreciation` utilise
    `idempotencyKey = "fixed-assets-depreciation-" + scheduleLineId` ; `dispose` utilise
    `"fixed-assets-disposal-" + assetId`. Un retry renvoie l'écriture existante ou 409.
11. **`approverEmails = List.of()`** (audit M12) — les écritures de postage et de cession
    sont postées via `accountingEngineService.postJournalEntry(companyId, entryId,
    List.of())`. Si une `ApprovalRule JOURNAL_ENTRY_POST` s'active, aucun approbateur n'est
    notifié.

### Cycle de vie des objets

- `Asset` : `ACTIVE → DISPOSED`
  - `ACTIVE → DISPOSED` : via `POST /fixed-assets/{assetId}/dispose`. Calcule
    plus/moins-value = `disposalAmount − (acquisitionCost − cumulativeDepreciation)`.
    Génère une écriture de cession (sortie actif + reprise amortissement + prix de
    cession + plus/moins-value).
- `DepreciationScheduleLine` : `unposted → posted`. Transition via
  `POST /fixed-assets/{assetId}/post-period-depreciation?periodId=`. Une ligne postée ne
  peut plus être re-postée (409).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/fixed-assets` | Liste les immobilisations (⚠️ non paginé) | — |
| POST | `/api/v1/companies/{companyId}/fixed-assets` | Crée une immobilisation + génère l'échéancier. Corps : `{label, acquisitionDate, acquisitionCost, usefulLifeMonths, residualValue, depreciationMethod, assetAccountId, depreciationExpenseAccountId, accumulatedDepreciationAccountId}` | 422 `USEFUL_LIFE_INVALID`/`RESIDUAL_TOO_HIGH`/`ACCOUNT_NOT_FOUND`/`ACCOUNT_INACTIVE`/`ACCOUNT_WRONG_REPORTING_CLASS` |
| GET | `/api/v1/companies/{companyId}/fixed-assets/{assetId}` | Récupère une immobilisation (porte désormais `impairmentAmount`, `impairmentExpenseAccountId`, `accumulatedImpairmentAccountId`, `components` — V58) | 404 `Asset` |
| GET | `/api/v1/companies/{companyId}/fixed-assets/{assetId}/schedule` | Échéancier d'amortissement (lignes postées et non postées) — chaque ligne porte désormais `componentId` (V58) | 404 |
| POST | `/api/v1/companies/{companyId}/fixed-assets/{assetId}/post-period-depreciation?periodId=` | Poste l'amortissement d'une période — génère écriture | 404, 409 `ASSET_DISPOSED`/`SCHEDULE_LINE_ALREADY_POSTED`, 422 `JOURNAL_OD_NOT_FOUND`/`PERIOD_NOT_FOUND` |
| POST | `/api/v1/companies/{companyId}/fixed-assets/{assetId}/dispose` | Cède une immobilisation — génère écriture de cession. Corps : `{disposalDate, disposalAmount}` | 404, 409 `ASSET_ALREADY_DISPOSED`, 422 `JOURNAL_OD_NOT_FOUND`/`PERIOD_NOT_FOUND` |
| GET | `/api/v1/companies/{companyId}/fixed-assets/{assetId}/components` | **V58 — IAS 16** — Liste les composants d'une immobilisation (structure, toiture, installations...). | 404 |
| POST | `/api/v1/companies/{companyId}/fixed-assets/{assetId}/components` | **V58 — IAS 16** — Ajoute un composant à un asset existant et regénère l'échéancier par composant. Refusé (409) si l'échéancier a déjà des lignes postées. | 404, 409 `SCHEDULE_ALREADY_POSTED` |
| POST | `/api/v1/companies/{companyId}/fixed-assets/{assetId}/test-impairment?recoverableAmount=` | **V58 — IAS 36** — Test de dépréciation : compare la VNC (coût − amortissement cumulé − dépréciation antérieure) avec le montant recouvrable fourni. Si VNC > recouvrable, enregistre une dépréciation (D 6816 / C 291) et retourne le montant + l'écriture générée. Retourne `ImpairmentTestResult {assetId, bookValue, recoverableAmount, impairmentAmount, journalEntryId}`. | 404, 409 `ASSET_DISPOSED` |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ReportingClass`, `ApplicationEventPublisher`.
- `:chart-of-accounts` — `Account`, `AccountRepository` (validation sémantique des comptes
  via `reportingClass` — audit M9).
- `:accounting-engine` — `AccountingEngineService.createJournalEntry` +
  `postJournalEntry`, `FiscalPeriod`, `FiscalPeriodRepository`, `Journal`,
  `JournalRepository` (recherche du journal `"OD"`).
- `:document-numbering` —间接ement via `:accounting-engine` au postage.

### Modules qui dépendent de celui-ci

- `:app` — tests d'intégration `FixedAssetsIntegrationTest`.
- `:reporting` — recense les immobilisations cédées pour le calcul des plus/moins-values
  de l'exercice (tableau des mutations d'immobilisations).

### Événements publiés / consommés

- **Publie** : `AssetCreatedEvent`, `DepreciationPostedEvent` (à chaque postage de
  période), `AssetDisposedEvent` (à la cession).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V20__fixed_assets.sql` — tables `asset` et
  `depreciation_schedule_line`. CHECK sur `depreciation_method` (2 valeurs), `status`
  (2 valeurs). `acquisition_cost > 0`, `useful_life_months >= 1`. Index sur
  `(company_id, status)` et `(asset_id, period_date)`.
- `src/main/resources/db/migration/V21__fixed_assets_disposal_accounts.sql` — ajoute les
  colonnes `disposal_gain_account_id` (PRODUITS) et `disposal_loss_account_id` (CHARGES) sur
  `asset` (audit M11).
- `src/main/resources/db/migration/V58__fixed_assets_components_and_impairment.sql` —
  **V58 — audit v4.7 §3.2 (Finding #11 — IAS 16/36)**. Crée la table `asset_component`
  (composants d'immobilisation — structure, toiture, installations...). Ajoute sur `asset` :
  `impairment_amount` (NUMERIC 19,4, défaut 0), `impairment_expense_account_id` (compte de
  CHARGES 6816, nullable — fallback `depreciation_expense_account_id`),
  `accumulated_impairment_account_id` (compte d'ACTIF 291, nullable — fallback
  `accumulated_depreciation_account_id`). Ajoute sur `depreciation_schedule_line` :
  `component_id` (nullable — null si amortissement global, sinon référence le composant).

## Points d'attention (hérités de l'audit)

- ⚠️ **M9 — `validateAccount` enrichi** : la version initiale ne validait que
  l'existence/tenant/activité du compte. Désormais, la `reportingClass` attendue est
  vérifiée pour chaque rôle (`assetAccountId` → ACTIF, `depreciationExpenseAccountId` →
  CHARGES, `accumulatedDepreciationAccountId` → ACTIF). **Non-breaking pour les clients
  conformes** ; breaking pour un client qui assignait un compte sémantiquement incohérent
  (reçoit désormais 422 `ACCOUNT_WRONG_REPORTING_CLASS`).
- ⚠️ **M10 — `generateAcquisitionEntry` absent** : l'acquisition d'immobilisation n'est PAS
  comptabilisée automatiquement à la création de l'asset. L'utilisateur doit poster
  manuellement l'écriture d'acquisition (D Actif / C Fournisseur ou C Banque) via
  `:accounting-engine`. Le client mobile doit afficher un avertissement à la création
  d'une immobilisation : "Pensez à comptabiliser l'acquisition manuellement".
- ⚠️ **M11 — Plus-value de cession sur `depreciationExpenseAccountId`** : la plus/moins-value
  est créditée/débitée sur le compte de charge d'amortissement au lieu d'un compte de
  produit/charge dédié. Cela sous-évalue les charges nettes (la charge d'amortissement est
  réduite artificiellement par la plus-value) et ne fait pas apparaître la plus-value dans
  les produits. À corriger en session dédiée. Le client mobile doit informer l'utilisateur
  que la plus-value de cession n'est pas présentée correctement dans le compte de résultat.
- ⚠️ **M14 — Arrondis à 4 décimales codés en dur** — l'échéancier utilise
  `setScale(4, HALF_UP)` pour `monthlyAmount` et `divide(..., 6, HALF_UP)` pour le taux
  dégressif. Pour une devise 0-décimales (XOF/XAF/JPY), les montants sont stockés avec 4
  décimales. Le client mobile doit adapter l'affichage (arrondir à l'entier le plus proche
  pour XOF).
- ⚠️ **Aucune pagination sur `GET /fixed-assets` et `GET /{id}/schedule`** — retourne tous
  les assets / toutes les lignes. Pour une entreprise avec beaucoup d'immobilisations, le
  client mobile doit implémenter un filtre côté UI.
- ⚠️ **Code journal `"OD"` en dur** (lignes 320, 403 de `FixedAssetsService`) — l'écriture
  de postage et de cession cherche le journal de code `"OD"`. Si l'entreprise n'a pas créé
  ce journal (cas PCGR_CANADA où le concept "opérations diverses" n'existe pas sous ce
  code), lève `JOURNAL_OD_NOT_FOUND`. Le client mobile doit guider l'utilisateur vers la
  création d'un journal `"OD"` après initialisation du plan comptable.

## Tests

Couvert par `FixedAssetsIntegrationTest` dans `:app` — création d'asset STRAIGHT_LINE et
DECLINING_BALANCE, génération de l'échéancier, postage période par période, cession avec
plus-value et moins-value. Pas de test sur le coefficient variable 1.25/1.75/2.25 (audit
3.6).

## Activation (restructuration :company §7)

Le module `:fixed-assets` est **sectoriel** : son utilisation exige que le module
`FIXED_ASSETS` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `FIXED_ASSETS` (voir `V8__business_type.sql`).
