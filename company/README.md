# Module : company

> Identité de l'entreprise (tenant), wizard d'onboarding en 9 étapes, catalogue de types
> métier piloté par données, et enforcement de l'activation des modules sectoriels.

## Rôle du module

Le module `:company` porte l'entité `Company` qui **est** le tenant dans tout le projet : le
`companyId` injecté par `TenantContextFilter` provient de cette table. Il est **always-on**
(activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`) et
fonctionne pour les 6 référentiels comptables — le `accountingFrameworkId` est choisi par
l'utilisateur à l'étape 6 du wizard et stocké sur la `Company`.

### Restructuration 2026-07-24

La modélisation organisationnelle s'articule désormais autour de **5 axes distincts** (au
lieu de 2 auparavant — `legalForm` + `sector`), et l'activation des modules est **pilotée par
données** plutôt que par un `switch` Java :

| Axe | Rôle | Implémentation |
|---|---|---|
| **organizationNature** | lucratif / non-lucratif / public / coopératif — filtre les formes juridiques valides | Enum Java `OrganizationNature` (4 valeurs stables) |
| **legalForm** | juridique pur (SARL, SA, ASSOCIATION...) — validée contre la nature | Enum Java `LegalForm` (existant, affiné par validateur croisé) |
| **sector** | classification large, **purement descriptive** — n'active plus rien directement | Enum Java `Sector` élargi (10 valeurs, sans `MIXTE`) |
| **primaryActivityLabel** | libellé libre décrivant l'activité réelle | Champ texte (statistique) |
| **businessTypeCode** | **LE moteur** : pointe vers la liste de modules à activer + la liste de champs additionnels obligatoires | **Table de référence en base** (`business_type`), extensible sans déploiement |

Cette restructuration corrige deux bugs documentés :
- **`MIXTE` cassé** — la liste `modulesFor(MIXTE)` était vide ; désormais le type métier
  `CUSTOM` (qui remplace `MIXTE`) active réellement la sélection manuelle de modules à
  l'étape 8 du wizard.
- **Étapes 2-9 non persistées** — chaque étape du wizard a désormais un payload spécifique
  et une sémantique métier réelle (validation croisée Nature/Forme, choix du type métier,
  champs spécifiques dynamiques, etc.).

## Ce qu'il fait précisément

### Entités principales

- `Company` — l'entreprise/tenant (NON TenantAware, c'est elle qui définit le tenant).
  Champs : `name`, `legalForm` (enum), `country` (ISO 3166-1 alpha-2), `functionalCurrency`
  (ISO 4217), `sector` (enum élargi 10 valeurs, **descriptif uniquement**),
  `organizationNature` (enum, requis), `businessTypeCode` (référence vers `BusinessType.code`,
  requis), `primaryActivityLabel` (texte libre, requis), `extraAttributes` (JSONB, valeurs
  des champs additionnels), `accountingFrameworkId` (FK, positionné à l'étape 6 du wizard),
  `fiscalYearStartMonth` (1-12), `wizardStep` (1-9 — constante `Company.TOTAL_WIZARD_STEPS`),
  `wizardCompleted`.
- `CompanyModule` — ligne `(companyId, moduleCode, enabled, activatedAt)`. Unique sur
  `(company_id, module_code)`. EST une `TenantAwareEntity`.
- `BusinessType` — entrée du catalogue de types métier (NON TenantAwareEntity — donnée de
  référence globale). Champs : `code` (clé primaire), `label`, `defaultOrganizationNature`,
  `defaultSector`, `description`, `active`.
- `BusinessTypeModule` — ligne du mapping `businessTypeCode → moduleCode` (NON
  TenantAwareEntity). Remplace l'ancien `switch SectorModuleMapping.modulesFor(Sector)`.
- `BusinessTypeRequiredField` — définition d'un champ additionnel obligatoire pour un
  type métier (NON TenantAwareEntity). Modèle générique `(fieldKey, label, fieldType,
  required, displayOrder)` — ajouter un champ = une insertion de référence, pas de code.
- `Sector` (enum, élargi à 10 valeurs) — `COMMERCE`, `SERVICE`, `SANTE`, `EDUCATION`,
  `AGRICULTURE`, `INDUSTRIE`, `ADMINISTRATION_PUBLIQUE`, `ONG_HUMANITAIRE`,
  `CABINET_COMPTABLE`, `AUTRE`. L'ancienne valeur `MIXTE` est retirée.
- `ModuleCode` (enum) — 23 codes de modules métier (18 d'origine + 4 ajoutés par la
  restructuration 2026-07-24 suite : `PURCHASING`, `EXPENSES`, `EMPLOYEES`, `PAYROLL` ;
  + 1 ajouté par la stabilisation 2026-07-25 suite 4 : `FX_OPERATIONS`). Les 8 modules
  sectoriels soumis au gate `MODULE_NOT_ENABLED` sont `INVENTORY`, `TIME_BILLING`,
  `FUNDS_GRANTS`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`,
  `FX_OPERATIONS`. Les 15 modules always-on (socle commun) ne le sont pas — voir
  `BusinessTypeModuleService.alwaysOnModules()`.
- `LegalForm` (enum) — 7 formes juridiques.
- `OrganizationNature` (enum) — 4 valeurs : `FOR_PROFIT`, `NON_PROFIT`, `PUBLIC_SECTOR`,
  `COOPERATIVE`.
- `BusinessTypeModuleService` — service de résolution « type métier → modules activés »
  (lit la table `business_type_module`). Remplace `SectorModuleMapping` (supprimé).
- `OrganizationNatureLegalFormValidator` — validateur croisé Nature ↔ LegalForm (ex.
  `ASSOCIATION`/`NGO` ⟹ `NON_PROFIT` uniquement).
- `ModuleAccessGuard` — enforcement de l'activation des modules (voir §7.2 du prompt) :
  appelé en tête de chaque endpoint des 6 modules sectoriels, lève `403 MODULE_NOT_ENABLED`
  si `CompanyModuleService.isEnabled` renvoie `false`.

### Règles métier clés

1. **Max 3 sociétés par utilisateur** par défaut (`MaxCompaniesGuard.ensureCanCreateOneMore`)
   — `max_companies_override` sur `User` permet de lever la limite. Lève `MAX_COMPANIES_REACHED`
   409.
2. **Le créateur devient OWNER** — à la création, une ligne `UserCompanyRole` est insérée avec
   `role = OWNER` et `acceptedAt = now()` (auto-acceptée).
3. **Verrouillage post-wizard** — `organizationNature`, `legalForm`, `sector`,
   `businessTypeCode` et les valeurs de `extraAttributes` sont verrouillés une fois
   `wizardCompleted = true`. `updateWizardStep` lève `WIZARD_ALREADY_COMPLETED` 409.
4. **Étapes du wizard en ordre** — `updateWizardStep` refuse de passer à l'étape N si la
   société est encore à l'étape M < N (`WIZARD_STEP_OUT_OF_ORDER` 409). L'étape 1 est
   ré-éditable (correction des champs d'identité).
5. **Chaque étape a un payload spécifique** :
   - Étape 1 — Identité : `name`, `country`, `functionalCurrency` (ré-éditable).
   - Étape 2 — Nature + Forme juridique : validation croisée `LEGAL_FORM_NATURE_MISMATCH`.
   - Étape 3 — Secteur d'activité (descriptif).
   - Étape 4 — Type métier (catalogue `BusinessType`).
   - Étape 5 — Activité principale (libellé libre).
   - Étape 6 — Référentiel comptable + mois de clôture.
   - Étape 7 — Champs spécifiques (formulaire dynamique `BusinessTypeRequiredField`).
   - Étape 8 — Récapitulatif modules (sélection manuelle pour `CUSTOM` uniquement).
   - Étape 9 — Confirmation finale (déclarative).
6. **`completeWizard` exige `wizardStep >= 9`** — sinon `WIZARD_STEP_INCOMPLETE` 409.
7. **Activation des modules centralisée** dans `BusinessTypeModuleService` :
   - **always-on** (tous types métier) : CHART_OF_ACCOUNTS, ACCOUNTING_ENGINE, THIRD_PARTIES,
     INVOICING, DOCUMENT_NUMBERING, APPROVAL_WORKFLOW, DOCUMENT_GENERATION, NOTIFICATIONS,
     AUDIT_TRAIL, FINANCIAL_STATEMENTS, ANALYTICS, REPORTING, EMPLOYEES, EXPENSES, PAYROLL
     (les 3 derniers ajoutés par la restructuration 2026-07-24 suite — Partie B).
   - **Par type métier** : lu en base via `business_type_module` (table de référence). Les
     8 modules sectoriels `INVENTORY`, `TIME_BILLING`, `FUNDS_GRANTS`, `FIXED_ASSETS`,
     `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` sont concernés.
   - **CUSTOM** : sélection manuelle de l'étape 8 (extraAttributes["customModules"]) activée
     en plus du socle always-on — correction du bug documenté « MIXTE non testé ».
8. **Enforcement via `ModuleAccessGuard`** (§7) : tous les endpoints des 8 modules sectoriels
   (`:inventory`, `:time-billing`, `:funds-grants`, `:tax`, `:fixed-assets`,
   `:bank-reconciliation`, `:purchasing`, `:fx-operations`) appellent `moduleAccessGuard.ensureEnabled(companyId, ModuleCode.XXX)`
   en tête. 403 `MODULE_NOT_ENABLED` si non activé. Les modules always-on (`:invoicing`,
   `:expenses`, `:employees`, `:payroll`) ne sont pas concernés. `:fx-operations` est
   désormais sectoriel depuis la stabilisation 2026-07-25 (suite 4 §3) — voir V44 pour le
   mapping par défaut.
9. **Anti-fuite d'existence** — `getCompanyForUser` lève 404 (pas 403) si l'utilisateur n'a
   pas accès, pour ne pas révéler l'existence de la société (§3.9).
10. **Constante `Company.TOTAL_WIZARD_STEPS = 9`** — référencée partout où le nombre
    d'étapes apparaît (validation `@Max` du DTO, `updateWizardStep`, `completeWizard`,
    messages d'erreur).

### Cycle de vie des objets

- `Company` : `wizardStep=1` → … → `wizardStep=9` → `wizardCompleted=true` (verrouillé).
  Diagramme :
  - `wizardStep=1` → `wizardStep=2` : via `PATCH /companies/{id}/wizard/2` (nature+forme)
  - … → `wizardStep=9` : via `PATCH /companies/{id}/wizard/9` (confirmation)
  - `wizardStep=9` → `wizardCompleted=true` : via `POST /companies/{id}/wizard/complete`
    (active les modules sectoriels, publie `CompanyWizardCompletedEvent`)
- `CompanyModule` : `disabled` (non créé) → `enabled` (lors du `completeWizard`).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies` | Liste les sociétés accessibles à l'utilisateur courant | — |
| POST | `/api/v1/companies` | Crée une société (wizard step 1 — identité uniquement) — créateur auto-OWNER | 409 `MAX_COMPANIES_REACHED`, 422 `COMPANY_NAME_REQUIRED`/`COUNTRY_INVALID`/`FUNCTIONAL_CURRENCY_INVALID` |
| GET | `/api/v1/companies/{companyId}` | Récupère une société (404 si pas accès) | 404 `Company` |
| PATCH | `/api/v1/companies/{companyId}/legal` | **V53 — audit v4.7 §4.2** — Met à jour les champs légaux `siret`/`vatNumber`/`nif`/`address` (ADMIN seulement). Editable après `wizardCompleted=true` car relève de la conformité réglementaire (mentions légales factures CGI art. 289 + Factur-X). Sémantique : seuls les champs non-nuls sont écrasés ; une chaîne `blank` efface le champ. Publie `CompanyLegalFieldsUpdatedEvent` pour audit-trail (audit `LEGAL_FIELDS_UPDATED`, PII masquée). | 403 `INSUFFICIENT_ROLE`, 404, 422 pattern mismatch (SIRET 14 chiffres, VAT `[A-Z]{2}[0-9A-Z]+`, etc.) |
| PATCH | `/api/v1/companies/{companyId}/wizard/{step}` | Met à jour une étape du wizard (1-9) | 404, 409 `WIZARD_ALREADY_COMPLETED`/`WIZARD_STEP_OUT_OF_ORDER`, 422 `INVALID_WIZARD_STEP`/`LEGAL_FORM_INVALID`/`LEGAL_FORM_NATURE_MISMATCH`/`SECTOR_INVALID`/`ORGANIZATION_NATURE_INVALID`/`BUSINESS_TYPE_CODE_REQUIRED`/`BUSINESS_TYPE_NOT_FOUND`/`ACCOUNTING_FRAMEWORK_ID_INVALID`/`ACCOUNTING_FRAMEWORK_NOT_FOUND`/`FISCAL_YEAR_START_INVALID`/`REQUIRED_FIELD_MISSING` |
| POST | `/api/v1/companies/{companyId}/wizard/complete` | Finalise le wizard, active les modules sectoriels | 404, 409 `WIZARD_ALREADY_COMPLETED`/`WIZARD_STEP_INCOMPLETE` |
| GET | `/api/v1/companies/{companyId}/modules` | Liste les modules activés pour cette société | — |
| GET | `/api/v1/business-types` | Liste le catalogue des types métier actifs (wizard étape 4). **Filtre optionnel `?sector=COMMERCE`** — si présent, ne renvoie que les types métier dont `defaultSector == sector`. Le mobile appelle ce endpoint avec le secteur choisi à l'étape 3 pour peupler l'étape 4. | 422 `SECTOR_INVALID` |
| GET | `/api/v1/business-types/{code}` | Détail d'un type métier + modules suggérés + champs requis (wizard étapes 4 et 7) | 404 `BUSINESS_TYPE_NOT_FOUND` |

> Les endpoints `POST /companies/{companyId}/users` (invitation), `PATCH .../users/{userId}/role`
> et `POST .../users/{userId}/accept` sont exposés dans ce module via `UserCompanyRoleController`
> mais délèguent à `UserCompanyRoleService` du module `:auth`. Voir le README de `:auth` pour
> le détail.

> **Changement cassant** : `POST /companies` (création) ne porte plus que les champs d'identité
> (`name`, `country`, `functionalCurrency`). Les anciens champs `legalForm`, `sector`,
> `accountingFrameworkId` et `fiscalYearStartMonth` doivent être saisis via les étapes 2, 3
> et 6 du wizard. Voir `MOBILE_SYNC_2026-07-24_business-type-restructuring.md` pour la migration
> mobile complète.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `AccountingFramework`, `AccountingFrameworkRepository`, exceptions,
  `TenantContext`, `ApplicationEventPublisher`.
- `:auth` — `UserCompanyRole`, `UserRole`, `UserCompanyRoleRepository`,
  `UserCompanyRoleService` (pour assigner le rôle OWNER au créateur et vérifier l'accès).

### Modules qui dépendent de celui-ci

- `:app` — pour les tests d'intégration qui créent une société avant d'exercer les autres
  modules.
- `:inventory`, `:fixed-assets` — dépendent déjà de `:company` avant la restructuration.
- `:time-billing`, `:funds-grants`, `:tax`, `:bank-reconciliation` — dépendance ajoutée
  par la restructuration §7.2 (pour `ModuleAccessGuard`). Aucune règle `ArchUnitTest` ne
  l'interdisait — confirmé après exécution (31 tests ArchUnit passent).

### Événements publiés / consommés

- **Publie** : `CompanyCreatedEvent` (à la création), `CompanyWizardCompletedEvent` (à la
  complétion du wizard — déclenche notamment l'initialisation du plan comptable via
  `:chart-of-accounts`), `CompanyLegalFieldsUpdatedEvent` (**V53** — à chaque
  `PATCH /companies/{id}/legal`, audit `LEGAL_FIELDS_UPDATED` avec oldValue/newValue JSON
  + PII masquée).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `V6__company_companies.sql` — table `companies`. CHECK sur `legal_form`, `sector`,
  `fiscal_year_start_month`, `country`, `functional_currency`. FK vers
  `accounting_framework(id)`.
- `V7__company_modules.sql` — table `company_module`. Contrainte unique
  `(company_id, module_code)`. FK différée `user_company_role.company_id → companies(id)`.
- `V8__business_type.sql` — tables `business_type`, `business_type_module`,
  `business_type_required_field` + seed des 7 types métier de base + mappings modules +
  champs requis (numéro d'agrément pour école, licence sanitaire pour hôpital, etc.).
- `V9__company_business_type_columns.sql` — ajout des colonnes `organization_nature`,
  `business_type_code`, `primary_activity_label`, `extra_attributes` (JSONB) sur `companies`
  + contraintes CHECK + FK vers `business_type(code)`.
- `V10__company_backfill_business_type.sql` — backfill des lignes existantes (renommage
  `ONG` → `ONG_HUMANITAIRE`, `MIXTE` → `AUTRE` ; mapping ancien `sector` →
  `business_type_code` par défaut ; `organization_nature` déduite de `legal_form`) +
  élargissement du CHECK sur `sector` (10 valeurs) + `business_type_code NOT NULL`.
- `V11__company_drop_framework_not_null.sql` — `accounting_framework_id` devient
  nullable (positionné à l'étape 6 du wizard, plus à l'étape 1).
- `V34__business_type_catalog_expansion.sql` — **restructuration 2026-07-24 (suite — Partie A)** :
  - Élargit le CHECK `chk_btm_module_code` pour autoriser `PURCHASING`, `EXPENSES`,
    `EMPLOYEES`, `PAYROLL` (préalable aux INSERT ci-dessous).
  - Ajoute 3 nouveaux types métier COMMERCE : `WHOLESALE_COMMERCE`, `MIXED_COMMERCE`,
    `ECOMMERCE` (mêmes modules que `RETAIL_COMMERCE` — distinction descriptive seulement).
  - Corrige le mapping `HOSPITAL` → ajoute `INVENTORY` (trou fonctionnel — stocks de
    médicaments/consommables).
  - Ajoute `PURCHASING` sur `RETAIL_COMMERCE`, `PROFESSIONAL_SERVICES`, `NGO_HUMANITARIAN`,
    `ACCOUNTING_FIRM`, `SCHOOL`, `HOSPITAL` (cohérence — tout le monde achète quelque chose).
- `V43__company_active_fiscal_year.sql` — **stabilisation 2026-07-25 (suite 4)** : ajoute la
  colonne nullable `active_fiscal_year_id` (UUID) sur `companies`. Cette colonne n'est plus
  lue par les endpoints de données depuis la suite 4 — la résolution de l'exercice fiscal
  passe désormais par `AccountingEngineService.resolveFiscalYear(companyId, fiscalYearId)`
  (per-request, sans shared state). Conservée pour rétro-compatibilité avec les endpoints
  dépréciés `POST /fiscal-years/{id}/activate` et `GET /fiscal-years/active`.
- `V44__fx_operations_module.sql` — **stabilisation 2026-07-25 (suite 4 §3)** : élargit le
  CHECK `chk_btm_module_code` pour autoriser `FX_OPERATIONS` (le 23<sup>e</sup> code de
  l'enum `ModuleCode`) et mappe `FX_OPERATIONS` par défaut sur 6 types métier :
  `RETAIL_COMMERCE`, `WHOLESALE_COMMERCE`, `MIXED_COMMERCE`, `ECOMMERCE`, `NGO_HUMANITARIAN`,
  `HOSPITAL`. Pour les autres types métier, le module s'active via le feature toggle
  `POST /api/v1/companies/{companyId}/modules/FX_OPERATIONS/activate`.
- `V45__business_type_catalog_expansion_service.sql` — **stabilisation 2026-07-25 (suite 4)** :
  ajoute 3 nouveaux types métier SERVICE : `IT_CONSULTING`, `CREATIVE_AGENCY`,
  `MAINTENANCE_SERVICES` (mêmes modules que `PROFESSIONAL_SERVICES` — distinction
  descriptive/UX seulement). Aucun `BusinessTypeRequiredField` ajouté pour ces 3 types
  au MVP (étape 7 du wizard accepte un payload vide).
- `V53__company_thirdparty_legal_fields.sql` — **audit v4.7 §4.2 (session 7)**. Ajoute les
  colonnes légales `siret` (VARCHAR 14, pattern 14 chiffres), `vat_number` (VARCHAR 20,
  pattern `[A-Z]{2}[0-9A-Z]+`), `nif` (VARCHAR 30, NIF/Numéro d'identification fiscale) et
  `address` (TEXT) sur `companies` **et** sur `third_party` (migration partagée avec le
  module `:third-parties`). Ces champs alimentent les mentions légales des factures
  (CGI art. 289) et le Factur-X (`SellerTradeParty` / `BuyerTradeParty`). Exposés via
  `PATCH /companies/{id}/legal` et `ThirdPartyResponse`.

## CompanyResponse DTO (V53)

La réponse `CompanyResponse` expose désormais les champs légaux (audit v4.7 §4.2) :

```json
{
  "id": "...",
  "name": "Boutique Pétion-Ville",
  "legalForm": "SARL",
  "country": "HT",
  "functionalCurrency": "HTG",
  "sector": "COMMERCE",
  "organizationNature": "FOR_PROFIT",
  "businessTypeCode": "RETAIL_COMMERCE",
  "primaryActivityLabel": "Commerce de détail",
  "accountingFrameworkId": "...",
  "fiscalYearStartMonth": 1,
  "wizardStep": 9,
  "wizardCompleted": true,
  "siret": "12345678900012",
  "vatNumber": "FR12345678901",
  "nif": "1234567890",
  "address": "12 rue Pétion-Ville, Port-au-Prince",
  "createdAt": "2026-07-24T10:00:00Z",
  "updatedAt": "2026-07-28T15:30:00Z"
}
```

Ces champs sont éditables après `wizardCompleted=true` (conformité réglementaire) via
`PATCH /api/v1/companies/{id}/legal`. Une modification publie
`CompanyLegalFieldsUpdatedEvent` → trace d'audit `LEGAL_FIELDS_UPDATED` avec old/new JSON
(PII masquée).

## Catalogue de types métier — extensions SERVICE (V45)

Au-delà des 3 variantes COMMERCE ajoutées en V34 (`WHOLESALE_COMMERCE`, `MIXED_COMMERCE`,
`ECOMMERCE`), la vague 2026-07-25 (suite 4) enrichit le secteur `SERVICE` avec 3 nouveaux
types métier. Ils ont tous `defaultOrganizationNature = FOR_PROFIT`,
`defaultSector = SERVICE`, et le **même mapping modules que `PROFESSIONAL_SERVICES`**
(volontaire — distinction purement descriptive/UX, pas d'implication technique au MVP).

| Code | Label | Nature | Secteur | Modules sectoriels auto-activés | Champs requis (étape 7) |
|---|---|---|---|---|---|
| `IT_CONSULTING` | Conseil et services informatiques | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` | — |
| `CREATIVE_AGENCY` | Agence créative, marketing et communication | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` | — |
| `MAINTENANCE_SERVICES` | Services de maintenance et réparation | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` | — |

Le catalogue de types métier actifs passe donc de **10 à 13 entrées** (hors `CUSTOM`) :
7 d'origine (V8) + 3 COMMERCE (V34) + 3 SERVICE (V45) + `CUSTOM`. Les 3 nouvelles
entrées sont visibles via `GET /api/v1/business-types` (sans filtre) et via
`GET /api/v1/business-types?sector=SERVICE` (filtre sectoriel — renvoie les 4 types SERVICE
: `PROFESSIONAL_SERVICES`, `IT_CONSULTING`, `CREATIVE_AGENCY`, `MAINTENANCE_SERVICES`).

Aucun `BusinessTypeRequiredField` n'est ajouté pour ces 3 nouveaux types au MVP — la
création d'entreprise via le wizard n'exige donc aucun champ spécifique supplémentaire à
l'étape 7. Voir `MOBILE_SYNC_2026-07-25_service-sector-and-reports.md` §1 pour le contrat
mobile détaillé.

## Points d'attention (post-restructuration)

- ⚠️ **`POST /companies` est un changement cassant** — le payload de création ne contient
  plus que `name`/`country`/`functionalCurrency`. Les clients mobiles doivent migrer vers
  le nouveau flux wizard (voir `MOBILE_SYNC_2026-07-24_business-type-restructuring.md`).
- ⚠️ **`403 MODULE_NOT_ENABLED` sur 8 modules sectoriels** — tout client qui appelait
  `:inventory`, `:time-billing`, `:funds-grants`, `:tax`, `:fixed-assets`,
  `:bank-reconciliation`, `:purchasing` ou `:fx-operations` sans module activé recevait
  200/201 et reçoit désormais 403. Le gate `:fx-operations` a été ajouté par la
  stabilisation 2026-07-25 (suite 4 §3). Voir `ENDPOINTS_CHANGELOG.md` pour le détail par
  endpoint.
- ⚠️ **`Sector` élargi à 10 valeurs, `MIXTE` retiré** — backfill automatique en V10
  (`MIXTE` → `AUTRE`, `ONG` → `ONG_HUMANITAIRE`).
- ✅ **`CUSTOM` remplace `MIXTE`** et active réellement la sélection manuelle de modules
  à l'étape 8 du wizard (correction du bug documenté).
- ✅ **Étapes 2-9 ont désormais une sémantique réelle** — chaque étape persiste des
  données métier et applique des validations (correction du bug documenté).
- ⚠️ **Aucune pagination sur `GET /companies`** — mais limité en pratique par le max 3 sociétés
  par utilisateur, donc faible risque.
- ⚠️ **`completeWizard` ne fait pas de rollback transactionnel** si l'activation d'un module
  échoue — une `CompanyModule` partielle peut persister. Le client mobile doit pouvoir
  reprendre le wizard en cas d'échec (mais `WIZARD_ALREADY_COMPLETED` bloque si l'étape a
  déjà été complétée — il faudra un endpoint de réparation ou un fix côté service — backlog).

## Tests

Couvert par `Phase1IntegrationTest` dans `:app` (24 tests) qui exerce le flux register →
create company → wizard 9 étapes → complete → module activation, pour 3 types métier
(RETAIL_COMMERCE, NGO_HUMANITARIAN, CUSTOM avec sélection manuelle), plus la validation
croisée Nature/Forme juridique et le verrouillage post-wizard. `CUSTOM` est désormais
couvert (correction du trou A-3).
