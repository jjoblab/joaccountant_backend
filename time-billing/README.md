# Module : time-billing

> Suivi du temps par projet, taux facturables et WIP unbilled pour le secteur Service.

## Rôle du module

Le module `:time-billing` gère le temps passé par les ressources sur les projets de
prestation. Il est **sectoriel** : activé uniquement pour le type métier `PROFESSIONAL_SERVICES` via
le mapping `business_type_module` (restructuration :company §6). Il fonctionne pour les 6 référentiels — les
projets et taux sont agnostiques au framework comptable.

Le module **ne génère aucune écriture comptable** — le WIP (travail en cours) est stocké
comme information, sans contrepartie comptable. La reconnaissance de revenu à l'avancement
n'est **pas implémentée** (option désactivée par défaut). Ce n'est qu'au moment de la
facturation via `:invoicing` (avec `timesheetEntryId` dans une `InvoiceLine`) que le temps
est converti en écriture comptable.

## Ce qu'il fait précisément

### Entités principales

- `Project` — projet de prestation. `(companyId, code)` unique. Champs : `code`, `label`,
  `clientThirdPartyId` (nullable — projet interne si null), `status`
  (ACTIVE/COMPLETED/ON_HOLD), `billingType` (FIXED_FEE / TIME_AND_MATERIALS).
- `BillableRate` — taux horaire facturable. `(projectId, resourceUserId)` unique. Champs :
  `projectId`, `resourceUserId`, `hourlyRate`, `validFrom`, `validTo` (nullable).
- `TimesheetEntry` — entrée de feuille de temps. Champs : `projectId`, `resourceUserId`,
  `entryDate`, `hours` (BigDecimal, ex. 1.5 = 1h30), `billable` (default true), `approved`
  (default false), `invoiced` (default false), `description`.
- `ProjectStatus` (enum) — `ACTIVE`, `COMPLETED`, `ON_HOLD`.
- `BillingType` (enum) — `FIXED_FEE`, `TIME_AND_MATERIALS`.

### Règles métier clés

1. **Seules les entrées `approved=true` ET `billable=true` sont facturables** — une entrée
   non approuvée ou non facturable ne compte pas dans le WIP.
2. **Idempotence métier** — une entrée `invoiced=true` ne peut pas être réutilisée sur une
   autre facture. Le passage à `invoiced=true` est fait par `:invoicing` au moment de
   l'émission d'une facture qui référence l'entrée.
3. **WIP sans écriture comptable** — le temps non facturé s'accumule comme WIP mais ne
   génère aucune écriture. La reconnaissance de revenu à l'avancement (option Phase 10)
   n'est pas implémentée.
4. **`(companyId, code)` unique pour Project** — 409 `PROJECT_CODE_EXISTS` sinon.
5. **`(projectId, resourceUserId)` unique pour BillableRate** — un taux par couple
   projet/ressource (pas de taux global par ressource).

### Cycle de vie des objets

- `Project` : `ACTIVE → ON_HOLD → COMPLETED` (transitions manuelles, pas d'endpoint
  public — opération DB).
- `TimesheetEntry` : `submitted (approved=false) → approved (approved=true) → invoiced
  (invoiced=true)`.
  - `submitted → approved` : via `PATCH /timesheet-entries/{id}/approve`.
  - `approved → invoiced` : via `:invoicing` au moment où une facture référençant
    l'entrée est émise (passage DRAFT → ISSUED).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/time-billing/projects` | Crée un projet | 409 `PROJECT_CODE_EXISTS`, 422 `PROJECT_CODE_REQUIRED`/`PROJECT_LABEL_REQUIRED` |
| GET | `/api/v1/companies/{companyId}/time-billing/projects` | Liste les projets (⚠️ non paginé) | — |
| POST | `/api/v1/companies/{companyId}/time-billing/billable-rates` | Crée un taux horaire facturable | 404, 409 rate exists |
| POST | `/api/v1/companies/{companyId}/time-billing/timesheet-entries` | Crée une entrée de temps | 404 `Project`, 422 `HOURS_INVALID` |
| PATCH | `/api/v1/companies/{companyId}/time-billing/timesheet-entries/{entryId}/approve` | Approuve une entrée | 404, 409 already approved |
| GET | `/api/v1/companies/{companyId}/time-billing/projects/{projectId}/unbilled` | WIP unbilled (entrées approuvées, billables, non facturées) avec total | 404 |
| GET | `/api/v1/companies/{companyId}/time-billing/utilization?from=&to=` | **Nouveau (Part E3)** — Taux d'utilisation des consultants par projet sur la période. Agrège par (projet, consultant) : `hoursLogged` (toutes les heures saisies), `hoursBilled` (facturables + approuvées + facturées), `hoursUnbilled` (facturables + approuvées + non facturées = WIP), `utilizationRate` (% = `(billed + unbilled) / logged × 100`). Si `from`/`to` sont omis, borne inférieure = `1900-01-01` et borne supérieure = aujourd'hui. Le champ `consultant` est le `resourceUserId` sous forme de UUID string — la résolution en nom affichable se fait côté client (pas de dépendance directe :time-billing → :auth). Utilisé comme vue JSON source pour l'export CSV `time_billing_utilization` (`:reporting`). | — |

