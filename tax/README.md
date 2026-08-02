# Module : tax

> Règles fiscales (TVA, retenues à la source) et déclaration fiscale agrégée par période.

## Rôle du module

Le module `:tax` gère la configuration fiscale de l'entreprise et produit les déclarations
périodiques. Il est **sectoriel transversal** : activé pour les secteurs `COMMERCE`,
`SERVICE` et `ONG` via le mapping `business_type_module` (restructuration :company §6). Il fonctionne pour les 6
référentiels — les taux et règles sont configurables par entreprise, indépendamment du
framework.

Le module **ne génère aucune écriture comptable**. Il stocke des règles (taux de TVA,
retenues à la source) et des comptes payables/recevables, mais ces comptes ne sont **pas
lus** par `:invoicing` (qui résout la TVA via `reportingClass + taxMappingCode` depuis
l'audit B4). La déclaration fiscale est une agrégation en lecture seule des factures
émises sur la période.

## Ce qu'il fait précisément

### Entités principales

- `TaxRule` — règle de TVA. Champs : `code` (unique par entreprise), `label`, `rate`
  (BigDecimal, ex. 15.00 pour 15 %), `payableAccountId` (compte de TVA collectée),
  `receivableAccountId` (compte de TVA déductible), `applicableFrom`, `applicableTo`,
  `active`. **V55** : `vatMode` (`DEBIT` défaut — exigible à l'émission, ou `ENCAISSEMENT` —
  exigible à l'encaissement, art. 289 II CGI). Si `ENCAISSEMENT`, l'écriture d'émission crédite
  le compte 4438 « TVA différée non encaissée » (`taxMappingCode="VAT_DEFERRED"`, fallback
  `443800`/`4438`) au lieu du 443 (TVA collectée) ; la TVA est constatée au règlement du
  client.
- `WithholdingRule` — règle de retenue à la source. Champs : `code`, `label`, `rate`,
  `applicableThirdPartyTypes` (JSONB — ex. `["SUPPLIER", "EMPLOYEE"]`),
  `applicableFrom`, `applicableTo`, `active`. **V57** : `bracketType` (`FLAT` défaut, ou
  `PROGRESSIVE`) + `brackets` (JSONB `[{threshold, rate}]` — barème progressif par tranches).
  En `PROGRESSIVE`, le `rate` racine est ignoré côté calcul (chaque tranche a son propre taux).
- `ContributionRule` — **V51 — audit v4.7 §4.1 Finding #3** — règle de cotisation sociale
  pour `PayrollCalculator`. Champs : `code` (unique par entreprise, ex. `URSSAF`, `RETRAITE`,
  `CSG`, `MUTUELLE`), `label`, `regime` (`ContributionRegime` : `FR_GENERAL` / `FR_CADRE` /
  `FR_NON_CADRE` / `HT_GENERAL` / `CUSTOM`), `contributionType` (`ContributionType` :
  `EMPLOYEE` / `EMPLOYER` / `EMPLOYEE_AND_EMPLOYER`), `rate` (% appliqué sur l'assiette),
  `baseType` (`ContributionBase` : `GROSS` / `GROSS_ABATED` / `CAPPED_GROSS` /
  `CAPPED_GROSS_ABATED` / `TRANCHE_B`), `abatementRate` (ex. 98.25 % pour CSG),
  `monthlyCeiling` (PMSS France 3 864 € 2024, PMT Haïti, ...), `ceilingMultiplier` (ex. 4 pour
  Tranche B = PMSS × 4), `taxMappingCode`, `active`. Stockage : une ligne par
  `(companyId, code, regime)`.
- `CorporateTaxRule` — règle d'impôt sur les sociétés (IS) : taux PME 15 %, taux normal 25 %,
  seuils de réintégration. Utilisé par `getCorporateTaxProjection`.
- `TaxDeclaration` (DTO) — déclaration agrégée par taux de TVA sur une période. Champs :
  `from`, `to`, `lines: [{taxRate, taxableBase, taxAmount}]`, `totalTaxableBase`,
  `totalTaxAmount`.
- `CorporateTaxProjection` (DTO) — projection IS : résultat comptable → résultat fiscal
  (+ réintégrations Charasse, − déductions LTPE) → IS brut (15 % PME ou 25 %) → IS net
  (− crédits d'impôt) → 4 acomptes (15 mars, 15 juin, 15 sept, 15 déc) + solde (15 mai N+1,
  art. 1668 CGI).
- `TaxDeclarationSchedule` (DTO) — échéancier annuel des déclarations fiscales françaises :
  TVA mensuelle (12 échéances, le 19 du mois M+1) OU trimestrielle (4 échéances), IS acomptes
  + solde, DES mensuelle (10 du mois M+1, art. 289 B CGI). Limitation v1 : ne tient pas compte
  des reports de weekend/jour férié (art. A. 40 A LPF).
- `VatMode` (enum, V55) — `DEBIT`, `ENCAISSEMENT`.
- `WithholdingBracketType` (enum, V57) — `FLAT`, `PROGRESSIVE`.
- `ContributionBase` (enum, V51) — `GROSS`, `GROSS_ABATED`, `CAPPED_GROSS`,
  `CAPPED_GROSS_ABATED`, `TRANCHE_B`.
- `ContributionRegime` (enum, V51) — `FR_GENERAL`, `FR_CADRE`, `FR_NON_CADRE`, `HT_GENERAL`,
  `CUSTOM`.
- `ContributionType` (enum, V51) — `EMPLOYEE`, `EMPLOYER`, `EMPLOYEE_AND_EMPLOYER`.

### Règles métier clés

1. **`code` unique par entreprise** pour `TaxRule` et `WithholdingRule`.
2. **Période de validité** — `applicableFrom` / `applicableTo` (nullable). Une règle sans
   `applicableTo` est valable indéfiniment.
3. **`listTaxRules` retourne les règles de l'entreprise ET les règles globales**
   (`companyId IS NULL`) — permet de seed des règles par défaut (ex. TVA standard HT 15 %).
4. **Déclaration par période** — `getDeclaration(from, to)` agrège les `InvoiceLine` des
   factures `ISSUED` sur la période, par `taxRate`. Calcule `taxableBase = lineTotalHt` et
   `taxAmount = lineTotalTax`.
5. **Pas de télédéclaration** — l'export est déclaratif (CSV/PDF à imprimer), pas
   d'intégration avec un portail fiscal.

### Cycle de vie des objets

- `TaxRule` / `WithholdingRule` : créées → `active=true` → `active=false` (désactivation,
  pas d'endpoint public — opération DB). Pas de suppression physique.
- `TaxDeclaration` : DTO recalculé à chaque appel (pas de persistance).

## Endpoints exposés

### TaxController — `/api/v1/companies/{companyId}/tax`

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/tax/rules` | Crée une règle de TVA (avec `vatMode` V55 optionnel, défaut `DEBIT`) | 422 `TAX_RULE_CODE_REQUIRED`/`RATE_INVALID` |
| GET | `/api/v1/companies/{companyId}/tax/rules` | Liste les règles de TVA (entreprise + globales) | — |
| POST | `/api/v1/companies/{companyId}/tax/withholding-rules` | Crée une règle de retenue à la source (`bracketType=FLAT` ou `PROGRESSIVE` + `brackets`, V57) | 422 champs invalides |
| GET | `/api/v1/companies/{companyId}/tax/withholding-rules` | Liste les règles de retenue à la source | — |
| GET | `/api/v1/companies/{companyId}/tax/declarations?from=&to=` | Déclaration fiscale agrégée par taux sur la période | — |
| GET | `/api/v1/companies/{companyId}/tax/corporate-tax/projection?from=&to=` | **V51 — audit v4.7 §4.1 Finding #4** — Projection IS (15 % PME / 25 %, 4 acomptes + solde 15 mai N+1). Si aucune règle IS configurée, utilise les valeurs par défaut France 2026. | 422 dates invalides |
| GET | `/api/v1/companies/{companyId}/tax/declaration-schedule?year=` | **audit mobile #7** — Échéancier des déclarations fiscales françaises (TVA mensuelle/trimestrielle, IS acomptes + solde, DES mensuelle). `?year=` optionnel (défaut : année courante). Limitation : ne tient pas compte des reports de weekend/férié (art. A. 40 A LPF). | — |
| GET | `/api/v1/companies/{companyId}/tax/declarations/export?format=ca3|des|efi&from=&to=` | **audit mobile #8** — Export déclaration fiscale. `ca3` : CSV UTF-8 BOM (compatible copier-coller impots.gouv.fr). `des`/`efi` : **501 Not Implemented** (TODO v4.9). | 422 dates invalides / format inconnu, 501 DES/EFI |

### ContributionRuleController — `/api/v1/companies/{companyId}/tax/contribution-rules`

**V51 — audit v4.7 §4.1 Finding #3**. CRUD des règles de cotisation sociale pour
`PayrollCalculator`. Tous les endpoints exigent le rôle ADMIN (configuration sensible —
impact sur tous les bulletins de paie).

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/tax/contribution-rules?regime=` | Liste les règles (actives) — filtrable par `?regime=FR_CADRE`. | — |
| POST | `/api/v1/companies/{companyId}/tax/contribution-rules` | Crée une règle (code unique par entreprise). Corps : `{code, label, regime, contributionType, rate, baseType, abatementRate?, monthlyCeiling?, ceilingMultiplier?, taxMappingCode?}`. | 409 `CONTRIBUTION_RULE_CODE_EXISTS` |
| PUT | `/api/v1/companies/{companyId}/tax/contribution-rules/{ruleId}` | Modifie une règle existante (le `code` n'est pas modifiable). | 404 |
| DELETE | `/api/v1/companies/{companyId}/tax/contribution-rules/{ruleId}` | Soft delete — la règle est marquée `active=false`. Les bulletins déjà générés ne sont pas affectés. | 404 |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions.
- `:invoicing` — `SalesInvoice`, `SalesInvoiceRepository`, `InvoiceLine`,
  `InvoiceLineRepository` (pour la déclaration fiscale — lecture seule).

### Modules qui dépendent de celui-ci

- `:chart-of-accounts` — `Account.taxMappingCode` est une référence opaque vers `TaxRule.code`
  (pas de FK dure). Mais en pratique, `:invoicing` résout la TVA via `reportingClass +
  taxMappingCode`, pas via `TaxRule` directement.
- `:payroll` — **`PayrollCalculator`** (V51) consomme `ContributionRule` pour calculer les
  cotisations par tranches (PMSS, CSG abattue, Tranche A/B). Fallback sur `WithholdingRule`
  si aucune `ContributionRule` n'est configurée pour l'entreprise.
- Aucun autre module ne dépend fortement de `:tax` — c'est un module de configuration et de
  reporting, pas un module qui déclenche des écritures.

### Événements publiés / consommés

- **Publie** : aucun.
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V40__tax.sql` — tables `tax_rule` et
  `withholding_rule`. Unique `(company_id, code)`. CHECK sur `rate >= 0`.
  `applicable_third_party_types` en JSONB. Index sur `(company_id, active)`.
- `src/main/resources/db/migration/V51__contribution_rule.sql` — **V51 — audit v4.7 §4.1 #3**.
  Crée la table `contribution_rule` (cotisations par tranches pour `PayrollCalculator`).
  Colonnes : `id`, `company_id`, `code` (unique par entreprise), `label`, `regime` (CHECK 5
  valeurs), `contribution_type` (CHECK 3 valeurs), `rate` (NUMERIC 6,4), `base_type` (CHECK
  5 valeurs), `abatement_rate` (défaut 100), `monthly_ceiling`, `ceiling_multiplier`,
  `tax_mapping_code`, `active`. Index sur `(company_id, active)`.
- `src/main/resources/db/migration/V55__vat_mode_encaissement.sql` — **V55 — Finding #6**.
  Ajoute la colonne `vat_mode` (`DEBIT`/`ENCAISSEMENT`) sur `tax_rule`. Défaut `DEBIT` (régime
  des débits — exigible à l'émission, comportement historique).
- `src/main/resources/db/migration/V57__withholding_rule_progressive_brackets.sql` —
  **V57 — Finding #14**. Ajoute `bracket_type` (`FLAT`/`PROGRESSIVE`) et `brackets` (JSONB
  `[{threshold, rate}]`) sur `withholding_rule`. Défaut `FLAT` (rétro-compat).
- `src/main/resources/db/migration/V61__tax_accounts_seed.sql` — **V61 — Finding #10**. Seed
  des comptes 447 (TVA autoliquidation / reverse charge) et 4438 (TVA différée non encaissée)
  dans les plans SYSCOHADA et PCG_FRANCE (SectorAccountTemplate). Avant V61, ces comptes
  étaient référencés par `InvoicingService` mais jamais seedés — la première facture en
  autoliquidation (V56) ou en TVA encaissement (V55) levait `VAT_REVERSE_CHARGE_ACCOUNT_NOT_FOUND`
  / `VAT_DEFERRED_ACCOUNT_NOT_FOUND`.

## Points d'attention (hérités de l'audit)

- ⚠️ **`TaxRule.payableAccountId` / `receivableAccountId` jamais lus** — ces champs sont
  stockés mais ne sont pas utilisés par `:invoicing` (qui résout la TVA via
  `reportingClass + taxMappingCode` depuis l'audit B4). La `TaxRule` est donc purement
  déclarative. Le client mobile doit informer l'utilisateur que configurer
  `payableAccountId` sur une `TaxRule` n'a aucun effet sur la comptabilisation — c'est le
  `taxMappingCode` du compte qui compte.
- ⚠️ **Aucune écriture de TVA générée** — `:tax` ne poste pas d'écriture de TVA collectée
  ou déductible. La TVA est constatée au moment de l'émission de la facture (via
  `:invoicing` qui poste D Client / C Ventes / C TVA). La régularisation de TVA (déclaration
  CA3 en France, TVA SYSCOHADA) n'est pas automatisée.
- ⚠️ **Aucune pagination sur `GET /rules` et `GET /withholding-rules`** — retourne toutes
  les règles. En pratique, le nombre de règles par entreprise est faible (< 20), donc
  faible risque.
- ⚠️ **Pas de contrôle de rôle** sur les endpoints (audit B5) — un `VIEWER` peut créer une
  règle fiscale, ce qui pourrait permettre de fausser la déclaration.
- ⚠️ **Déclaration agrégée uniquement** — `getDeclaration` ne ventile pas par tiers ni par
  nature d'opération. Pour une déclaration fiscale réelle (ex. CA3 française avec
  ventilation par régime), le client mobile doit compléter manuellement.
- ⚠️ **Pas de test sur les retenues à la source** — `TaxIntegrationTest` couvre la TVA mais
  pas les `WithholdingRule` (audit 3.x). Le client mobile doit tester manuellement ce
  flux.

## Tests

Couvert par `TaxIntegrationTest` dans `:app` (5 tests) — création de règle TVA, création
de règle de retenue à la source, déclaration fiscale par période (agrégation par taux).
Couverture partielle (pas de test sur la désactivation, pas de test sur les
`WithholdingRule` appliquées).

## Activation (restructuration :company §7)

Le module `:tax` est **sectoriel** : son utilisation exige que le module
`TAX` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `TAX` (voir `V8__business_type.sql`).
