# Module : purchasing

> Factures fournisseur (achats) et paiements fournisseurs — symétrique de `:invoicing` côté
> décaissement.

## Rôle du module

Le module `:purchasing` gère les factures reçues des fournisseurs et leur règlement. Il est
**sectoriel** : activé pour les 4 variants commerce (`RETAIL_COMMERCE`,
`WHOLESALE_COMMERCE`, `MIXED_COMMERCE`, `ECOMMERCE`) ainsi que pour
`PROFESSIONAL_SERVICES`, `NGO_HUMANITARIAN`, `ACCOUNTING_FIRM`, `SCHOOL`, `HOSPITAL`
via le mapping `business_type_module` (V34 — restructuration 2026-07-24 — Partie A).

La capacité de paiement fournisseur vit ici (§2.0 du prompt — pas de module `:payment`
séparé) :
- Paiement **client** (encaissement) → `:invoicing` (`RecordPaymentRequest`).
- Paiement **fournisseur** (décaissement) → **ce module** (`RecordPurchasePaymentRequest`).
- Rapprochement bancaire des deux → `:bank-reconciliation` (inchangé).

## Ce qu'il fait précisément

### Entités principales

- `PurchaseInvoice` — facture fournisseur. Champs : `thirdPartyId` (SUPPLIER), `type`
  (STANDARD/DEBIT_NOTE), `status` (DRAFT/RECEIVED/PARTIALLY_PAID/PAID/VOID),
  `invoiceNumber` (attribué à `RECEIVED` via `:document-numbering`), `supplierReference`
  (n° facture fournisseur externe, texte libre), `issueDate`, `dueDate`, `currency`,
  `subtotal`, `taxAmount`, `totalAmount`, `paidAmount`, `journalEntryId`.
- `PurchaseInvoiceLine` — `invoiceId`, `description`, `quantity`, `unitPrice`, `taxRate`,
  `expenseAccountId` (compte de charge cible, `ReportingClass.CHARGES`), `lineTotalHt`,
  `lineTotalTax`. **Pas de lien `:inventory` au MVP** — l'entrée de stock déclenchée par
  une facture d'achat est un chaînage manquant documenté dans `BACKLOG.md` (hors scope,
  §4 du prompt).

### Règles métier clés

1. **Le tiers doit être un `SUPPLIER`** — `createPurchaseInvoice` valide
   `thirdParty.type == SUPPLIER` et lève `422 THIRD_PARTY_NOT_SUPPLIER` sinon.
2. **Passage DRAFT → RECEIVED** : attribue `invoiceNumber` via
   `DocumentType.PURCHASE_INVOICE` (scopeKey = `"AC"`), génère l'écriture comptable
   (voir ci-dessous). Une fois RECEIVED, la facture est immuable — correction par
   DEBIT_NOTE (avoir fournisseur).
