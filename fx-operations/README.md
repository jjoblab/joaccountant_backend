# Module :fx-operations

> Opérations en devises étrangères — achat/vente de devises, réévaluation de fin de période,
> gain/perte de change automatique.

## Rôle du module

Le module `:fx-operations` permet à une entreprise de gérer ses opérations en devises
étrangères. Il est **sectoriel** (stabilisation 2026-07-25 suite 4 §3) : son utilisation
exige que le module `FX_OPERATIONS` soit activé pour la société (vérifié en tête de chaque
endpoint via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.FX_OPERATIONS)`). Le
module est auto-activé à la complétion du wizard pour 6 types métier (V33 — voir ci-dessous) ;
pour les autres types métier, l'activation se fait via le feature toggle
`POST /api/v1/companies/{companyId}/modules/FX_OPERATIONS/activate`. Il réutilise
`ExchangeRateService` du module `:core` pour la conversion des montants.

Trois types d'opérations :
- **BUY** — achat de devise étrangère (ex. HTG → USD)
- **SELL** — vente de devise étrangère (ex. USD → HTG)
- **REVALUATION** — réévaluation de fin de période au taux de clôture

Chaque opération génère automatiquement une écriture comptable avec gain/perte de change.

## Ce qu'il fait précisément

### Entités principales

- `FxOperation` — opération de change. Champs : `type` (BUY/SELL/REVALUATION),
  `fromCurrency`, `toCurrency`, `fromAmount`, `toAmount`, `rate`,
  `fromAmountFunctional`, `toAmountFunctional`, `fxGainLoss`, `operationDate`,
  `description`, `journalEntryId`, `status` (POSTED/REVERSED).

### Règles métier clés

1. **Devises distinctes** — `fromCurrency` et `toCurrency` doivent être différents.
2. **Cohérence du taux** — `toAmount ≈ fromAmount × rate` (tolérance 0.01).
3. **Conversion en devise fonctionnelle** — `fromAmountFunctional` et `toAmountFunctional`
   sont calculés via `ExchangeRateService.convert()`.
4. **Gain/perte de change** — `fxGainLoss = toAmountFunctional - fromAmountFunctional`
   (BUY) ou l'inverse (SELL/REVALUATION).
5. **Écriture comptable générée** :
   - BUY : D 521 / C 521 + (C 776 si gain OU D 676 si perte)
   - SELL : D 521 / C 521 + (C 776 si gain OU D 676 si perte)
   - REVALUATION : D 521 / C 776 (gain latent) OU D 676 / C 521 (perte latente)
6. **Code journal `OD`** — opérations diverses (pas de journal FX dédié au MVP).

### Résolution des comptes

- **Compte de trésorerie** : `bankAccountId` si fourni, sinon `ACTIF + taxMappingCode="CASH"`
  (fallback SYSCOHADA `521`/`521000`).
- **Compte de gain de change** : `PRODUITS + taxMappingCode="FX_GAIN"` (fallback `776`/`776000`).
- **Compte de perte de change** : `CHARGES + taxMappingCode="FX_LOSS"` (fallback `676`/`676000`).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/fx-operations/rates` | Crée un taux de change (ADMIN). **Gate `FX_OPERATIONS`** (suite 4 §3). | 422 `FROM_CURRENCY_INVALID`/`TO_CURRENCY_INVALID`/`RATE_INVALID`, 403 `MODULE_NOT_ENABLED` |
| GET | `/api/v1/companies/{companyId}/fx-operations/convert?amount=&fromCurrency=&toCurrency=&asOfDate=` | Convertit un montant (VIEWER). **Gate `FX_OPERATIONS`** (suite 4 §3). | 422 `EXCHANGE_RATE_NOT_FOUND`, 403 `MODULE_NOT_ENABLED` |
| POST | `/api/v1/companies/{companyId}/fx-operations` | Crée une opération de change (BOOKKEEPER). **Gate `FX_OPERATIONS`** (suite 4 §3). | 422 `SAME_CURRENCY`/`INCONSISTENT_RATE`/`CASH_ACCOUNT_NOT_FOUND`/`FX_GAIN_ACCOUNT_NOT_FOUND`/`FX_LOSS_ACCOUNT_NOT_FOUND`, 403 `MODULE_NOT_ENABLED` |
| GET | `/api/v1/companies/{companyId}/fx-operations` | Liste les opérations (VIEWER). **Gate `FX_OPERATIONS`** (suite 4 §3). | 403 `MODULE_NOT_ENABLED` |
| GET | `/api/v1/companies/{companyId}/fx-operations/{id}` | Détail d'une opération (VIEWER). **Gate `FX_OPERATIONS`** (suite 4 §3). | 404 `FxOperation`, 403 `MODULE_NOT_ENABLED` |