> **Stabilisation 2026-07-25 (Part E3)** — ajout de `GET /utilization` pour exposer
> l'agrégation (projet × consultant) des heures saisies / facturées / non facturées.
> Avant cet endpoint, le mobile ne disposait que du WIP unbilled par projet
> (`GET /projects/{id}/unbilled`) — sans vue agrégée par consultant ni distinction
> heures facturées vs non facturées. Le nouvel endpoint sert aussi de **vue JSON source**
> pour l'export CSV `time_billing_utilization` (`:reporting`) — le mobile peut l'appeler
> directement pour l'affichage in-app (tableau/graphe), puis déclencher le download CSV
> via `:reporting` pour le bouton « Télécharger ».

### Schéma de réponse — `GET /utilization` (Part E3)

```json
[
  {
    "projectId": "0192c0a2-3e4f-5a6b-7c8d-9e0fabcd1234",
    "projectCode": "PRJ-001",
    "projectLabel": "Migration ERP client X",
    "consultantId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
    "consultant": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
    "hoursLogged": 120.50,
    "hoursBilled": 80.00,
    "hoursUnbilled": 24.50,
    "utilizationRate": 86.72
  }
]
```

- `hoursLogged` = toutes les heures saisies sur la période (billables ou non, approuvées ou non).
- `hoursBilled` = heures facturables + approuvées + déjà facturées (`invoiced = true`).
- `hoursUnbilled` = heures facturables + approuvées + non facturées (`invoiced = false`) = WIP.
- `utilizationRate` = `(hoursBilled + hoursUnbilled) / hoursLogged × 100` (0 si `hoursLogged = 0`).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ApplicationEventPublisher`.

### Modules qui dépendent de celui-ci

- `:invoicing` — référence `timesheetEntryId` dans `InvoiceLine` (lien faible via ID, pas
  de jointure dure). Au passage DRAFT → ISSUED, `:invoicing` devrait marquer
  `invoiced=true` sur les entrées référencées (à vérifier dans le code `:invoicing`).
- `:notifications` — consomme `ProjectCreatedEvent` pour notifier le client.
- `:reporting` — recense le WIP unbilled pour le dashboard.

### Événements publiés / consommés

- **Publie** : `ProjectCreatedEvent`, `TimesheetEntryApprovedEvent`.
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V34__time_billing.sql` — tables `tb_project`,
  `tb_billable_rate`, `tb_timesheet_entry`. Unique `(company_id, code)` sur project,
  `(project_id, resource_user_id, valid_from)` sur rate. CHECK sur `status` (3 valeurs),
  `billing_type` (2 valeurs), `hours > 0`.

## Points d'attention (hérités de l'audit)

- ⚠️ **WIP non comptabilisé** — le temps approuvé non facturé n'apparaît dans aucune
  écriture comptable. Le bilan sous-évalue donc les créances clients potentielles tant que
  la facture n'est pas émise. Le client mobile doit afficher le WIP unbilled comme
  "information" et non comme "produit constaté".
- ⚠️ **Reconnaissance de revenu à l'avancement non implémentée** — pour un projet
  `FIXED_FEE` long, aucune écriture n'est générée à l'avancement. La totalité du revenu est
  constatée à la facturation finale. À corriger pour les projets pluri-annuels.
- ⚠️ **M14 — Arrondis à 4 décimales** — `setScale(4, HALF_UP)` sur le calcul du WIP
  (`hours × hourlyRate`). Pour une devise 0-décimales (XOF/XAF/JPY), les montants sont
  stockés avec 4 décimales.
- ⚠️ **Aucune pagination sur `GET /projects`** — retourne tous les projets. Le client
  mobile doit implémenter une recherche côté UI.
- ⚠️ **Pas de contrôle de rôle** sur les endpoints (audit B5) — un `VIEWER` peut créer un
  projet, une entrée de temps, s'auto-approuver.
- ⚠️ **Auto-approbation possible** — `PATCH /timesheet-entries/{id}/approve` ne vérifie pas
  que l'appelant est différent du `resourceUserId`. Un utilisateur peut approuver son
  propre temps (violation de la règle des quatre yeux implicite). À corriger.

## Tests

Couvert par `TimeBillingIntegrationTest` dans `:app` (11 tests) — création de projet, taux,
timesheet, approbation, WIP unbilled, idempotence métier (`invoiced=true`).

## Activation (restructuration :company §7)

Le module `:time-billing` est **sectoriel** : son utilisation exige que le module
`TIME_BILLING` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `TIME_BILLING` (voir `V8__business_type.sql`).
