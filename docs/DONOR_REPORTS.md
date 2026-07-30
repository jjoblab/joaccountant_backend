# Rapports bailleurs structurés (USAID SF-425, EU PRAG, Banque Mondiale)

> Version : **v6-3** (formats bailleurs) — squelette fonctionnel.
> Module : `:funds-grants`
> Migration : `V69__donor_report_formats.sql`
> Concerne : ONG multiprojets/multibailleurs (USAID, UE, BM, CRS, ...) — gap bloquant
> identifié lors de la validation PME3 (Mme Nadège Saintilus, ONG Espwa pou Ayiti).

---

## 1. Contexte

Avant v6-3, le module `:funds-grants` n'exposait qu'un DTO générique `DonorReport`
`{totalReceived, totalSpent, balanceRemaining, from, to}` — insuffisant pour les ONG
qui doivent produire des rapports trimestriels/annuels dans des formats hétérogènes
imposés par chaque bailleur institutionnel.

Pour une ONG avec 5M USD/an de budget et 4 bailleurs aux formats incompatibles
(USAID SF-425, EU PRAG, Banque Mondiale QFR, CRS interne), l'équipe finance devait
reconstruire chaque rapport à la main dans Excel — le SaaS n'économisait pas de temps.

v6-3 introduit :

1. Une table `donor_report_line` qui matérialise, par (grant, année, trimestre,
   cost_category), les montants budget / actual / cost_share.
2. Un service `DonorReportExporter` qui agrège ces lignes en CSV structurés conformes
   aux formats bailleurs.
3. Trois endpoints REST d'export.

## 2. Formats supportés

### 2.1. USAID SF-425 (Federal Financial Report) — trimestriel

Endpoint :
```
GET /api/v1/companies/{companyId}/funds-grants/grants/{grantId}/donor-reports/usaid-sf425?year=2026&quarter=1
```

Format CSV produit (extrait) :
```
USAID SF-425 Federal Financial Report
Grant ID;0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd
Grant Code;USAID-2026-WASH
Reporting Period;Q1 FY2026
Recipient Name;Espwa pou Ayiti
Currency;USD

SECTION A - Status of Federal Funding
Line 10a. Total Federal funds authorized;500000.00
Line 10b. Federal funds authorized for this period;125000.00
Line 10c. Total Federal funds drawn;31000.00
Line 10d. Federal share of expenditures;31000.00
Line 10e. Federal share of unliquidated obligations;0.00
Line 10f. Total Federal share (sum of 10d + 10e);31000.00
Line 10g. Unobligated balance of Federal funds;94000.00
Line 10h. Recipient share;5000.00
Line 10i. Total recipient share;5000.00

SECTION B - Expenditures by Cost Category
Cost Category;Budget;Actual;Variance
PERSONNEL;50000.00;12500.00;37500.00
FRINGE;15000.00;3750.00;11250.00
TRAVEL;8000.00;2000.00;6000.00
EQUIPMENT;20000.00;0.00;20000.00
SUPPLIES;12000.00;3500.00;8500.00
CONTRACTUAL;10000.00;5000.00;5000.00
OTHER;5000.00;2000.00;3000.00
INDIRECT_COST;5000.00;2250.00;2750.00
TOTAL;125000.00;31000.00;94000.00
```

Référence : 2 CFR §200.302 — Uniform Administrative Requirements, Cost Principles, and
Audit Requirements for Federal Awards.

### 2.2. EU PRAG (Annual Financial Report) — annuel

Endpoint :
```
GET /api/v1/companies/{companyId}/funds-grants/grants/{grantId}/donor-reports/eu-prag?year=2026
```

Format CSV produit (extrait) :
```
EU PRAG - Annual Financial Report
Grant Agreement;EU-2026-HEALTH
Grant Label;Programme santé communautaire
Beneficiary;Espwa pou Ayiti
Reporting Period;FY2026
Currency;EUR
Total Grant Amount;800000.00

Expenditures by Cost Category
Cost Category;Budget;Actual;Variance;% of Total Actual
PERSONNEL;300000.00;75000.00;225000.00;50.00
FRINGE;90000.00;22500.00;67500.00;15.00
TRAVEL;50000.00;12500.00;37500.00;8.33
EQUIPMENT;120000.00;30000.00;90000.00;20.00
SUPPLIES;70000.00;7500.00;62500.00;5.00
CONTRACTUAL;50000.00;0.00;50000.00;0.00
OTHER;10000.00;2500.00;7500.00;1.67
INDIRECT_COST;10000.00;0.00;10000.00;0.00
TOTAL;700000.00;150000.00;550000.00;100.00

Co-financing (cost share);30000.00
Total eligible expenditures;150000.00
EU contribution;120000.00
Co-financing rate (derived);80.00
```

