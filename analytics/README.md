# Module : analytics

> Dimensions analytiques transverses (plans + valeurs) — mécanisme générique multi-secteur pour ventiler les écritures.

## Rôle du module

Le module `:analytics` fournit le mécanisme générique d'analyse dimensionnelle qui permet à
Commerce, Service et ONG de partager le même moteur comptable sans branches de code
spécifiques par secteur. Il est **always-on** (activé pour tous les types métier via
`BusinessTypeModuleService.alwaysOnModules()`) et fonctionne pour les 6 référentiels — les axes
analytiques sont agnostiques au framework.

Concrètement, un plan analytique est un axe d'analyse (ex. `FONDS` pour une ONG, `PROJET`
pour une société de services, `POINT_VENTE` pour un commerce). Les valeurs d'un plan sont
hiérarchiques (parent/enfant optionnel). Une écriture peut être ventilée sur plusieurs
valeurs de plusieurs plans via `JournalLineAnalyticalTag` (table du `:accounting-engine`).

## Ce qu'il fait précisément

### Entités principales

- `AnalyticalDimensionPlan` — un axe d'analyse par entreprise. Champs : `code` (unique par
  entreprise), `label`, `active`. Ex. `{code: "FONDS", label: "Fonds/Subventions"}`.
- `AnalyticalDimensionValue` — valeur dans un plan, hiérarchie parent/enfant optionnelle.
  Champs : `planId`, `parentId` (nullable), `code` (unique par plan), `label`, `active`.
  Ex. `{code: "F-RESTRICTED-001", label: "Subvention USAID 2026", parentId: ...}`.

### Règles métier clés

1. **`code` plan unique par entreprise** — contrainte DB `uc_adp_company_code`. 409
   `PLAN_CODE_ALREADY_EXISTS` si collision.
2. **`code` valeur unique par plan** — contrainte DB `uc_adv_plan_code`. 409
   `VALUE_CODE_ALREADY_EXISTS` si collision.
3. **Parent doit appartenir au même plan** — 422 `PARENT_WRONG_PLAN` sinon.
4. **Recommandation 2-4 plans actifs max** — au-delà, `hasTooManyActivePlans` retourne
   `true` et `:accounting-engine` émet un avertissement (pas un blocage dur, conformément
   au §5).
5. **Validation au postage** — `validateValue(companyId, planId, valueId)` est appelée par
   `:accounting-engine` au postage d'une `JournalLine` qui porte un tag analytique. Lève
   `ANALYTICAL_VALUE_NOT_FOUND` (404 ou 422 selon le contexte), `ANALYTICAL_VALUE_WRONG_PLAN`
   ou `ANALYTICAL_VALUE_INACTIVE` si la valeur n'est pas valide.
6. **`requiresAnalyticalTagPlanIds` sur `Account`** — un compte peut exiger qu'une ligne
  postée sur lui porte une valeur pour un plan donné. C'est `:accounting-engine` qui
  vérifie cette exigence au postage (lève `ANALYTICAL_TAG_REQUIRED`), pas `:analytics`.

### Cycle de vie des objets

- `AnalyticalDimensionPlan` : `ACTIVE` → `INACTIVE` (via update direct, pas d'endpoint
  public de désactivation — opération DB manuelle).
- `AnalyticalDimensionValue` : `ACTIVE` → `INACTIVE`. Une valeur inactive ne peut plus être
  utilisée dans de nouvelles écritures, mais les écritures passées qui la référencent
  restent valides.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/analytics/plans` | Crée un plan analytique. Corps : `{code, label}` | 409 `PLAN_CODE_ALREADY_EXISTS`, 422 `PLAN_CODE_REQUIRED`/`PLAN_LABEL_REQUIRED` |
| GET | `/api/v1/companies/{companyId}/analytics/plans` | Liste les plans du tenant | — |
| POST | `/api/v1/companies/{companyId}/analytics/plans/{planId}/values` | Crée une valeur dans un plan. Corps : `{code, label, parentId?}` | 404 `AnalyticalDimensionPlan`, 409 `VALUE_CODE_ALREADY_EXISTS`, 422 `VALUE_CODE_REQUIRED`/`VALUE_LABEL_REQUIRED`/`PARENT_WRONG_PLAN` |
| GET | `/api/v1/companies/{companyId}/analytics/plans/{planId}/values` | Liste les valeurs d'un plan | 404 `AnalyticalDimensionPlan` |

> Aucun endpoint de mise à jour ou suppression — les plans et valeurs sont immuables après
> création (sauf désactivation DB manuelle).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `TenantContext`.

### Modules qui dépendent de celui-ci

- `:accounting-engine` — consomme `AnalyticsService.validateValue` au postage d'une
  `JournalLine` qui porte un tag analytique, et `AnalyticsService.findPlanById` pour valider
  les `requiresAnalyticalTagPlanIds` d'un compte. Utilise aussi
  `hasTooManyActivePlans` pour émettre un avertissement.

### Événements publiés / consommés

- **Publie** : aucun (les créations de plans/valeurs sont auditées via `AuditEvent`
  générique si un listener est branché, mais aucun événement typé n'est publié).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V15__analytics.sql` — tables
  `analytical_dimension_plan` et `analytical_dimension_value`. Contraintes uniques
  `(company_id, code)` sur le plan, `(plan_id, code)` sur la valeur. FK
  `value.plan_id → plan(id) ON DELETE CASCADE`. Index sur `plan_id`, `parent_id`,
  `company_id`.

## Points d'attention (hérités de l'audit)

- ⚠️ **Aucun endpoint de désactivation** — pour désactiver un plan ou une valeur, il faut
  une opération DB manuelle. Le client mobile doit informer l'utilisateur qu'une fois créé,
  un plan/valeur ne peut pas être modifié ni supprimé via l'API (uniquement désactivé
  côté backend).
- ⚠️ **`requiresAnalyticalTagPlanIds` non pré-vérifié par les modules métier** — les modules
  qui créent des écritures automatiques (invoicing, fixed-assets, inventory) passent
  `analyticalTags = List.of()` dans les `LineDto` (audit M12). Si un compte exige un tag
  analytique, le postage échouera avec `ANALYTICAL_TAG_REQUIRED` levé par
  `:accounting-engine`, sans contexte métier clair (l'utilisateur ne saura pas quelle ligne
  de facture/asset/mouvement est en cause).
- ⚠️ **Pas d'agrégation analytique exposée** — il n'y a pas d'endpoint du type
  `GET /analytics/plans/{planId}/balance` pour obtenir le solde par valeur analytique. Le
  client mobile doit passer par `GET /accounting-engine/ledger` et faire l'agrégation
  côté client (ou par `GET /reporting/dashboard` qui n'expose pas non plus cette coupe).

## Tests

Couvert indirectement par `AccountingEngineIntegrationTest` dans `:app` — test de postage
d'écriture avec tag analytique, test de compte exigeant un tag (rejet si manquant). Pas de
test d'intégration dédié à `:analytics` seul.
