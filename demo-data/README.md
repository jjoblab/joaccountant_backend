# Module Démos — V8.1

> **4 entreprises fictives haïtiennes** sur 2 exercices fiscaux, endpoints publics pour prospection commerciale.

## Vue d'ensemble

Le module `:demo-data` crée 4 entreprises fictives représentatives des 4 segments PME haïtiens validés en v7 :

| Code | Nom | Segment | Localisation | Employés | CA annuel | Devise | Framework | IS |
|------|-----|---------|--------------|----------|-----------|--------|-----------|----|
| `BOUTIK_LAKAY` | Boutik Lakay S.A. | Commerce retail | Pétion-Ville | 4 | ~6M HTG | HTG | PCN_HAITI | 30% |
| `MOISE_ASSOCIES` | Moïse & Associés Conseil S.A. | Services pro | Port-au-Prince | 8 | ~18M HTG | HTG | PCN_HAITI | 30% |
| `ESPWA_POU_AYITI` | Espwa pou Ayiti | ONG humanitaire | Port-au-Prince (Delmas 33) | 35 | ~60M HTG (~5M USD) | USD | PCN_HAITI | 0% (NGO_EXEMPT) |
| `CARIBBEAN_TEXTILES` | Caribbean Textiles S.A. | Industrie zone franche | CODEVI, Ouanaminthe | 1200 | ~144M HTG (~12M USD) | USD | IFRS_FULL | 15% (FREE_ZONE) |

**Exercices fiscaux** (exercice haïtien 01/10 → 30/09) :
- `FY2024-2025` : 01/10/2024 → 30/09/2025
- `FY2025-2026` : 01/10/2025 → 30/09/2026 (en cours)

## Endpoints API publics (sans auth)

```http
GET /api/v1/demos                              → liste des 4 entreprises démos
GET /api/v1/demos/{demoCode}                   → détail d'une entreprise
GET /api/v1/demos/{demoCode}/dashboard?fy=     → KPIs + alertes + transactions récentes
```

**Sécurité** : les endpoints `GET /api/v1/demos/**` sont publics (pas d'auth). Toutes les
entreprises démos ont `is_demo=TRUE` dans la table `companies` — filtrage automatique, aucune
fuite possible vers les entreprises réelles.

## Lancement

```bash
export JAVA_HOME=/home/z/jdk17
export PATH=$JAVA_HOME/bin:$PATH
cd /home/z/my-project/joaccountant_patched

# Profil demo : active le seed automatique au démarrage
./gradlew :app:devRun --args='--spring.profiles.active=demo'

# Vérifier les endpoints
curl -s http://localhost:8080/api/v1/demos | jq .
curl -s http://localhost:8080/api/v1/demos/BOUTIK_LAKAY | jq .
curl -s http://localhost:8080/api/v1/demos/BOUTIK_LAKAY/dashboard?fy=FY2025-2026 | jq .kpi.totalRevenue
curl -s http://localhost:8080/api/v1/demos/CARIBBEAN_TEXTILES/dashboard | jq .kpi.incomeTax
```

## Architecture

```
demo-data/
├── build.gradle.kts
├── src/main/java/jo/accountant/demo/
│   ├── DemoDataSeeder.java                ← orchestrateur (profil "demo")
│   ├── controller/DemoController.java     ← 3 endpoints publics
│   ├── service/DemoService.java           ← KPIs + filtrage is_demo
│   ├── seeders/
│   │   ├── CompanySeeder.java             ← interface
│   │   ├── RetailCommerceSeeder.java      ← PME1 Boutik Lakay
│   │   ├── ProfessionalServicesSeeder.java← PME2 Moïse & Associés
│   │   ├── NgoHumanitarianSeeder.java     ← PME3 Espwa pou Ayiti
│   │   └── FreeZoneIndustrySeeder.java    ← PME4 Caribbean Textiles
│   ├── dto/
│   │   ├── DemoCompanySummary.java
│   │   └── DemoDashboard.java
│   └── fixtures/
│       ├── HaitianNames.java              ← 30 prénoms + 30 noms haïtiens
│       ├── HaitianAddresses.java          ← adresses PAP/Pétion-Ville/Cap/Ouanaminthe
│       ├── HaitianProducts.java           ← 30 produits retail (alimentation, ménagers, cosmétiques)
│       └── ExchangeRateFixtures.java      ← taux BRH HTG/USD 2024-2026 mensuels
├── src/main/resources/db/migration/
│   └── V94__demo_data_module.sql          ← table demo_seed_history + colonne is_demo
└── README.md                              ← ce fichier
```

## État V8.1 (version simplifiée)

La V8.1 crée les 4 entreprises avec leur configuration complète (NIF, secteur, framework
comptable, taxExemptionStatus, isFreeZone, monthlyLegalHours=208 Haïti). Les KPIs affichés
sont des estimations conformes au profil de chaque PME (CA annuel, marge 15%, IS selon
statut d'exonération).

**Roadmap V9** (données métier complètes) :
- Employés (4 / 8 / 35 / 1200)
- Clients + fournisseurs + produits
- Écritures comptables mensuelles (24 mois × 4 entreprises)
- Factures ventes/achats (50K factures/an pour Caribbean Textiles)
- Bulletins de paie (28 800 sur 2 ans pour Caribbean Textiles via Spring Batch)
- Déclarations DGI mensuelles (TVA/TCA/RS/acompte IS)
- Clôtures annuelles (bilan + CR + CF + SCE pour IFRS_FULL)
- Subventions + formats bailleurs pour ONG (USAID SF-425, EU PRAG, BM)

## Limitations

- Les données démo sont fictives — ne pas utiliser en production
- Le seed est idempotent (vérification nom + isDemo=true) — re-seed manuel via suppression
- Les KPIs sont estimés tant que les écritures ne sont pas générées (V9)
