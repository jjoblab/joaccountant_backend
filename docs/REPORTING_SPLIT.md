# R-40 — Scission du module `:reporting` (cible de refactoring)

> Statut : **Documentation de la cible** — le refactoring réel sera planifié en v6.
> R-40 (lot-F-ops-docs) — P2 amélioration continue.

## Constat actuel

Le module `:reporting` dépend actuellement de **19 modules métier** (le plus gros fan-in du projet). Il regroupe deux responsabilités distinctes :

1. **Reporting-core** : dashboard, balance âgée clients/fournisseurs, indicateurs temps réel — requêtes en lecture simple, latence attendue < 500ms
2. **Reporting-exports** : 15 exports PDF/CSV sectoriels (bilan, CR, TAFIRE, journal, grand livre, DCR DGI, etc.) — génération lourde avec PDF rendering, latence attendue < 5s

## Problématiques

- Blast radius : une modification d'un export PDF force le rebuild complet du module `:reporting`, y compris le code du dashboard
- Tests : impossible de tester les exports sans charger tout le contexte dashboard (et inversement)
- Couplage : le dashboard dépend de `:accounting-engine` (lecture), mais les exports dépendent en plus de `:document-generation`, `:funds-grants`, `:fx-operations`, etc.
- Scalabilité : les exports PDF pourraient être déplacés vers un service séparé (scale independently) si le module était déjà scindé

## Cible recommandée

```
:reporting-core
    └── dépend de :core, :audit-trail, :chart-of-accounts, :accounting-engine,
        :financial-statements, :third-parties, :company
    └── contient : DashboardService, AgedBalanceService, KPI computation
    └── latence cible : < 500ms

:reporting-exports
    └── dépend de :reporting-core, :document-generation, :invoicing, :purchasing,
        :expenses, :payroll, :inventory, :time-billing, :fixed-assets, :fx-operations,
        :funds-grants, :tax
    └── contient : 15 services d'export PDF/CSV, DgiHaitiExportService, FacturXExporter
    └── latence cible : < 5s (peut être async via @Async)
```

## Bénéfices attendus

| Métrique | Avant | Après |
|----------|-------|-------|
| Modules rebuildés sur modif export | 1 (entier :reporting) | 1 (:reporting-exports seul) |
| Tests à charger pour tester un export | Tout le contexte dashboard | Contexte minimal reporting-exports |
| Fan-in :reporting-core | 19 → 7 | 7 (réduit de 63%) |
| Fan-in :reporting-exports | 19 | 13 (réduit de 32%) |
| Possibilité de scale independently | Non | Oui (deploy :reporting-exports en pods dédiés) |

## Plan de migration (recommandé pour v6)

1. **Étape 1 — Créer `:reporting-exports` module** (1 jh) : `settings.gradle.kts` + `build.gradle.kts` + déplacer les packages `export/`, `pdf/`, `csv/`, `dgi/`
2. **Étape 2 — Déplacer les services d'export** (3 jh) : `BilanPdfExporter`, `IncomeStatementPdfExporter`, `CashFlowPdfExporter`, `JournalPdfExporter`, `DgiHaitiExportService`, `FacturXExporter`, etc. vers `:reporting-exports`
3. **Étape 3 — Adapter ArchUnit rules** (1 jh) : créer Rule 43-50 pour `:reporting-exports`, adapter Rule 32-41 existantes pour `:reporting` → `:reporting-core`
4. **Étape 4 — Tests** (2 jh) : vérifier que tous les tests `:app` passent toujours, ajuster les imports
5. **Étape 5 — Documentation** (0.5 jh) : mettre à jour README.md + 28 README modules + OPERATIONS.md

**Charge totale estimée** : 7.5 jh — à planifier dans le sprint v6.

## Pourquoi ne pas le faire maintenant ?

- Le scinding est un refactoring mécanique mais qui touche beaucoup de fichiers (~30 services d'export à déplacer)
- Risque de casser les tests d'intégration existants (310 tests dans `:app` qui référencent les classes par leur FQN)
- Bénéfice modéré pour l'instant (le module compile en < 10s, pas de goulot d'étranglement build)
- Priorité P2 — les P0/P1 du plan 90 jours ont été traités en priorité

## Alternative légère (quick win v5.3)

Si le blast radius est un problème immédiat mais que la scission complète est trop coûteuse :
- Séparer les packages internes `:reporting` en `dashboard/` vs `exports/` (sans créer de module Gradle)
- Ajouter une règle ArchUnit qui interdit aux classes du package `dashboard/` de dépendre des classes du package `exports/`
- Bénéfice : isolation logique sans coût de refactoring, préparation de la scission future

Cette alternative légère peut être faite en 1 jh et pose les fondations pour la scission v6.