Référence : PRAG — Practical Guide to contract procedures for EU external actions,
Annex F2 — Financial Report Template.

### 2.3. Banque Mondiale (Quarterly Financial Report) — trimestriel

Endpoint :
```
GET /api/v1/companies/{companyId}/funds-grants/grants/{grantId}/donor-reports/world-bank?year=2026&quarter=1
```

Format CSV produit (extrait) :
```
World Bank - Quarterly Financial Report
Grant No;BM-2026-EDUC
Project Name;Programme éducation rurale
Borrower/Recipient;Espwa pou Ayiti
Reporting Period;Q1 FY2026
Currency;USD

SECTION A - Withdrawal Applications
Total grant amount;1200000.00
Total cumulative withdrawals (actual);85000.00
Unliquidated balance (variance);215000.00
Borrower contribution (cost share);15000.00

SECTION B - Expenditures by Category
Category;Budget;Actual;Variance
Personnel;150000.00;40000.00;110000.00
Fringe Benefits;45000.00;12000.00;33000.00
Travel;30000.00;5000.00;25000.00
Equipment;100000.00;15000.00;85000.00
Supplies;40000.00;8000.00;32000.00
Contractual Services;50000.00;5000.00;45000.00
Other Direct Costs;10000.00;0.00;10000.00
Overhead/Indirect Costs;20000.00;0.00;20000.00
Contingencies;0.00;0.00;0.00
TOTAL;300000.00;85000.00;215000.00
```

Référence : World Bank Disbursement Guidelines for Investment Project Financing,
Annex 3 — Interim Financial Report (IFR) template.

## 3. Cost categories standardisées

L'énumération `CostCategory` (paquetage `jo.accountant.fundsgrants.entity`) définit 8
catégories alignées sur les formats bailleurs :

| Énumération Java | USAID SF-425 | EU PRAG | Banque Mondiale |
|---|---|---|---|
| `PERSONNEL` | Personnel | Staff costs | Personnel |
| `FRINGE` | Fringe Benefits | Social charges | Fringe Benefits |
| `TRAVEL` | Travel | Travel | Travel |
| `EQUIPMENT` | Equipment | Equipment | Equipment |
| `SUPPLIES` | Supplies | Consumables | Supplies |
| `CONTRACTUAL` | Contractual | Subcontracting | Contractual Services |
| `OTHER` | Other | Other direct costs | Other Direct Costs |
| `INDIRECT_COST` | Indirect Charges | Indirect costs (NIHA) | Overhead/Indirect Costs |

La catégorie `CONTINGENCIES` (spécifique Banque Mondiale) n'a pas d'énumération dédiée
en v6-3 — elle est émise comme une ligne séparée à `0.00` dans l'export BM. En v7, une
énumération `CONTINGENCIES` pourra être ajoutée via migration complémentaire si des
lignes contingencies réelles doivent être tracées.

## 4. Limitations actuelles (v6-3)

### 4.1. Squelette — alimentation en v7

La table `donor_report_line` existe mais **n'est pas encore alimentée**. En l'absence
de lignes, les exports retournent un CSV valide structurellement avec des zéros partout.
Ce comportement est volontaire : il permet aux équipes finance de **valider le format**
auprès des bailleurs dès maintenant, sans attendre l'alimentation automatique.

L'alimentation réelle sera faite en **v7** via un job de ventilation post-écriture :

1. Les écritures comptables (JournalLine) porteront un tag analytique `grant_id` et
   un tag `cost_category` (extension du modèle analytique existant).
2. Un job périodique (cron ou déclenché à la saisie) projettera ces écritures dans
   `donor_report_line` en agrégeant par (grant, year, quarter, cost_category).
3. Les montants `budget_amount` seront saisis manuellement par l'équipe finance au
   début de la période (formulaire dédié à ajouter en v7) ou importés depuis le
   budget initial du grant.

