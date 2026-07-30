# Module : invoicing

> Factures de vente et avoirs, avec génération automatique de l'écriture comptable et du PDF.

## Rôle du module

Le module `:invoicing` gère le cycle de vie des factures de vente et des avoirs. Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`)
— un commerce facture des marchandises, un service facture du temps, une ONG facture
occasionnellement des services. Il fonctionne pour les 6 référentiels grâce à la résolution
référentiel-agnostique des comptes (audit B4).

Le module **génère des écritures comptables** via `:accounting-engine` au passage
`DRAFT → ISSUED` :
- Débit Client (compte dédié du tiers) — total TTC
- Crédit Ventes (compte de `PRODUITS`) — subtotal HT
- Crédit TVA collectée (compte de `PASSIF`) — taxAmount (si > 0)

Pour un avoir (`CREDIT_NOTE`), débit/crédit sont inversés.

## Ce qu'il fait précisément

### Entités principales

- `SalesInvoice` — facture ou avoir. Champs : `thirdPartyId`, `type` (STANDARD/CREDIT_NOTE),
  `status` (DRAFT/ISSUED/PARTIALLY_PAID/PAID/VOID), `invoiceNumber` (null en DRAFT, attribué
  via `:document-numbering` à l'émission), `issueDate`, `dueDate`, `currency`, `subtotal`
  (HT), `taxAmount` (TVA), `totalAmount` (TTC), `paidAmount` (cumulé), `creditNoteForInvoiceId`
  (si avoir — référence la facture corrigée), `journalEntryId` (ID de l'écriture comptable),
  `isReverseCharge` (**V45** — autoliquidation intra-UE B2B, art. 283 2 nonies CGI ; défaut
  `false`. Si `true`, l'écriture crédite le compte 447 « TDA autoliquidation »
  (`taxMappingCode="VAT_REVERSE_CHARGE"`, fallback `4447`/`447`) au lieu du 443, et la
  facture porte la mention « Autoliquidation »).
- `InvoiceLine` — ligne de facture. Champs : `invoiceId`, `description`, `quantity`,
  `unitPrice`, `discountPercent`, `taxRate`, `itemId` (Commerce) OU `timesheetEntryId`
  (Service — mutuellement exclusifs), `lineTotalHt`, `lineTotalTax`.
- `InvoiceType` (enum) — `STANDARD`, `CREDIT_NOTE`.
- `InvoiceStatus` (enum) — `DRAFT`, `ISSUED`, `PARTIALLY_PAID`, `PAID`, `VOID`.

### Règles métier clés

1. **Une ligne référence `itemId` OU `timesheetEntryId`, jamais les deux** — 422
   `ITEM_AND_TIMESHEET_EXCLUSIVE` sinon.
2. **`invoiceNumber` attribué à l'émission** — via `:document-numbering`
   (`DocumentType.SALES_INVOICE` pour une facture, `DocumentType.CREDIT_NOTE` pour un
   avoir). Null tant que la facture est DRAFT.
3. **Une facture ISSUED est immuable** — correction par avoir uniquement
   (`POST /invoices/{id}/credit-note`).
4. **Écriture comptable générée à l'émission** (audit B4 — résolution référentiel-agnostique) :
   - **Compte de ventes** : (1) `PRODUITS` + `taxMappingCode = "SALES_REVENUE"`, (2) à défaut
     `PRODUITS` actif quelconque, (3) à défaut (rétro-compatibilité) codes `"701000"`/`"701"`.
   - **Compte de TVA collectée** : (1) `PASSIF` + `taxMappingCode = "VAT_COLLECTED"`, (2) à
     défaut codes `"443000"`/`"443"`.
   Lève `SALES_ACCOUNT_NOT_FOUND` / `VAT_ACCOUNT_NOT_FOUND` si rien trouvé.
5. **Idempotence synthétique** — `idempotencyKey = "invoicing-" + invoice.getId()` au
   postage de l'écriture.
6. **`approverEmails = List.of()`** (audit M12) — l'écriture est postée via
   `accountingEngineService.postJournalEntry(companyId, entryId, List.of())`. Si une
   `ApprovalRule JOURNAL_ENTRY_POST` ou `INVOICE_ISSUE` s'active, aucun approbateur n'est
   notifié.
7. **Règlement partiel/total** — `POST /invoices/{id}/record-payment` met à jour `paidAmount`
   et passe le statut à `PARTIALLY_PAID` ou `PAID` (si `paidAmount >= totalAmount`). Pas de
   génération d'écriture comptable pour le règlement en Phase 12 (le lettrage est manuel via
   `:third-parties`).
8. **Avoir inverse l'écriture** — pour `CREDIT_NOTE`, débit/crédit sont permutés dans
   l'écriture générée.
9. **PDF généré via `:document-generation`** — `GET /invoices/{id}/pdf` produit le PDF via
   un gabarit Thymeleaf (Phase 11).
10. **Factur-X (audit v4.7 §4.1 Finding #5)** — `FacturXExporter` (composant Spring du
    module) génère un XML Factur-X profil **BASICWL** (Cross Industry Invoice D16B, conforme
    **EN 16931**) pour conformité Loi 2023-314 (facturation électronique B2B obligatoire en
    France depuis le 1er septembre 2026). Le XML contient `SellerTradeParty` +
    `BuyerTradeParty` (SIRET, TVA intracommunautaire — depuis les champs V42),
    `ApplicableTradeTax` par taux de TVA, et `SpecifiedTradeSettlementHeaderMonetarySummation`.
    Exposé via `GET /invoices/{id}/factur-x`. L'embarquement PDF/A-3 (`/factur-x-pdf`)
    n'est pas encore activé — retourne 501 tant que `openpdf`/`iText` n'est pas ajouté au
    classpath (TODO v4.8 build.gradle).

### Cycle de vie des objets

- `SalesInvoice` : `DRAFT → ISSUED → PARTIALLY_PAID → PAID` (ou `→ VOID` à tout moment
  après ISSUED).
  - `DRAFT → ISSUED` : via `POST /invoices/{id}/issue`. Attribue `invoiceNumber`, génère
    l'écriture comptable, publie `InvoiceIssuedEvent`.
  - `ISSUED → PARTIALLY_PAID` : via `POST /invoices/{id}/record-payment` si
    `paidAmount < totalAmount` après règlement.
  - `ISSUED/PARTIALLY_PAID → PAID` : via `POST /invoices/{id}/record-payment` si
    `paidAmount >= totalAmount`.
  - `ISSUED → VOID` : via un avoir `CREDIT_NOTE` (pas d'endpoint `void` direct).
- `InvoiceLine` : créée à la création de la facture, immuable ensuite.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/invoicing/invoices?fiscalYearId=&page=&size=` | **Paginé (Finding #3)** — retourne une `Page<InvoiceResponse>`. `?page=0&size=20` (défaut, `size` capped à 200). `?fiscalYearId=` filtre par exercice via `resolveFiscalYear`. | — |
| GET | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}` | Récupère une facture par ID (deep-linking mobile). | 404 |
| POST | `/api/v1/companies/{companyId}/invoicing/invoices` | Crée une facture DRAFT. Corps : `{thirdPartyId, type?, currency?, issueDate?, dueDate?, lines: [...]}` | 404 `ThirdParty`, 422 `ITEM_AND_TIMESHEET_EXCLUSIVE` |
| POST | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/issue` | Émet une facture (DRAFT → ISSUED) + génère écriture. Si `isReverseCharge=true` (V45), crédite le compte 447 (autoliquidation) au lieu de 443. | 404, 409 `INVOICE_NOT_DRAFT`, 422 `SALES_ACCOUNT_NOT_FOUND`/`VAT_ACCOUNT_NOT_FOUND`/`VAT_REVERSE_CHARGE_ACCOUNT_NOT_FOUND`/`JOURNAL_VT_NOT_FOUND`/`PERIOD_NOT_FOUND`/`PERIOD_LOCKED` |
| POST | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/record-payment` | Enregistre un règlement | 404, 409 `INVOICE_NOT_ISSUED`/`INVOICE_VOID`, 422 `PAYMENT_AMOUNT_INVALID` |
| POST | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/credit-note` | Crée un avoir pour une facture | 404, 422 |
| GET | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/pdf` | Génère le PDF de la facture | 404 |
| GET | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/factur-x` | **V45 — audit v4.7 §4.1 Finding #5** — Génère le XML Factur-X (CII D16B, profil BASICWL, EN 16931). Loi 2023-314. Content-Type `application/xml`. | 404 |
| GET | `/api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/factur-x-pdf` | **TODO** — PDF/A-3 avec XML Factur-X embarqué comme `EmbeddedFile` (`AFRelationship=/Data`). **Retourne 501 Not Implemented** tant que `openpdf`/`iText` n'est pas bundlé (header `X-Error-Reason: PDF_A3_FACTURX_DEPENDENCY_MISSING`). | 404, 501 |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ReportingClass`, `ApplicationEventPublisher`.
- `:third-parties` — `ThirdParty`, `ThirdPartyRepository` (pour récupérer le compte dédié
  du client).