3. **Règlement partiel/total** symétrique à `:invoicing` : `recordPayment(amount)` met à
   jour `paidAmount` et passe à `PARTIALLY_PAID` ou `PAID`. Pas de lettrage automatique au
   MVP (l'utilisateur lettre via `:third-parties`).
4. **Annulation** via `void()` disponible tant que non payée (statut DRAFT ou RECEIVED).
5. **Résolution des comptes référentiel-agnostique** (calquée sur audit B4) :
   - Compte de charges par ligne : `expenseAccountId` si précisé (doit être CHARGES),
     sinon recherche `CHARGES + taxMappingCode="PURCHASES"` → `CHARGES` actif quelconque
     → fallback SYSCOHADA `"601000"/"601"`.
   - Compte de TVA déductible : `ACTIF + taxMappingCode="VAT_DEDUCTIBLE"` → fallback
     SYSCOHADA `"445000"/"445"`.
   - Compte fournisseur : compte dédié du tiers (ou collectif si pas de dédié).
6. **Code journal `AC` (achats)** — doit exister (sinon `422 JOURNAL_AC_NOT_FOUND`). Pas
   de fallback (contrairement aux comptes, le code journal est porté par l'utilisateur).
7. **Écriture DEBIT_NOTE inversée** — débit/crédit permutés, comme pour les CREDIT_NOTE
   côté `:invoicing`.

### Cycle de vie des objets

- `PurchaseInvoice` : `DRAFT` (lignes éditables) → `RECEIVED` (attribue `invoiceNumber`,
  génère l'écriture) → `PARTIALLY_PAID` → `PAID` (ou `VOID` tant que non payée).
- `PurchaseInvoiceLine` : créée en même temps que la facture, immuable après `RECEIVED`.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/purchase-invoices` | Crée une facture DRAFT | 422 `THIRD_PARTY_NOT_SUPPLIER`, `PURCHASES_ACCOUNT_NOT_FOUND` |
| GET | `/api/v1/companies/{companyId}/purchase-invoices?fiscalYearId=&from=&to=&page=&size=` | **Paginé (Finding #3)** — retourne une `Page<PurchaseInvoiceResponse>`. `?page=0&size=20` (défaut, `size` capped à 200). `?fiscalYearId=` filtre par exercice (prévalence sur `from`/`to`). `?from=`/`?to=` plage de dates sur `issueDate`. | — |
| GET | `/api/v1/companies/{companyId}/purchase-invoices/{id}` | Détail d'une facture | 404 `PurchaseInvoice` |
| PATCH | `/api/v1/companies/{companyId}/purchase-invoices/{id}` | Modification DRAFT — non implémenté au MVP (lève 409 `PATCH_NOT_IMPLEMENTED`) | 409 `PATCH_NOT_IMPLEMENTED` |
| POST | `/api/v1/companies/{companyId}/purchase-invoices/{id}/receive` | DRAFT → RECEIVED, attribue `invoiceNumber`, génère l'écriture | 409 `PURCHASE_INVOICE_NOT_DRAFT`, 422 `JOURNAL_AC_NOT_FOUND`/`VAT_DEDUCTIBLE_ACCOUNT_NOT_FOUND`/`PURCHASES_ACCOUNT_NOT_FOUND` |
| POST | `/api/v1/companies/{companyId}/purchase-invoices/{id}/payments` | Paiement partiel/total | 409 `PURCHASE_INVOICE_NOT_RECEIVED`/`PURCHASE_INVOICE_VOID`, 422 `PAYMENT_EXCEEDS_TOTAL` |
| POST | `/api/v1/companies/{companyId}/purchase-invoices/{id}/void` | Annulation (tant que non payée) — passe le statut à `VOID`. Disponible tant que `status ∈ {DRAFT, RECEIVED}` (refusé si `PAID` ou déjà `VOID`). | 409 `PURCHASE_INVOICE_ALREADY_PAID`/`PURCHASE_INVOICE_ALREADY_VOID` |

> **Breaking** : `403 MODULE_NOT_ENABLED` si le module `PURCHASING` n'est pas activé
> pour la société. Le message indique explicitement comment l'activer (wizard étape 8 ou
> `POST /wizard/complete`).

> **Stabilisation 2026-07-25 (suite 4)** — ajout du paramètre optionnel `?fiscalYearId=`
> sur `GET /purchase-invoices`. Le filtre s'applique sur `issueDate` (date de réception de
> la facture par l'entreprise, pas la date d'émission fournisseur `supplierReference`).
> Combiné avec `?from=`/`?to=` : si `?fiscalYearId=` est présent, il a la prévalence sur
> les filtres dates (les bornes de l'exercice résolu remplacent `from`/`to`). Pour filtrer
> par une plage de dates précise qui ne correspond pas à un exercice, omettre
> `?fiscalYearId=` et ne passer que `?from=`/`?to=`.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `CurrencyRoundingService`, `ReportingClass`.
- `:audit-trail` — auditing.
- `:chart-of-accounts` — `Account`, `AccountRepository`.
- `:accounting-engine` — `AccountingEngineService`, `JournalRepository`,
  `JournalEntrySourceModule.PURCHASING`.
- `:document-numbering` — `DocumentNumberingService`, `DocumentType.PURCHASE_INVOICE`.
- `:approval-workflow` — délègue à `JOURNAL_ENTRY_POST` (§2.2 du prompt — choix de cohérence
  avec `:invoicing`/`:fixed-assets`/`:inventory`).
- `:third-parties` — `ThirdParty` (type `SUPPLIER`), `ThirdPartyRepository`.
- `:company` — `ModuleAccessGuard` (enforcement de l'activation du module `PURCHASING`).

### Modules qui dépendent de celui-ci

- Aucun au MVP. Le rapprochement bancaire (`:bank-reconciliation`) consomme les
  `JournalLine` (via `thirdPartyId` / `accountId`), pas directement `PurchaseInvoice`.

### Événements publiés / consommés

- **Publie** : aucun au MVP (une `PurchaseInvoiceReceivedEvent` serait à ajouter si un
  consommateur est identifié — ex. déclencher l'entrée de stock automatique quand le
  chaînage `:inventory` sera implémenté).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V35__purchasing.sql` — tables `purchase_invoice` et
  `purchase_invoice_line`. CHECK sur `type` et `status`. Index sur
  `(company_id, status)`, `third_party_id`, `(company_id, supplier_reference)`.

## Repository — méthodes de lecture (suite 4)

`PurchaseInvoiceRepository` (Spring Data JPA, dérivation de méthodes) expose les méthodes
de lecture suivantes. Aucune écriture custom — Spring Data génère les requêtes à partir du
nom des méthodes.

| Méthode | Usage |
|---|---|
| `findByCompanyIdOrderByIssueDateDesc(companyId)` | `GET /purchase-invoices` sans filtre (comportement historique). |
| `findByCompanyIdAndStatus(companyId, status)` | Balance âgée fournisseurs (`:reporting`) — ne renvoie que les factures `RECEIVED`/`PARTIALLY_PAID`. |
| `findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(companyId, start, end)` | **Nouveau (suite 4)** — `GET /purchase-invoices?fiscalYearId=` : résout l'exercice via `AccountingEngineService.resolveFiscalYear` puis appelle cette méthode avec `start = fy.startDate` et `end = fy.endDate`. Aussi utilisé par `:reporting` pour l'export `purchase_register` quand `?from=`/`?to=` sont fournis. |

## Points d'attention

- ⚠️ **Pas de chaînage automatique achat → entrée de stock** — l'entrée de stock
  déclenchée par une facture d'achat est explicitement hors scope (§4 du prompt, documenté
  dans `BACKLOG.md`).
- ⚠️ **Pas de PATCH implémenté** — la modification d'une facture DRAFT n'est pas
  implémentée au MVP (le endpoint existe pour conformité au contrat API mais lève
  `409 PATCH_NOT_IMPLEMENTED`). L'utilisateur doit recréer la facture.
- ⚠️ **Pas de lettrage automatique** — `recordPayment` met à jour `paidAmount` mais ne
  lettre pas automatiquement la ligne fournisseur. L'utilisateur lettre via `:third-parties`.
- ⚠️ **Pas de PDF au MVP** — la facture d'achat est un document interne (pas envoyée à un
  tiers externe), pas de `DocumentType.PURCHASE_INVOICE` dans `:document-generation`
  (§2.5 du prompt).

## Activation (restructuration :company §7)

Le module `:purchasing` est **sectoriel** : son utilisation exige que le module
`PURCHASING` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `PURCHASING` (voir `V34__business_type_catalog_expansion.sql`).

## Tests

Couvert par `PurchasingIntegrationTest` dans `:app` — cycle de vie complet (création →
réception → règlement → PAID), vérification de l'écriture comptable (équilibre débit/
crédit, comptes attendus), `ModuleAccessGuard` qui bloque si module non activé.