### 4.2. Cofinancing rate EU PRAG

Le taux de cofinancement EU PRAG est actuellement **dérivé** des montants constatés :
`cofinancing_rate = (actual_total − cost_share_total) / actual_total`.

En v7, ce taux sera configurable par grant (colonne dédiée dans `fg_grant`) car il est
défini contractuellement dans l'accord de subvention EU (généralement 80% EU / 20%
co-financing, ou 90% / 10% selon le programme).

### 4.3. NIHA USAID (indirect cost rate)

Le taux de Negotiated Indirect Cost Rate Agreement (NICRA) pour USAID n'est pas encore
configurable par grant — il est implicitement dérivé du `budget_amount` saisi pour la
catégorie `INDIRECT_COST`. En v7, une colonne `nicra_rate` sera ajoutée à `fg_grant`
pour calculer automatiquement le budget indirect en pourcentage du direct.

### 4.4. Cumul YTD vs trimestre

Les exports trimestriels (USAID SF-425, BM) filtrent les `donor_report_line` par
`period_quarter == quarter`. En v7, un mode `cumulative=true` pourra être ajouté pour
produire des rapports Year-To-Date cumulés (lignes jusqu'au quarter N inclus).

### 4.5. Format binaire (PDF, XLSX)

Seul le format CSV est supporté en v6-3. Le PDF et XLSX seront ajoutés en v7 via le
module `:document-generation` (openhtmltopdf pour PDF, Apache POI pour XLSX).

### 4.6. Catégories BM supplémentaires

La Banque Mondiale prévoit parfois des catégories supplémentaires : `Training`,
`Consultant Services`, `Operating Costs`. Elles ne sont pas couvertes en v6-3 — elles
seront mappées via `OTHER` ou via de nouvelles énumérations en v7 selon les besoins
remontés par les utilisateurs.

## 5. Procédure de génération

### 5.1. Trimestrielle (USAID SF-425, Banque Mondiale)

1. En fin de trimestre (T1 = jan-mar, T2 = avr-juin, T3 = juil-sep, T4 = oct-déc),
   l'équipe finance s'assure que toutes les écritures de dépenses du trimestre sont
   saisies et taguées analytiquement (grant + cost_category).
2. Le job v7 (à implémenter) projette ces écritures dans `donor_report_line`.
3. Le comptable compare `actual_amount` aux reçus et factures, ajuste si nécessaire.
4. L'équipe finance déclenche l'export via l'endpoint REST :
   ```
   curl -X GET \
     "https://app.joaccountant.ht/api/v1/companies/{companyId}/funds-grants/grants/{grantId}/donor-reports/usaid-sf425?year=2026&quarter=1" \
     -H "Authorization: Bearer {token}" \
     -o usaid-sf425_Q1-FY2026.csv
   ```
5. Le CSV est ouvert dans Excel (BOM UTF-8 → accents corrects), relu et signé par le
   directeur financier, puis soumis au bailleur via son portail (USAID Phoenix,
   World Bank Client Connection, etc.).

### 5.2. Annuelle (EU PRAG)

1. À la clôture de l'exercice, l'équipe finance s'assure que toutes les écritures de
   l'année sont saisies.
2. Le job v7 projette les écritures annuelles dans `donor_report_line` avec
   `period_quarter = NULL`.
3. Le comptable ajuste les montants `budget_amount` (budget initial EU) si nécessaire.
4. L'équipe finance déclenche l'export :
   ```
   curl -X GET \
     "https://app.joaccountant.ht/api/v1/companies/{companyId}/funds-grants/grants/{grantId}/donor-reports/eu-prag?year=2026" \
     -H "Authorization: Bearer {token}" \
     -o eu-prag_FY2026.csv
   ```
5. Le CSV est relu, signé, et soumis à l'EUD (European Union Delegation) via l'outil
   PADOR / e-Procedures.

## 6. Exemple de payload API

### 6.1. Export USAID SF-425

**Requête** :
```
GET /api/v1/companies/0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd/funds-grants/grants/0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd/donor-reports/usaid-sf425?year=2026&quarter=1
Authorization: Bearer eyJhbGciOi...
Accept: text/csv
```

**Réponse** :
```
HTTP/1.1 200 OK
Content-Type: text/csv; charset=UTF-8
Content-Disposition: attachment; filename="usaid-sf425_0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd_Q1-FY2026.csv"
Content-Length: 1842

<UTF-8 BOM>USAID SF-425 Federal Financial Report
Grant ID;0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd
...
```

### 6.2. Rôles requis

| Endpoint | Rôle minimum | Module requis |
|---|---|---|
| `GET .../usaid-sf425` | `VIEWER` | `FUNDS_GRANTS` |
| `GET .../eu-prag` | `VIEWER` | `FUNDS_GRANTS` |
| `GET .../world-bank` | `VIEWER` | `FUNDS_GRANTS` |

L'accès est refusé (HTTP 403) si :
- Le rôle de l'utilisateur est inférieur à VIEWER (ex: utilisateur désactivé)
- Le module `FUNDS_GRANTS` n'est pas activé pour le `business_type_code` de l'entreprise
- L'utilisateur n'appartient pas à la compagnie `{companyId}` (tenant mismatch)

### 6.3. Codes d'erreur

| Code | Cause |
|---|---|
| `404 Grant/{grantId}` | Subvention introuvable OU n'appartenant pas au tenant `companyId` |
| `403 Forbidden` | Rôle insuffisant ou module désactivé |
| `400 Bad Request` | `quarter` hors plage 1-4 |
| `200 OK` | Export réussi — CSV renvoyé en body |

## 7. Modèle de données

```sql
-- Table : donor_report_line (V69)
-- Une ligne = (company, grant, year, quarter?, cost_category)
-- variance_amount est GENERATED ALWAYS AS STORED — jamais écrite directement.

donor_report_line
├── id              UUID PK
├── company_id      UUID NOT NULL     -- tenant (RLS)
├── grant_id        UUID NOT NULL     -- FK vers fg_grant
├── donor_type      VARCHAR(20)       -- USAID | EU | WORLD_BANK | CRS | OTHER
├── period_year     INT NOT NULL      -- ex: 2026
├── period_quarter  INT               -- 1-4 ou NULL pour annuel
├── cost_category   VARCHAR(50)       -- PERSONNEL | FRINGE | ... | INDIRECT_COST
├── budget_amount   NUMERIC(19,4)     -- saisi par l'équipe finance
├── actual_amount   NUMERIC(19,4)     -- alimenté par le job v7 (depuis JournalLine)
├── variance_amount NUMERIC(19,4)     -- GENERATED = budget - actual
├── cost_share_amount NUMERIC(19,4)   -- participation ONG (match funding)
├── description     VARCHAR(500)
└── version, created_at, updated_at, created_by, updated_by
```

## 8. Roadmap v7

| Item | Priorité | Description |
|---|---|---|
| Alimentation automatique | Haute | Job post-écriture projetant JournalLine → donor_report_line via tags analytiques |
| Saisie budget par catégorie | Haute | Formulaire de saisie des `budget_amount` par (grant, year, quarter, cost_category) |
| Config cofinancing rate EU | Moyenne | Colonne `cofinancing_rate` dans `fg_grant` |
| Config NIHA USAID | Moyenne | Colonne `nicra_rate` dans `fg_grant` |
| Mode YTD cumulé | Moyenne | Paramètre `?cumulative=true` pour USAID/BM |
| Export PDF | Moyenne | Via `:document-generation` (openhtmltopdf) |
| Export XLSX | Moyenne | Via `:document-generation` (Apache POI) |
| Catégories BM étendues | Basse | `Training`, `Consultant Services`, `Operating Costs` |
| Format CRS | Basse | Export spécifique Catholic Relief Services |
| Format AFD | Basse | Agence Française de Développement — bilan + compte de résultat analytique |

## 9. Références

- **USAID SF-425** : 2 CFR §200.302 — Uniform Administrative Requirements, Cost
  Principles, and Audit Requirements for Federal Awards. Form SF-425 (OMB No.
  4040-0014).
- **EU PRAG** : Practical Guide to contract procedures for EU external actions
  (PRAG) — Annex F2. https://wikis.ec.europa.eu/display/ExactAwards/PRAG
- **Banque Mondiale** : Disbursement Guidelines for Investment Project Financing —
  Annex 3 (Interim Financial Report).
- **Validation PME3** : Mme Nadège Saintilus, ONG Espwa pou Ayiti (Haïti), 2026.
