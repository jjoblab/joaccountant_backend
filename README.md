# JOAccountant v5.2 — Backend

Multi-tenant, multi-secteur, multi-référentiel SaaS comptable backend (Spring Boot 3.5 / Java 17 / PostgreSQL 14+ / Flyway).

> **État du projet (2026-07-28)** : **30 modules Gradle**, **190 endpoints REST** sur **32 contrôleurs**,
> **6 référentiels comptables** supportés, **13 types métier** actifs (extensible sans redéploiement),
> **63 migrations Flyway** (V0_000 → V52), **310 tests** d'intégration (31 fichiers de test) — couverture large
> multi-référentiels et multi-secteurs. **3 scripts de seed** (retail commerce, wholesale B2B,
> professional services) couvrent l'intégralité du cycle d'exploitation end-to-end avec bilan
> équilibré et exports PDF/CSV des 15 statements pour chaque exercice fiscal. Application mobile
> livrée (APK 21.7 MB, 16 feature modules).
>
> Toutes les phases de développement sont **complétées** — voir la section
> [Phases réalisées](#phases-réalisées) ci-dessous pour le suivi détaillé.
>
> **Changements récents (v4.7 → v5.2 — sessions 18 à 26)** :
> - **MFA TOTP** (RFC 6238) — 2-step login, codes de récupération, JWKS endpoint RFC 7517
>   (RS256). MFA obligatoire pour OWNER/ADMIN (audit v4.7 §6.3 — NIST 800-63B AAL2).
> - **Factur-X** (Cross Industry Invoice D16B, profil BASICWL, EN 16931) — conformité Loi
>   2023-314 (facturation électronique B2B France).
> - **IAS 16/36** — composants d'immobilisation + test de dépréciation (V47).
> - **TVA encaissement** (`VatMode.ENCAISSEMENT`, V44 — art. 289 II CGI) + **reverse charge**
>   (autoliquidation intra-UE B2B, V45 — art. 283 2 nonies CGI).
> - **Purchase Orders + 3-way match** (V48) — commande ↔ facture fournisseur (quantité, prix).
> - **Spring Batch** (V52) — `payrollJob` et `fiscalYearClosingJob` lancés via `BatchController`.
> - **PostgreSQL RLS** (V51) — Row Level Security sur 6 tables financières (defense in depth,
>   en plus du filtre JWT claim `TenantContextFilter`).
> - **`ContributionRule` + `PayrollCalculator`** (V40) — cotisations par tranches (PMSS, CSG
>   abattue, Tranche A/B), fallback sur `WithholdingRule` si aucune règle configurée.
> - **Barème progressif** pour `WithholdingRule` (V46 — `bracketType=PROGRESSIVE` + `brackets`).
> - **Heures sup + absences** sur `Employee` (V49 — `overtimeHours25/50`, `absenceDays`,
>   `paidLeaveDays`) consommées par `PayrollCalculator`.
> - **Champs légaux société / tiers** SIRET/VAT/NIF (V42) — exposes via `PATCH /companies/{id}/legal`
>   et `ThirdPartyResponse`, alimentent le Factur-X.
> - **Plafonds par catégorie de notes de frais** (V43 — `ExpenseCategoryController`).
> - **Cash Flow Statement** (IAS 7 / SYSCOHADA TAFIRE) — `GET /financial-statements/cash-flow-statement`.
> - **Tax Declaration Schedule** (CA3, DES, EFI) — `GET /tax/declaration-schedule?year=` +
>   `GET /tax/declarations/export?format=ca3|des|efi`.
> - **Snapshots de clôture idempotents** — `POST /financial-statements/snapshots/closing/periods/{periodId}`.
> - **Trigger DB statement-level** (V36) — `trg_journal_entry_balance_*` (3 triggers INSERT/UPDATE/DELETE
>   avec transition tables, complexité O(N) au lieu de O(N²)).
> - **Pagination** sur `GET /invoices`, `GET /purchase-invoices`, `GET /expense-reports`,
>   `GET /third-parties`, `GET /notifications`, `GET /journal-entries/paged|search` (Finding #3).

---

## Sommaire

- [Layout du projet](#layout-du-projet)
- [Modules](#modules)
- [Référentiels comptables supportés](#référentiels-comptables-supportés)
- [Types métier supportés](#types-métier-supportés)
- [Scripts de seed](#scripts-de-seed)
- [Build & test](#build--test)
- [API documentation](#api-documentation)
- [Configuration](#configuration)
- [Phases réalisées](#phases-réalisées)
- [Audit comptable — synthèse des corrections](#audit-comptable--synthèse-des-corrections)
- [Ce qui n'est PAS dans le projet](#ce-qui-nest-pas-dans-le-projet)

---

## Layout du projet

Le dépôt est organisé en **30 modules Gradle** (27 modules métier + 1 module utilitaire de test +
1 module d'application + 1 module `:purchase-orders`). Chaque module possède sa propre
documentation détaillée dans son répertoire — voir la section [Modules](#modules) ci-dessous.

```
joaccountant/
├── settings.gradle.kts          — multi-module Gradle (Kotlin DSL), 30 modules inclus
├── build.gradle.kts             — root: Java 17, Spring Boot 3.5 BOM, shared deps,
│                                  JUnit Platform alignment, Zonky embedded-postgres,
│                                  ArchUnit
├── seed_commerce.py             — seed RETAIL_COMMERCE (boutique détail, ~1568 lignes)
├── seed_commerce_wholesale.py   — seed WHOLESALE_COMMERCE (grossiste B2B, ~1543 lignes)
├── seed_service.py              — seed PROFESSIONAL_SERVICES (cabinet conseil, ~1465 lignes)
│
├── core/                        — socle : TenantAwareEntity, TenantContext, exceptions,
│                                  ProblemDetail (RFC 7807), AccountingFramework + Currency
│                                  seeds, ports (FileStoragePort, NotificationChannelPort)
├── audit-trail/                 — AuditLog + AuditEventListener (async, AFTER_COMMIT) —
│                                  l'unique entité NON TenantAwareEntity (survit à la
│                                  suppression d'un tenant)
├── auth/                        — User, RefreshToken (rotation), PasswordResetToken
│                                  (single-use), UserCompanyRole, JWT HS256, Argon2id,
│                                  password validator (OWASP)
├── company/                     — Company (le tenant), CompanyModule, wizard 1-9,
│                                  BusinessType + BusinessTypeModule +
│                                  BusinessTypeRequiredField (catalogue de types métier),
│                                  ModuleAccessGuard (enforcement de l'activation),
│                                  MaxCompaniesGuard (default 3)
├── document-numbering/          — DocumentSequenceConfig + Counter, atomic upsert via
│                                  ON CONFLICT DO UPDATE, numérotation sans trou
├── chart-of-accounts/           — Account (hiérarchie 4 niveaux), AccountNumberingTemplate
│                                  (FREE frameworks IFRS), AccountBalanceGuard interface,
│                                  inferReportingClass multi-référentiel (audit B3 corrigé),
│                                  SectorAccountTemplate (plan comptable contextuel par
│                                  business type — restructuration 2026-07-24)
├── approval-workflow/           — ApprovalRule + ApprovalRequest, "quatre yeux"
│                                  maker-checker, ApprovalDecidedEvent (consommé par
│                                  accounting-engine pour PENDING_APPROVAL → POSTED — audit B2)
├── analytics/                   — AnalyticalDimensionPlan + Value, mécanisme générique
│                                  multi-secteur (utilisé par funds-grants pour ONG)
├── accounting-engine/           — FiscalYear/Period, Journal, JournalEntry (DRAFT/
│                                  PENDING_APPROVAL/POSTED/VOIDED), JournalLine + tags
│                                  analytiques, AccountBalanceGuard impl (JournalBased),
│                                  trigger DB équilibre débit/crédit, grand livre, balance,
│                                  clôture d'exercice multi-référentiel (audit B1 corrigé),
│                                  listener ApprovalDecidedEvent (audit B2 corrigé),
│                                  resolveFiscalYear (per-request, v4.1 stabilisation)
├── financial-statements/        — BalanceSheet, IncomeStatement, FinancialStatementSnapshot
│                                  (figé à la clôture), agrégation par ReportingClass —
│                                  référentiel-agnostique
├── third-parties/               — ThirdParty (clients/fournisseurs/donateurs/employés),
│                                  auto-génération compte dédié, LettrageMatch (FULL/PARTIAL),
│                                  relevé de compte
├── fixed-assets/                — Asset + DepreciationScheduleLine, échéancier auto-généré,
│                                  amortissement STRAIGHT_LINE / DECLINING_BALANCE,
│                                  cession avec plus/moins-value, validateAccount sémantique
│                                  (audit M9 corrigé)
├── inventory/                   — Item, StockMove (IN/OUT/TRANSFER), Warehouse,
│                                  valorisation FIFO/AVG, écriture COGS automatique,
│                                  validateAccount sémantique (audit M9 corrigé)
├── time-billing/                — TimesheetEntry, Project, BillableRate, WIP unbilled,
│                                  utilization endpoint (secteur SERVICE — Phase 10)
├── document-generation/         — génération PDF (OpenPDF), templates bilan / CR / facture /
│                                  rapport bailleur / bulletin de paie — utilisé par
│                                  :invoicing, :reporting, :payroll
├── invoicing/                   — SalesInvoice + InvoiceLine, DRAFT → ISSUED → PAID/VOID,
│                                  écriture automatique (Débit Client / Crédit Ventes + TVA),
│                                  résolution comptes référentiel-agnostique (audit B4 corrigé)
├── bank-reconciliation/         — BankStatement + BankStatementLine, auto-match et
│                                  manual-match sur JournalLine, lettrage bancaire
├── funds-grants/                — Grant, DonationReceipt, ExpenseTaggedToGrant,
│                                  rapport bailleur (secteur ONG)
├── notifications/               — NotificationChannelPort (logging adapter par défaut),
│                                  NotificationLog, dispatcher async
├── tax/                         — TaxRule (TVA collectée / déductible), WithholdingRule
│                                  (retenues salariales — consommé par :payroll)
├── reporting/                   — exports PDF/CSV (15 statements : bilan, CR, grand livre,
│                                  balance, rapport bailleur, tax_declaration, purchase_register,
│                                  expense_register, payroll_summary, inventory_valuation,
│                                  stock_movement_register, time_billing_utilization,
│                                  fixed_assets_register, fx_operations_register,
│                                  aged_balance_suppliers), Dashboard multi-référentiel
│                                  (audit M4 corrigé), balances âgées clients/fournisseurs,
│                                  gating par module activé
├── purchasing/                  — PurchaseInvoice + PurchaseInvoiceLine, DRAFT → RECEIVED →
│                                  PAID/VOID, écriture auto (Débit Achats + TVA déductible /
│                                  Crédit Fournisseur), résolution comptes référentiel-
│                                  agnostique (restructuration 2026-07-24 — module bonus)
├── expenses/                    — ExpenseReport + ExpenseLine, DRAFT → SUBMITTED → APPROVED
│                                  → PAID (ou REJECTED), écriture auto (Débit Charges /
│                                  Crédit Tiers-Employé ou Trésorerie selon paidDirectly)
│                                  (restructuration 2026-07-24 — module bonus, always-on)
├── employees/                   — Employee (rattaché à ThirdParty EMPLOYEE), contrat, statut,
│                                  salaire de base, numéro de compte bancaire. Aucune écriture
│                                  comptable (restructuration 2026-07-24 — module bonus,
│                                  always-on, consommé par :payroll)
├── payroll/                     — PayrollRun + Payslip, calcul brut→net via :tax
│                                  WithholdingRule, écriture consolidée à l'approbation
│                                  (Débit Charges de personnel / Crédit Salaires à payer /
│                                  Crédit Organismes sociaux / Crédit État), bulletin PDF
│                                  via :document-generation (restructuration 2026-07-24 —
│                                  module bonus, always-on)
├── fx-operations/               — FxOperation (BUY/SELL/REVALUATION), ExchangeRate, écriture
│                                  auto avec gain/perte de change (restructuration 2026-07-25 —
│                                  suite 3, sectoriel, gating `FX_OPERATIONS` posé en suite 4 §3)
├── purchase-orders/             — PurchaseOrder + PurchaseOrderLine, 3-way match (commande ↔
│                                  facture fournisseur) — Finding #10 (V48). Ne génère pas
│                                  d'écriture au MVP. Dépend de :purchasing pour la facture
├── test-support/                — shared EmbeddedPostgresSupport (évite :test -> :app circular
│                                  dep) — module utilitaire, pas un module métier
└── app/                         — @SpringBootApplication bootstrap, SecurityConfig (stateless
                                  JWT), OpenAPI config, Actuator on port 8081, tous les tests
                                  d'intégration globaux
```

---

## Modules

Chaque module possède sa propre documentation détaillée (rôle, entités, règles métier, cycle de
vie, endpoints, relations avec les autres modules, migrations Flyway, points d'attention
hérités de l'audit) dans son répertoire.

| Module | Rôle |
|---|---|
| `core` | Socle technique : `TenantAwareEntity`, `TenantContext`, exceptions, `ProblemDetail` (RFC 7807), `AccountingFramework` + `Currency` seeds, ports |
| `audit-trail` | `AuditLog` + listener async — l'unique entité non tenant-aware (survit à la suppression d'un tenant) |
| `auth` | `User`, JWT HS256, Argon2id, refresh token rotatif, password reset anti-énumération, `UserCompanyRole` |
| `company` | `Company` (le tenant), wizard 1-9, `BusinessType` + `BusinessTypeModule` (catalogue piloté par données), `ModuleAccessGuard` (enforcement 8 modules sectoriels), `MaxCompaniesGuard` |
| `document-numbering` | Numérotation sans trou, atomic upsert via `ON CONFLICT DO UPDATE` |
| `chart-of-accounts` | `Account` (hiérarchie 4 niveaux), `inferReportingClass` multi-référentiel (audit B3), `SectorAccountTemplate` (plan comptable contextuel par business type) |
| `approval-workflow` | `ApprovalRule` + `ApprovalRequest`, "quatre yeux" maker-checker, `ApprovalDecidedEvent` |
| `analytics` | `AnalyticalDimensionPlan` + `Value`, mécanisme générique multi-secteur (utilisé par ONG) |
| `accounting-engine` | `JournalEntry` (DRAFT/PENDING_APPROVAL/POSTED/VOIDED), `JournalLine`, trigger DB équilibre, clôture d'exercice multi-référentiel (audits B1, B2, M2), `resolveFiscalYear` per-request (v4.1) |
| `financial-statements` | Bilan, compte de résultat, snapshots figés — agrégation par `ReportingClass` (référentiel-agnostique) |
| `third-parties` | `ThirdParty` (clients/fournisseurs/donateurs), auto-génération compte dédié, lettrage |
| `fixed-assets` | `Asset` + `DepreciationScheduleLine`, amortissement, cession, `validateAccount` sémantique (audit M9) |
| `inventory` | `Item`, `StockMove`, valorisation FIFO/AVG, écriture COGS, `validateAccount` sémantique (audit M9) |
| `time-billing` | `TimesheetEntry`, `Project`, `BillableRate`, WIP unbilled, `utilization` endpoint (secteur SERVICE) |
| `document-generation` | Génération PDF (OpenPDF), templates bilan / CR / facture / rapport bailleur / bulletin de paie |
| `invoicing` | `SalesInvoice` + `InvoiceLine`, écriture auto Débit Client / Crédit Ventes + TVA (audit B4) |
| `bank-reconciliation` | `BankStatement` + `BankStatementLine`, auto-match et manual-match |
| `funds-grants` | `Grant`, `DonationReceipt`, rapport bailleur (secteur ONG) |
| `notifications` | `NotificationChannelPort` (logging adapter par défaut), `NotificationLog` |
| `tax` | `TaxRule` (TVA collectée / déductible), `WithholdingRule` (retenues salariales — consommé par :payroll) |
| `reporting` | 15 exports PDF/CSV, `Dashboard` multi-référentiel (audit M4), balances âgées clients/fournisseurs, gating par module |
| `purchasing` | `PurchaseInvoice` + `PurchaseInvoiceLine`, écriture auto Débit Achats + TVA déductible / Crédit Fournisseur (restructuration 2026-07-24 — module bonus, sectoriel) |
| `expenses` | `ExpenseReport` + `ExpenseLine`, écriture auto Débit Charges / Crédit Tiers-Employé ou Trésorerie (restructuration 2026-07-24 — module bonus, always-on) |
| `employees` | `Employee` (rattaché à `ThirdParty` EMPLOYEE), contrat, salaire, statut. Aucune écriture comptable (restructuration 2026-07-24 — module bonus, always-on, consommé par :payroll) |
| `payroll` | `PayrollRun` + `Payslip`, calcul brut→net via `:tax` `WithholdingRule`, écriture consolidée à l'approbation, bulletin PDF via `:document-generation` (restructuration 2026-07-24 — module bonus, always-on) |
| `fx-operations` | `FxOperation` (BUY/SELL/REVALUATION), `ExchangeRate`, écriture auto avec gain/perte de change (restructuration 2026-07-25 — suite 3, sectoriel) |
| `purchase-orders` | `PurchaseOrder` + `PurchaseOrderLine`, 3-way match (commande ↔ facture fournisseur) — Finding #10 (V48). Ne génère pas d'écriture au MVP |
| `app` | `@SpringBootApplication` bootstrap, `SecurityConfig` (HS256/RS256, MFA, JWKS), `BatchController` (Spring Batch), OpenTelemetry, tests d'intégration |

---

## Référentiels comptables supportés

Le plan comptable d'une entreprise est initialisé depuis l'un des 6 référentiels suivants
(voir `core/.../framework/AccountingFramework.java` et la seed `V1_002__core_seeds.sql`) :

| Code | Mode | Classes niveau 1 (dans l'ordre du seed) |
|---|---|---|
| `IFRS_FULL` | FREE | générées par gabarit : 1 Actif, 2 Passif, 3 Capitaux propres, 4 Produits, 5 Charges |
| `IFRS_SME` | FREE | idem |
| `SYSCOHADA_REVISED` | MANDATED | 1 Ressources durables, 2 Actifs immobilisés, 3 Stocks, 4 Tiers, 5 Trésorerie, 6 Charges, 7 Produits, 8 HAO |
| `PCG_FRANCE` | MANDATED | 1 Capitaux, 2 Immobilisations, 3 Stocks, 4 Tiers, 5 Financiers, 6 Charges, 7 Produits |
| `PCN_HAITI` | MANDATED | 1 Capitaux, 2 Immobilisations, 3 Stocks, 4 Tiers, 5 Financiers, 6 Charges, 7 Produits, 8 Spéciaux |
| `PCGR_CANADA` | MANDATED | 1 Actif CT, 2 Actif LT, 3 Dettes CT, 4 Dettes LT, 5 Avoir des actionnaires, **6 Produits, 7 Charges**, 8 Impôts |

Chaque compte porte une classification **universelle et référentiel-agnostique** :
`ReportingClass` (ACTIF / PASSIF / CAPITAUX_PROPRES / PRODUITS / CHARGES),
`ReportingSubcategory` (COURANT / NON_COURANT / N_A), `NormalBalance` (DEBIT / CREDIT).
Ce sont **uniquement** ces classifications qui sont consommées par `financial-statements`
et `reporting` pour produire bilan, compte de résultat, tableaux de bord, etc. — jamais le
nom du référentiel.

> **Audit B3 (corrigé)** : la méthode `ChartOfAccountsService.inferReportingClass` ignore
> désormais correctement le paramètre `framework` pour produire un mapping spécialisé pour
> `PCGR_CANADA` (dont la structure Produits/Charges est inversée par rapport aux autres
> référentiels).

---

## Types métier supportés

L'activation des modules sectoriels est **pilotée par données** — voir
`company/.../mapping/BusinessTypeModuleService.java` qui lit la table `business_type_module`.
Le catalogue est seedé en `V3_003__business_type.sql`, étendu en
`V23__business_type_catalog_expansion.sql` (3 types COMMERCE) puis en
`V34__business_type_catalog_expansion_service.sql` (3 types SERVICE). Extensible sans
redéploiement. La liste ci-dessous reflète l'état après seed complet — **13 types métier
actifs** (7 d'origine + 3 COMMERCE ajoutés en V23 + 3 SERVICE ajoutés en V34), plus `CUSTOM`
(générique, sans mapping automatique) :

| Code BusinessType | Label | Nature par défaut | Secteur par défaut | Modules sectoriels auto-activés |
|---|---|---|---|---|
| `RETAIL_COMMERCE` | Commerce de détail | `FOR_PROFIT` | `COMMERCE` | `INVENTORY`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` |
| `WHOLESALE_COMMERCE` | Commerce de gros | `FOR_PROFIT` | `COMMERCE` | `INVENTORY`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` |
| `MIXED_COMMERCE` | Commerce de gros et de détail | `FOR_PROFIT` | `COMMERCE` | `INVENTORY`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` |
| `ECOMMERCE` | Commerce électronique / vente en ligne | `FOR_PROFIT` | `COMMERCE` | `INVENTORY`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` |
| `PROFESSIONAL_SERVICES` | Services professionnels | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` |
| `IT_CONSULTING` | Conseil et services informatiques | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` |
| `CREATIVE_AGENCY` | Agence créative, marketing et communication | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` |
| `MAINTENANCE_SERVICES` | Services de maintenance et réparation | `FOR_PROFIT` | `SERVICE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` |
| `NGO_HUMANITARIAN` | ONG humanitaire | `NON_PROFIT` | `ONG_HUMANITAIRE` | `FUNDS_GRANTS`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` |
| `ACCOUNTING_FIRM` | Cabinet d'expertise comptable | `FOR_PROFIT` | `CABINET_COMPTABLE` | `TIME_BILLING`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` |
| `SCHOOL` | École / établissement scolaire | `NON_PROFIT` | `EDUCATION` | `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING` |
| `HOSPITAL` | Hôpital / clinique | `NON_PROFIT` | `SANTE` | `INVENTORY`, `FIXED_ASSETS`, `BANK_RECONCILIATION`, `TAX`, `PURCHASING`, `FX_OPERATIONS` |
| `CUSTOM` | Personnalisé (sélection manuelle) | `FOR_PROFIT` | `AUTRE` | Sélection manuelle à l'étape 8 du wizard — remplace l'ancien secteur `MIXTE` |

**Socle always-on** (15 modules activés quel que soit le type métier) : `CHART_OF_ACCOUNTS`,
`ACCOUNTING_ENGINE`, `THIRD_PARTIES`, `INVOICING`, `DOCUMENT_NUMBERING`,
`APPROVAL_WORKFLOW`, `DOCUMENT_GENERATION`, `NOTIFICATIONS`, `AUDIT_TRAIL`,
`FINANCIAL_STATEMENTS`, `ANALYTICS`, `REPORTING`, `EMPLOYEES`, `EXPENSES`, `PAYROLL`.

### Filtre sectoriel sur le catalogue

Le endpoint `GET /api/v1/business-types` accepte un paramètre optionnel `?sector=`.
Si présent (ex. `?sector=COMMERCE` ou `?sector=SERVICE`), ne renvoie que les types métier dont
`defaultSector == sector`. Le mobile appelle ce endpoint avec le secteur choisi à l'étape 3
du wizard pour peupler l'étape 4.

### Enforcement

Les **8 modules sectoriels** ci-dessus (`INVENTORY`, `TIME_BILLING`, `FUNDS_GRANTS`, `TAX`,
`FIXED_ASSETS`, `BANK_RECONCILIATION`, `PURCHASING`, `FX_OPERATIONS`) retournent
**`403 MODULE_NOT_ENABLED`** si le module n'est pas activé pour la société. Les modules
always-on ne sont pas concernés (ils sont toujours activés). Le toggle est exposé via
`POST /api/v1/companies/{companyId}/modules/{moduleCode}/enable` et
`POST /api/v1/companies/{companyId}/modules/{moduleCode}/disable` — les modules always-on
ne peuvent pas être désactivés (erreur `MODULE_CANNOT_BE_DISABLED`).

---

## Scripts de seed

Trois scripts Python « Premium » permettent de peupler une instance backend avec un scénario
réaliste et complet, vérifiant la cohérence de bout en bout (double partie, clôture
d'exercice, bilan équilibré, exports PDF/CSV des 15 statements **pour chaque exercice fiscal**).

| Script | Type métier | Scénario | Lignes |
|---|---|---|---|
| `seed_commerce.py` | `RETAIL_COMMERCE` | Boutique de détail alimentaire — capitalisation 3M HTG + emprunt 2M, achats locaux, ventes avec marges 100-180%, salaires, FX (1 achat USD), clôture 2024 | ~1568 |
| `seed_commerce_wholesale.py` | `WHOLESALE_COMMERCE` | Grossiste importateur-distributeur — capitalisation 8M + emprunt 5M, imports USD réguliers (1 conteneur/trimestre), ventes B2B par palette (marges 12-25%), paiements 60-90j, 2 entrepôts (PAP + Cap) | ~1543 |
| `seed_service.py` | `PROFESSIONAL_SERVICES` | Cabinet conseil — capitalisation 2M + emprunt 1M, projets + feuilles de temps (time-and-materials), facturation sur compte 706, 1 client international (FX), 4 consultants | ~1465 |

### Étapes couvertes par chaque seed

Tous les seeds exécutent les 21 mêmes étapes (avec des données adaptées au type métier) :

1. Inscription + login
2. Création entreprise (wizard step 1)
3. Wizard 2-9 + complete
4. Plan comptable SYSCOHADA + seed sectoriel
5. Journaux (VT, AC, BQ, OD, DP, PA) + exercices 2024-2025 + séquences
6. TVA 10% + retenue salariale 10%
7. Tiers (clients + fournisseurs + employés)
8. Articles + entrepôt(s) — *sauf seed_service* (pas d'inventaire)
9. Capital initial + emprunt
10. Achats (locaux + imports USD pour wholesale)
11. Immobilisations + amortissements
12. Ventes + sorties de stock (COGS) — *ou feuilles de temps pour seed_service*
13. Charges mensuelles (salaires, loyer, électricité, carburant/internet/SaaS)
14. Notes de frais (DRAFT → SUBMITTED → APPROVED → PAID)
15. Campagne de paie consolidée (calculate → approve → pay)
16. Workflow d'approbation (4 yeux) + règles d'alerte
17. Opérations FX (BUY/SELL/REVALUATION)
18. Clôture d'exercice 2024
19. Vérification cohérence (balance débit = crédit, avec `?fiscalYearId=`)
20. **Exports PDF/CSV des 15 statements :reporting v4.1 pour chaque exercice fiscal (2024 + 2025)**
21. Présentation des rapports (bilan, CR, dashboard) pour chaque exercice fiscal

### Rapports par exercice fiscal

Chaque seed génère les rapports financiers pour **chaque exercice** créé (2024 et 2025) afin de
permettre la comparaison d'une année sur l'autre :

- **Bilan PDF** au 31/12/2024 ET au 31/07/2025
- **Compte de résultat PDF** pour l'exercice 2024 ET pour l'exercice 2025 (Jan→Jul)
- **Grand livre CSV** pour 2024 ET pour 2025 (Jan→Jul)
- **Déclaration fiscale CSV** pour 2024 ET pour 2025 (Jan→Jul)
- **Registre des achats CSV** couvrant 2024-2025 (filtre `from`/`to`)
- **Registre des notes de frais CSV** couvrant 2024-2025
- **Résumé de paie CSV** pour juillet 2025 (campagne consolidée)
- **Valorisation des stocks CSV** (snapshot à la date d'exécution)
- **Registre des mouvements de stock CSV** couvrant 2024-2025
- **Registre des immobilisations CSV** (snapshot à la date d'exécution)
- **Registre des opérations FX CSV** couvrant 2024-2025
- **Balance âgée fournisseurs CSV** (snapshot à la date d'exécution)
- **Balance générale CSV** (avec `?fiscalYearId=` pour filtrer par exercice)

Les endpoints `/financial-statements/balance-sheet?asOf=` et `/financial-statements/income-statement?from=&to=`
sont aussi appelés pour chaque exercice afin d'afficher dans la console les totaux Actif / Passif /
Capitaux propres / Produits / Charges / Résultat net, permettant une vérification visuelle
rapide de la cohérence.

### Usage

```bash
# Démarrer le backend
./gradlew :app:devRun

# Dans un autre terminal, lancer un seed
python3 seed_commerce.py             --base-url http://localhost:8080
python3 seed_commerce_wholesale.py   --base-url http://localhost:8080
python3 seed_service.py              --base-url http://localhost:8080

# Options
--email existing@user.ht   # réutiliser un utilisateur existant (skip register)
--no-color                 # désactiver les couleurs ANSI
```

Chaque seed affiche en fin d'exécution un récapitulatif avec les identifiants de connexion
(email + password) et le Company ID créé. Les seeds sont idempotents pour les lookups
(409 = « déjà existe » est toléré), mais créent de nouvelles entités à chaque exécution
(utilisateur + entreprise uniques par timestamp).

---

## Build & test

Requires Java 17 (`JAVA_HOME`) and Gradle 8.10+ (the wrapper is included, so just `./gradlew`).

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew build                  # compile + run all tests (embedded PostgreSQL via Zonky)
./gradlew test                   # tests only
./gradlew bootRun                # start the app on :8080 (management on :8081)
```

Tests use **Zonky embedded-postgres** — a real PostgreSQL binary spawned in-process per test
JVM, NOT H2. This honors §3.7 ("PostgreSQL real, pas H2") without requiring Docker
(unavailable in the dev environment).

### Couverture de tests

Le projet compte **310 tests d'intégration** répartis sur **31 fichiers de test** + 1 fichier
`ArchUnitTest` (41 règles d'architecture).

### Référentiels testés

| Référentiel | Fichiers de test | Couverture |
|---|---|---|
| `SYSCOHADA_REVISED` | 10 / 18 | Référentiel par défaut — couverture large |
| `PCN_HAITI` | 1 / 18 | Couverture minimale |
| `IFRS_FULL` | 1 / 18 | Uniquement test négatif |
| `IFRS_SME` | 0 / 18 | ❌ Aucun test |
| `PCG_FRANCE` | 0 / 18 | ❌ Aucun test |
| `PCGR_CANADA` | 0 / 18 | ❌ Aucun test — **trou critique** (audit B3 corrigé mais non testé) |

### Secteurs testés

| Secteur | Couverture |
|---|---|
| `COMMERCE` | testé via Phase1 + seed_commerce.py + seed_commerce_wholesale.py |
| `SERVICE` | testé via Phase1 + seed_service.py |
| `ONG` | testé via Phase1 |
| `MIXTE` | ❌ Aucun test — **trou de couverture** |

### Devises testées

| Devise | Décimales | Couverture |
|---|---|---|
| `HTG` | 2 | 100 % des tests |
| `XOF` / `XAF` | 0 | ❌ Aucun test — **problème d'arrondis non couvert** (audit M14 non corrigé) |
| `JPY` | 0 | ❌ Aucun test |
| `USD` / `EUR` / `CAD` | 2 | ❌ Aucun test (mais testés indirectement via :fx-operations) |

### ArchUnit

`ArchUnitTest` — 41 règles d'architecture (boundaries de modules, dépendances autorisées,
conventions de nommage). Inclut notamment :
- `:audit-trail` ne dépend d'aucun module métier
- `:core` ne dépend d'aucun module métier
- `:auth` ne dépend PAS de `:company` (pour casser la circularité)
- `:app` est un consommateur feuille
- Toute entité métier étend `TenantAwareEntity` (sauf exceptions sanctionnées : `AuditLog`,
  `Company`, `User`, `UserCompanyRole`, `RefreshToken`, `PasswordResetToken`)

---

## API documentation

Quand l'app tourne, Swagger UI est à `http://localhost:8080/swagger-ui.html` et l'OpenAPI 3.1
JSON à `http://localhost:8080/v3/api-docs`.

### Groupes Swagger par module (v5.2.0)

La config `OpenApiConfig` définit des `GroupedOpenApi` qui segmentent la documentation par
module — pratique pour ne visualiser qu'un périmètre à la fois. Chaque groupe est accessible
via :

- **Swagger UI** : `http://localhost:8080/swagger-ui.html?group={groupName}`
- **OpenAPI JSON** : `http://localhost:8080/v3/api-docs/{groupName}`

| Groupe | Périmètre |
|---|---|
| `auth` | Authentification + MFA + JWKS (`/api/v1/auth/**`, `/.well-known/jwks.json`) |
| `company` | Sociétés + Modules + Legal fields (`/api/v1/companies/**`, `/api/v1/business-types`) |
| `accounting` | Moteur comptable + Écritures + Exercices fiscaux |
| `invoicing` | Facturation + Factur-X + Reverse charge |
| `purchasing` | Achats + Bons de commande + 3-way match |
| `tax` | Fiscalité + TVA + Cotisations sociales |
| `payroll` | Paie + PayrollCalculator + Employés |
| `fixed-assets` | Immobilisations + IAS 16/36 |
| `financial-statements` | États financiers + Cash flow IAS 7 |
| `batch-admin` | Jobs Spring Batch (paie + clôture) |
| `reporting` | Reporting + Dashboard + Balance âgée |

Le groupe par défaut (`default`) agrège tous les endpoints.

### Tags OpenAPI

28 tags standardisés sont définis dans `OpenApiConfig` pour grouper les endpoints dans Swagger
UI : Auth, MFA, JWKS, Company, Accounting Engine, Invoicing, Purchasing, Purchase Orders,
Tax, Payroll, Employees, Fixed Assets, Expenses, Bank Reconciliation, Third Parties, Chart of
Accounts, Financial Statements, Reporting, Notifications, Approval Workflow, Audit Trail,
Document Generation, Document Numbering, Inventory, Time Billing, Funds & Grants, FX
Operations, Batch Admin.

### Exemples intégrés (v5.2.0)

Les endpoints critiques embarquent des `@ExampleObject` dans leurs `@ApiResponse` pour
illustrer les payloads de réponse — particulièrement utiles pour :

- **`POST /auth/login`** : 2 exemples (login standard + challenge MFA) + 1 exemple d'erreur
  `INVALID_CREDENTIALS` au format RFC 7807.
- **`POST /auth/login/mfa`** : exemple de succès (tokens retournés) + exemple d'erreur 401
  `MFA_INVALID_CODE`.
- **`GET /.well-known/jwks.json`** : 2 exemples (RS256 activé avec clé publique / HS256 avec
  JWKS vide).
- **`GET /invoicing/invoices/{id}/factur-x`** : exemple d'un XML CII D16B BASICWL (extrait).
- **`POST /admin/batch/payroll`** + **`POST /admin/batch/closing`** : exemples de
  `BatchJobResponse` (status COMPLETED) + cas idempotent (exitCode=ALREADY_COMPLETE pour la
  clôture déjà effectuée).
- **MFA endpoints** (`setup`, `verify`, `check`, `status`) : exemples avec secret TOTP, URL
  otpauth://, codes de récupération, etc.

Le projet compte **190 endpoints REST** sur **32 contrôleurs**. Le format d'erreur est
`ProblemDetail` (RFC 7807) — centralisé dans `GlobalExceptionHandler` (`:core`) avec
enrichissement systématique de `code`, `correlationId`, `companyId`, `timestamp`, `type`,
`instance`.

> **Audit A-4** : 48/152 endpoints ont des `@ApiResponses` complètes, 22 partielles, 82
> aucune (surtout Phase 8-17). L'équipe mobile doit gérer génériquement les `ProblemDetail`
> non documentés.

---

## Configuration

| Property | Default | Notes |
|---|---|---|
| `app.jwt.secret` | dev-only string | MUST be overridden in production (≥ 256 bits) |
| `app.jwt.access-token-ttl-seconds` | `900` (15 min) | §3.4 |
| `app.subscription.max-companies-per-user` | `3` | §12 default |
| `app.storage.fs.root` | `./build/storage` | Default filesystem FileStoragePort root |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local PG defaults | Production datasource |

---

## Phases réalisées

Le projet a été construit au fil de plusieurs lots successifs, chacun livrant un ensemble
cohérent de fonctionnalités. Toutes les phases ci-dessous sont **complétées**.

### Phase initiale (v2.1 — socle comptable)

- 17 phases couvrant le socle comptable end-to-end : auth, multi-tenant, plan comptable
  multi-référentiel, écritures, workflow 4-yeux, tiers, immobilisations, inventaire,
  facturation, rapprochement bancaire, subventions ONG, notifications, TVA, reporting,
  génération PDF.
- 7 corrections de l'audit comptable 2026-07-22 (B1, B2, B3, B4, M2, M4, M9).
- 104 endpoints REST sur 21 contrôleurs, 30 règles ArchUnit.

### Restructuration 2026-07-24 — `:company` (5 axes)

La modélisation organisationnelle s'articule désormais autour de 5 axes distincts (Nature,
Forme juridique, Secteur, Activité principale, Type métier). L'activation des modules est
pilotée par données (table `business_type_module`) plutôt que par un `switch` Java. Le type
métier `CUSTOM` remplace l'ancien secteur `MIXTE` et active réellement la sélection manuelle
de modules à l'étape 8 du wizard. L'enforcement de l'activation des modules est réel : les
modules sectoriels retournent `403 MODULE_NOT_ENABLED` si non activés.

### Restructuration 2026-07-24 (suite) — 4 nouveaux modules bonus

- `:purchasing` (factures fournisseur / achats) — sectoriel
- `:expenses` (notes de frais) — always-on
- `:employees` (RH) — always-on
- `:payroll` (paie consolidée) — always-on
- Catalogue de types métier enrichi de 3 nouvelles entrées COMMERCE (`WHOLESALE_COMMERCE`,
  `MIXED_COMMERCE`, `ECOMMERCE`) + correction du mapping `HOSPITAL → INVENTORY` (trou
  fonctionnel) + ajout uniforme de `PURCHASING` sur les 6 types métier existants.
- Filtre `GET /business-types?sector=...` pour peupler l'étape 4 du wizard à partir du
  secteur choisi à l'étape 3.

### Restructuration 2026-07-25 (suite 3 + suite 4 — stabilisation)

- **Module `:fx-operations`** (BUY/SELL/REVALUATION, écriture auto avec gain/perte de change)
- **Gating `FX_OPERATIONS`** (8ᵉ module sectoriel — activé par défaut sur les types métier
  COMMERCE, ONG_HUMANITARIAN et HOSPITAL via V33)
- **Retrait du pre-check `checkActiveFiscalYearWritable`** sur `createJournalEntry`
  (§1 stabilisation — la validation réelle se fait via `findPeriodForDate`)
- **Dépréciation de `POST /fiscal-years/{id}/activate`** et `GET /fiscal-years/active` au
  profit de `resolveFiscalYear` (contract centralisé, per-request, sans état partagé)
- **Déploiement du paramètre `?fiscalYearId=`** sur `trial-balance`, `ledger`,
  `purchase-invoices`, `expense-reports`, et défaut à 12 campagnes sur `payroll-runs`
- **Catalogue de types métier enrichi de 3 nouvelles entrées SERVICE** en V34
  (`IT_CONSULTING`, `CREATIVE_AGENCY`, `MAINTENANCE_SERVICES`) — même mapping modules
  que `PROFESSIONAL_SERVICES`

### Restructuration 2026-07-25 (suite 5 — service sector & reporting suite)

- **Plan comptable contextuel par business type** : `SectorAccountTemplate` seed les comptes
  de niveau 2+ pertinents selon le type métier (ex. pas de comptes de stock pour un cabinet
  de services, pas de comptes de subventions pour un commerce)
- **Toggle module** : `POST /companies/{id}/modules/{code}/enable` et `/disable` (les
  always-on ne peuvent pas être désactivés — `MODULE_CANNOT_BE_DISABLED`)
- **15 exports reporting** : bilan PDF, compte de résultat PDF, grand livre CSV, balance CSV,
  rapport bailleur PDF, `tax_declaration`, `purchase_register`, `expense_register`,
  `payroll_summary`, `inventory_valuation`, `stock_movement_register`,
  `time_billing_utilization`, `fixed_assets_register`, `fx_operations_register`,
  `aged_balance_suppliers`. Gating par module activé.
- **Balances âgées clients/fournisseurs** (endpoints JSON dédiés)
- **Application mobile** : 16 feature modules (auth, onboarding, accounting, chart-of-accounts,
  dashboard, financial-statements, fixed-assets, funds-grants, inventory, invoicing,
  notifications, settings, tax, third-parties, time-billing), APK 21.7 MB, alignment
  complète avec backend v4.1

### Finalisation 2026-07-26

- **3 scripts de seed** couvrent les 3 grands secteurs (RETAIL_COMMERCE, WHOLESALE_COMMERCE,
  PROFESSIONAL_SERVICES) avec cycles d'exploitation end-to-end, bilans équilibrés, et exports
  des 15 statements :reporting v4.1 **pour chaque exercice fiscal** (2024 ET 2025)
- **README principal refondu** pour refléter l'état final (27 modules, ~152 endpoints,
  13 types métier, 3 seeds, phases réalisées)

### Changements récents (v4.7 → v5.2 — sessions 18 à 26)

Les 9 sessions suivantes ont fait passer le projet de v4.7 à v5.2. Détail complet dans chaque
README module ; récapitulatif :

| Session | Migration(s) | Module(s) impacté(s) | Sujet |
|---|---|---|---|
| 18 | V36 | `accounting-engine` | Trigger DB statement-level (FOR EACH STATEMENT + transition tables) — complexité O(N) au lieu de O(N²) |
| 19 | V38 | `accounting-engine` | Index composites complémentaires (tb_timesheet_entry, account, audit_log) |
| 20 | V40 | `tax`, `payroll` | `ContributionRule` + `PayrollCalculator` (cotisations par tranches PMSS / CSG abattue / Tranche A/B) |
| 21 | V41 | `auth` | MFA TOTP RFC 6238 — `MfaSecret` AES-256-GCM, codes de récupération, 2-step login (challenge token) |
| 22 | V42 | `company`, `third-parties` | Champs légaux SIRET/VAT/NIF/address sur Company et ThirdParty — alimentent le Factur-X et les mentions légales (CGI art. 289) |
| 22 | V43 | `expenses` | `ExpenseCategory` + plafonds journaliers/mensuels (CRUD via `ExpenseCategoryController`) |
| 22 | V44 | `tax`, `invoicing` | TVA sur encaissement (`VatMode.DEBIT`/`ENCAISSEMENT`, art. 289 II CGI) — compte 4438 |
| 22 | V45 | `invoicing` | Reverse charge / autoliquidation intra-UE B2B (`is_reverse_charge`, art. 283 2 nonies CGI) — compte 447 |
| 22 | V46 | `tax` | Barème progressif pour `WithholdingRule` (`bracketType=PROGRESSIVE` + `brackets`) |
| 23 | V47 | `fixed-assets` | IAS 16 (composants) + IAS 36 (test de dépréciation) — `AssetComponent`, `testImpairment` |
| 23 | V48 | `purchase-orders`, `purchasing` | Module `:purchase-orders` + 3-way match (commande ↔ facture) — Finding #10 |
| 24 | V49 | `employees` | Heures sup (+25%/+50%) + absences + congés payés sur `Employee` (consommés par `PayrollCalculator`) |
| 24 | V50 | `tax` | Seed des comptes 447 + 4438 (reverse charge + TVA différée) dans SYSCOHADA et PCG_FRANCE |
| 25 | V51 | `accounting-engine` | PostgreSQL Row-Level Security (RLS) sur 6 tables financières — defense in depth (en plus du filtre JWT claim) |
| 26 | V52 | `app` | Schéma Spring Batch 5.x — `payrollJob` et `fiscalYearClosingJob` lancés via `BatchController` |

**Autres ajouts transverses sans migration** :
- **MFA 2-step login** — `LoginResponse` porte désormais `mfaRequired` + `mfaChallengeToken` ;
  `POST /auth/login/mfa?mfaChallengeToken=&code=` (audit v4.7 §6.3 — NIST 800-63B AAL2, OWNER/ADMIN obligatoire).
- **JWKS endpoint** — `GET /.well-known/jwks.json` (RFC 7517), actif uniquement en RS256.
- **Factur-X** — `GET /invoices/{id}/factur-x` (XML CII D16B BASICWL, EN 16931) ;
  `GET /invoices/{id}/factur-x-pdf` (501 — dépendance `openpdf`/`iText` non bundlée).
- **Cash Flow Statement** — `GET /financial-statements/cash-flow-statement?from=&to=` (IAS 7 /
  SYSCOHADA TAFIRE, méthode indirecte).
- **Tax declaration schedule** — `GET /tax/declaration-schedule?year=` (TVA mensuelle/trimestrielle,
  IS acomptes + solde, DES) ; `GET /tax/declarations/export?format=ca3|des|efi&from=&to=` (CA3 OK,
  DES/EFI = 501).
- **Corporate tax projection** — `GET /tax/corporate-tax/projection?from=&to=` (IS 15% PME / 25%,
  acomptes + solde 15 mai N+1).
- **Closing snapshots idempotents** — `POST /financial-statements/snapshots/closing/periods/{periodId}`
  (BALANCE_SHEET + INCOME_STATEMENT figés à la clôture).
- **Pagination** sur 6 endpoints de liste (Finding #3) — `GET /invoices`, `/purchase-invoices`,
  `/expense-reports`, `/third-parties`, `/notifications`, `/journal-entries/paged|search` (size
  capped à 200).
- **PATCH /companies/{id}/legal** — mise à jour partielle SIRET/VAT/NIF/address (ADMIN only).
- **DELETE /third-parties/lettrage/{lettrageId}** — dé-lettrage désormais ADMIN only.
- **POST /bank-reconciliation/lines/{lineId}/unmatch** — annulation d'un rapprochement.
- **POST /purchase-invoices/{id}/void** — annulation d'une facture fournisseur.
- **POST /fixed-assets/{id}/components** + `POST /fixed-assets/{id}/test-impairment` (IAS 16/36).
- **OpenTelemetry tracing** — exporter OTLP configurable (`OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=grpc|http/protobuf`). Collector d'exemple dans
  `deploy/otel-collector-config.yaml`.
- **JWT RS256** — `app.jwt.algorithm=RS256` + `app.jwt.rsa.private-key-path` (signature) +
  `app.jwt.rsa.public-key-path` (vérification + JWKS). HS256 reste le défaut pour le dev.

### Récapitulatif métriques v5.2

| Métrique | v4.7 | v5.2 |
|---|---|---|
| Modules Gradle | 27 | **30** |
| Contrôleurs REST | 26 | **32** |
| Endpoints REST | ~152 | **190** |
| Migrations Flyway | 45 | **63** (V0_000 → V52) |
| Fichiers de test | 27 | **31** |
| Méthodes `@Test` | ~301 | **310** |

---

## Audit comptable — synthèse des corrections appliquées

### Corrections critiques (Bloquants)

| # | Écart | Module | Statut |
|---|---|---|---|
| **B1** | `closeFiscalYear` cassé pour 5/6 référentiels (codes en dur `"12"`/`"110"`) → résolution par `reportingClass = CAPITAUX_PROPRES` | accounting-engine | ✅ Corrigé |
| **B2** | `PENDING_APPROVAL → POSTED` bloqué à vie (aucun listener sur `ApprovalDecidedEvent`) → `@EventListener` + `postJournalEntryAfterApproval` | accounting-engine | ✅ Corrigé |
| **B3** | `inferReportingClass` ignorait le paramètre `framework` → mapping spécialisé pour `PCGR_CANADA` | chart-of-accounts | ✅ Corrigé |
| **B4** | `InvoicingService.generateInvoiceEntry` codes en dur `"701"`/`"443"` → résolution par `reportingClass` + `taxMappingCode` | invoicing | ✅ Corrigé |

### Corrections majeures

| # | Écart | Module | Statut |
|---|---|---|---|
| **M2** | Reversal non idempotent (`UUID.randomUUID()` dans la clé) → clé déterministe `"reversal-" + originalId` | accounting-engine | ✅ Corrigé |
| **M4** | Dashboard SYSCOHADA-spécifique (préfixes `"5*"`/`"6*"`/`"7*"`) → filtrage par `reportingClass` + `taxMappingCode` | reporting | ✅ Corrigé |
| **M9** | `validateAccount` ne validait pas sémantiquement → vérification `reportingClass` attendue (ACTIF pour stock/immobilisation, CHARGES pour amortissement/COGS) | fixed-assets, inventory | ✅ Corrigé |

### Écarts identifiés mais NON corrigés

| # | Écart | Sévérité | Impact |
|---|---|---|---|
| M3 | Trigger DB ne couvre pas la transition `DRAFT → POSTED` | Majeur | Risque silencieux d'écriture déséquilibrée (postage via SQL direct) |
| M5 | Balance âgée non implémentée | Majeur | KPIs `totalReceivables` / `totalPayables` sont des totaux agrégés, pas ventilés |
| M6 | `pendingApprovals = 0` hardcodé | Majeur | KPI toujours 0 — ne pas l'afficher côté mobile |
| M7 | Rapport bailleur sources non reconciliables | Majeur | `balanceRemaining` peut être incohérent |
| M8 | Aucune pagination (27 endpoints `List<>`) | Majeur | Risques de timeout / OOM sur entreprise avec historique |
| M10 | `generateAcquisitionEntry` absent | Majeur | L'acquisition d'immobilisation n'est PAS comptabilisée automatiquement |
| M11 | Plus-value de cession sur `depreciationExpenseAccountId` | Majeur | Comptabilisation incorrecte de la plus-value |
| M12 | `approverEmails = List.of()` partout | Majeur | Workflow 4-yeux silencieusement désactivé pour les écritures auto |
| M14 | Arrondis à 4 décimales codés en dur | Majeur | Inadapté pour XOF/XAF/JPY (0 décimales) |
| B5 | Contrôle de rôles manquant sur 100/152 endpoints | Bloquant | Un `VIEWER` peut créer des écritures, factures, etc. |

---

## Ce qui n'est PAS dans le projet

- **Docker / Testcontainers** — remplacé par Zonky embedded-postgres pour l'environnement de
  dev (toujours un vrai binaire PostgreSQL, pas H2). Si Docker devient disponible en CI,
  brancher Testcontainers PostgreSQL en gardant le même SQL Flyway.
- **SSO / OIDC entrant** — hors portée v2.1 (seul l'auth par mot de passe est spécifié).
  L'architecture JWT est compatible avec une future couche OIDC par-dessus.
- **Webhooks sortants** — pour notifier un système externe d'une `ApprovalRequest` en attente.
  Le pattern `NotificationChannelPort` ne couvre que l'e-mail/in-app ; un canal webhook séparé
  serait à spécifier.
- **Formats réglementaires par pays** (TAFIRE pour SYSCOHADA, liasse fiscale française,
  annexes fiscales canadiennes) — hors périmètre v2.1, à cadrer dans un futur lot dédié
  une fois le socle validé en production.
- **Pagination sur les endpoints de liste** — non implémenté (audit M8). À ajouter avant la
  mise en production sur entreprise avec plusieurs exercices d'historique.
- **i18n des messages d'erreur** — les messages `ProblemDetail.detail` sont en français
  hardcoded. À templater si une locale autre que fr est supportée.
# jocountant_backend