- `:chart-of-accounts` — `Account`, `AccountRepository` (résolution référentiel-agnostique
  des comptes de ventes et de TVA — audit B4).
- `:accounting-engine` — `AccountingEngineService.createJournalEntry` +
  `postJournalEntry`, `JournalRepository` (recherche du journal `"VT"`).
- `:document-numbering` — `DocumentNumberingService.nextNumber(SALES_INVOICE ou
  CREDIT_NOTE, ...)`.
- `:document-generation` — `DocumentGenerationService` pour la génération PDF.
- (interne) `FacturXExporter` (composant Spring du module `:invoicing`, package
  `jo.accountant.invoicing.einvoice`) — génération XML Factur-X (CII D16B BASICWL).
  N'a pas de dépendance Gradle externe pour le moment (StAX only).

### Modules qui dépendent de celui-ci

- `:app` — tests d'intégration `InvoicingIntegrationTest`.
- `:reporting` — recense les factures émises pour les KPIs (chiffre d'affaires, etc.).

### Événements publiés / consommés

- **Publie** : `InvoiceIssuedEvent` (au passage `DRAFT → ISSUED`).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V14_001__invoicing.sql` — tables `sales_invoice` et
  `invoice_line`. CHECK sur `type` (2 valeurs), `status` (5 valeurs), `total_amount >= 0`.
  Index sur `(company_id, status)`, `(company_id, third_party_id)`, `(invoice_id)`.
- `src/main/resources/db/migration/V45__sales_invoice_reverse_charge.sql` — **V45 — Finding #7**.
  Ajoute la colonne `is_reverse_charge BOOLEAN NOT NULL DEFAULT FALSE` sur `sales_invoice`.
  Positionnée à TRUE à l'émission quand le tiers client ET l'entreprise émettrice disposent
  tous deux d'un numéro de TVA intracommunautaire (Article 283, 2 nonies du CGI). L'écriture
  crédite alors le compte 447 « TDA autoliquidation » (`taxMappingCode="VAT_REVERSE_CHARGE"`,
  fallback SYSCOHADA/PCG `444700`/`4447`) au lieu du 443 (TVA collectée). La facture porte la
  mention « Autoliquidation - Article 283, 2 nonies du CGI ». Le défaut FALSE assure la
  rétro-compatibilité de toutes les factures existantes.

## Points d'attention (hérités de l'audit)

- ⚠️ **B4 — Résolution des comptes corrigée** : la version initiale utilisait les codes en
  dur `"701000"/"701"` (ventes) et `"443000"/"443"` (TVA). Sur 6 référentiels, seul
  SYSCOHADA fonctionnait ; PCG_FRANCE et PCN_HAITI échouaient sur la TVA (codes `4457`/
  `4452` réels vs `443` en dur) ; PCGR_CANADA était sémantiquement inversé (classe 7 =
  CHARGES au Canada) ; IFRS échouait (codes non standardisés). La version corrigée utilise
  `reportingClass` + `taxMappingCode` en priorité, avec fallback sur les codes pour
  rétro-compatibilité. **Non-breaking** côté API ; les codes d'erreur `SALES_ACCOUNT_NOT_FOUND`
  et `VAT_ACCOUNT_NOT_FOUND` existent toujours avec des messages plus précis.
- ⚠️ **M14 — Arrondis à 4 décimales codés en dur** — `setScale(4, HALF_UP)` sur `lineHt`
  et `lineTax` ; `divide(HUNDRED, 4, HALF_UP)` pour le calcul de TVA. Pour une devise
  0-décimales (XOF/XAF/JPY), les montants sont stockés avec 4 décimales. **Le test
  `InvoicingIntegrationTest.createDraftInvoice` verrouille ce défaut** en assertant
  `inv.subtotal() == "10000.0000"` (4 décimales) — il échouerait si l'on rendait l'arrondi
  currency-aware (HTG=2 → `10000.00`).
- ⚠️ **Aucune pagination sur `GET /invoices`** — **CORRIGÉ (Finding #3)** : `GET /invoices`
  retourne désormais une `Page<InvoiceResponse>` avec `?page=&size=` (défaut 0/20, size capped
  à 200). Le paramètre `?fiscalYearId=` filtre par exercice via `resolveFiscalYear`.
- ⚠️ **Règlement non lettré automatiquement** — `recordPayment` met à jour `paidAmount` mais
  ne génère pas d'écriture comptable de règlement (D Banque / C Client) ni de lettrage
  automatique. L'utilisateur doit poster manuellement l'écriture de règlement via
  `:accounting-engine` et lettrer via `:third-parties`. Le client mobile doit informer
  l'utilisateur de cette étape manuelle.
- ⚠️ **`INVOICE_ISSUE` approval rule non évaluée** — `ApprovalActionType.INVOICE_ISSUE`
  existe dans `:approval-workflow` mais `issueInvoice` ne l'appelle pas (il poste
  directement l'écriture, qui elle peut déclencher `JOURNAL_ENTRY_POST`). Le seuil
  d'approbation spécifique à l'émission de facture n'est donc pas fonctionnel.
- ⚠️ **Code journal `"VT"` en dur** — l'écriture cherche le journal de code `"VT"`. Si
  l'entreprise n'a pas créé ce journal, lève `JOURNAL_VT_NOT_FOUND`.

## Tests

Couvert par `InvoicingIntegrationTest` dans `:app` (12 tests) — création facture DRAFT,
émission, écriture comptable, règlement, avoir, PDF. **Anti-pattern** : le test asserte
`subtotal == "10000.0000"` (4 décimales), ce qui verrouille le défaut d'arrondis au lieu de
le tester (audit 3.6).