> **Stabilisation 2026-07-25 (suite 4 §3)** — `:fx-operations` est désormais **sectoriel**.
> Les 5 endpoints ci-dessus appellent tous `moduleAccessGuard.ensureEnabled(companyId,
> ModuleCode.FX_OPERATIONS)` en tête (après le check de rôle) et retournent
> `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société. Avant cette
> stabilisation, le module était toujours-actif (always-on) — tout client mobile qui
> appelait ces endpoints sans module activé recevait 200/201 et reçoit désormais 403.

> **Note sur `?sector=` filter** : la liste des opérations de change n'est pas filtrable
> par secteur (un secteur n'est pas une donnée d'entreprise). Le paramètre `?sector=`
> mentionné dans le contrat mobile concerne `GET /api/v1/business-types?sector=SERVICE`
> (filtrage du catalogue de types métier à l'étape 4 du wizard), pas ce module.

## Activation (stabilisation :company §7 + V33)

Le module `:fx-operations` est **sectoriel** : son utilisation exige que le module
`FX_OPERATIONS` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.FX_OPERATIONS)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/modules/FX_OPERATIONS/activate` (feature toggle — ADMIN) ou via
`POST /api/v1/companies/{id}/wizard/complete` pour les sociétés dont le type métier mappe
`FX_OPERATIONS` par défaut.

### Mapping par défaut (V33)

Le module `FX_OPERATIONS` est **auto-activé** à la complétion du wizard pour les 6 types
métier suivants (mapping `business_type_module` ajouté par V33 — voir ci-dessous) :

| Type métier | Raison du mapping par défaut |
|---|---|
| `RETAIL_COMMERCE` | Commerce de détail — importations fréquentes en USD/EUR. |
| `WHOLESALE_COMMERCE` | Commerce de gros — flux transfrontaliers réguliers. |
| `MIXED_COMMERCE` | Commerce mixte (gros + détail) — idem. |
| `ECOMMERCE` | E-commerce — paiements multi-devises (Stripe/PayPal en USD). |
| `NGO_HUMANITARIAN` | ONG — financements bailleurs en USD/EUR, dépenses locales en HTG. |
| `HOSPITAL` | Hôpital — équipements médicaux importés en USD/EUR. |

Pour les autres types métier (`PROFESSIONAL_SERVICES`, `IT_CONSULTING`, `CREATIVE_AGENCY`,
`MAINTENANCE_SERVICES`, `ACCOUNTING_FIRM`, `SCHOOL`, `CUSTOM`), le module n'est pas activé
par défaut. L'administrateur doit l'activer explicitement via
`POST /api/v1/companies/{companyId}/modules/FX_OPERATIONS/activate`.

## Relations avec les autres modules

### Dépendances

- `:core` — `ExchangeRateService`, `ExchangeRate`, `CurrencyRoundingService`, `ReportingClass`.
- `:audit-trail` — auditing.
- `:chart-of-accounts` — `Account`, `AccountRepository`.
- `:accounting-engine` — `AccountingEngineService`, `JournalRepository`.
- `:company` — `ModuleAccessGuard`, `ModuleCode.FX_OPERATIONS` (gating — stabilisation 2026-07-25 suite 4 §3).

### Modules qui dépendent de celui-ci

- `:reporting` — `FxOperationRepository` (pour l'export CSV `fx_operations_register` — Part E4).

### Événements publiés / consommés

- **Publie** : aucun au MVP.
- **Consomme** : aucun directement (mais lit les `ExchangeRate` de `:core`).

## Tables / migrations Flyway

- `src/main/resources/db/migration/V31__fx_operations.sql` — table `fx_operation`.
  CHECK sur `type` (BUY/SELL/REVALUATION) et `status` (POSTED/REVERSED).
  Index sur `(company_id, operation_date)`.
- `company/src/main/resources/db/migration/V33__fx_operations_module.sql` — **suite 4 §3**
  (migration du module `:company`, documentée ici car elle porte le mapping par défaut
  `business_type_module` du module `FX_OPERATIONS`). Élargit le CHECK
  `chk_btm_module_code` pour autoriser `FX_OPERATIONS` (le 23<sup>e</sup> code de l'enum
  `ModuleCode`) et insère 6 lignes de mapping par défaut (voir tableau ci-dessus).

## Points d'attention

- ⚠️ **Une seule devise fonctionnelle** — le MVP suppose que la devise fonctionnelle est
  HTG (configurable dans une future version via `Company.functionalCurrency`).
- ⚠️ **Pas de suivi des soldes par devise** — le compte 521 est unique. Pour tracer
  séparément les soldes USD/EUR/HTG, l'utilisateur doit créer des sous-comptes (521-USD,
  521-EUR, etc.) et les passer via `bankAccountId`.
- ⚠️ **Pas de contre-passation** — une opération FX ne peut pas être contre-passée
  au MVP. En cas d'erreur, créer une opération inverse manuellement.
- ⚠️ **Réévaluation manuelle** — la réévaluation de fin de période n'est pas automatisée.
  L'utilisateur doit lancer une opération `REVALUATION` pour chaque devise étrangère
  détenant un solde.
- ⚠️ **`403 MODULE_NOT_ENABLED` depuis la suite 4** — tout client mobile qui appelait les
  endpoints `:fx-operations` sans module activé recevait 200/201 (module always-on avant la
  suite 4) et reçoit désormais 403. Pour activer le module sur une société existante (type
  métier hors mapping par défaut V33), appeler
  `POST /api/v1/companies/{companyId}/modules/FX_OPERATIONS/activate` avec un rôle ADMIN.

## Tests

Le module est exercé par le script `seed_commerce.py` (étape 17) qui crée des taux de
change, achète/vend des USD, et effectue une réévaluation de fin d'année. Le test
d'intégration `FxOperationsIntegrationTest` couvre le gate `MODULE_NOT_ENABLED`
(activer/désactiver le module `FX_OPERATIONS` → vérifier que les endpoints retournent 403
puis 200/201).
