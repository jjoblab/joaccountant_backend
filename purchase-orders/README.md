# Module : purchase-orders

> Commandes fournisseurs (Purchase Orders) et 3-way match commande ↔ facture — Finding #10
> (V48, session 23).

## Rôle du module

Le module `:purchase-orders` persiste les **bons de commande fournisseurs** et implémente le
**3-way match** (commande ↔ réception ↔ facture). Il est **toujours-actif** (always-on) au MVP
— pas de `ModuleAccessGuard`. La création de commandes est utile à toute entreprise qui
souhaite formaliser ses engagements d'achat avant la réception de la facture.

Le module **ne génère aucune écriture comptable** au MVP — l'écriture est générée à la
réception de la facture fournisseur dans `:purchasing`. L'intérêt principal est le contrôle
interne via le 3-way match : toute facture rapprochée d'une commande est vérifiée sur 3
dimensions (existence de la commande, quantité, prix), et les écarts sont signalés.

### Problème adressé (Finding #10)

Avant V48, aucune commande fournisseur n'était persistée. Les factures d'achat
(`:purchasing`) étaient enregistrées sans rapprochement avec une commande préalable. Or, le
contrôle interne standard (3-way match) permet de détecter :

- les **factures sans commande sous-jacente** (engagement non autorisé) ;
- les **sur-facturations** (quantité facturée > quantité commandée) ;
- les **écarts de prix** (prix facturé ≠ prix commandé).

Sans ce contrôle, l'entreprise paie des factures qui n'ont pas fait l'objet d'un engagement
formel — risque de fraude et de sur-paiement.

## Ce qu'il fait précisément

### Entités principales

- `PurchaseOrder` — entête de commande. Champs : `supplierId` (FK vers `ThirdParty` de type
  `SUPPLIER`), `orderNumber` (unique par entreprise), `orderDate`, `status`
  (`DRAFT`/`SUBMITTED`/`RECEIVED`/`CLOSED`), `currency` (ISO 4217, défaut `HTG`),
  `totalAmount` (calculé = Σ `quantity × unitPrice` des lignes), `createdAt`, `updatedAt`.
- `PurchaseOrderLine` — ligne de commande. Champs : `poId`, `itemId` (FK vers `Item` de
  `:inventory`, nullable), `description`, `quantity`, `unitPrice`, `receivedQuantity`
  (cumul des quantités reçues via les factures rapprochées — incrémenté par
  `ThreeWayMatchService`), `lineTotal` (= `quantity × unitPrice`).
- `PurchaseOrderStatus` (enum) — `DRAFT` (saisie en cours, modifiable), `SUBMITTED` (validée
  interne), `RECEIVED` (marchandises livrées — prête pour 3-way match), `CLOSED` (facture
  reçue et rapprochée).

### Règles métier clés

1. **`orderNumber` unique par entreprise** — contrainte `uc_po_company_number`. Lève
   `409 PURCHASE_ORDER_NUMBER_EXISTS` si violation.
2. **`totalAmount` calculé automatiquement** à la création/modification des lignes —
   `totalAmount = Σ (line.quantity × line.unitPrice)`. Aucune saisie directe.
3. **Cycle de vie strict** : `DRAFT → SUBMITTED → RECEIVED → CLOSED`. Les transitions sont
   manuelles via `POST /{poId}/change-status?status=`.
4. **3-way match indépendant du cycle de vie** — `POST /3-way-match?invoiceId=` peut être
   appelé à tout moment, même si la commande est encore `DRAFT` (le résultat renverra
   `matches=false` avec `discrepancies=[NO_PURCHASE_ORDER]` si aucune commande `RECEIVED`
   n'existe pour le fournisseur).
5. **`receivedQuantity` incrémentée à chaque match réussi** — `ThreeWayMatchService`
   additionne les quantités facturées rapprochées, permettant à plusieurs factures de
   couvrir une seule commande (livraisons échelonnées).
6. **Aucune écriture comptable** — la commande ne déclenche pas d'écriture d'engagement au
  MVP (budget engagements hors scope). L'écriture est générée à la réception de la facture
  dans `:purchasing`.

### Cycle de vie des objets

- `PurchaseOrder` : `DRAFT → SUBMITTED → RECEIVED → CLOSED`
  - `DRAFT → SUBMITTED` : via `POST /{poId}/change-status?status=SUBMITTED` (validation
    interne — la commande est gelée).
  - `SUBMITTED → RECEIVED` : via `POST /{poId}/change-status?status=RECEIVED` (marchandises
    livrées — la commande est éligible au 3-way match).
  - `RECEIVED → CLOSED` : via `POST /{poId}/change-status?status=CLOSED` (toutes les
    factures attendues ont été rapprochées).
- `PurchaseOrderLine` : créées en même temps que la commande, immuables après `SUBMITTED`.
  Seul `receivedQuantity` est muté (par `ThreeWayMatchService`).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/purchase-orders` | Liste les commandes fournisseurs | — |
| GET | `/api/v1/companies/{companyId}/purchase-orders/{poId}` | Récupère une commande par ID | 404 `PurchaseOrder` |
| POST | `/api/v1/companies/{companyId}/purchase-orders` | Crée une commande `DRAFT` avec ses lignes. Corps : `{supplierId, orderNumber, orderDate, currency?, status?, lines: [{itemId?, description, quantity, unitPrice}]}`. `totalAmount` calculé automatiquement. | 409 `PURCHASE_ORDER_NUMBER_EXISTS`, 422 champs invalides |
| POST | `/api/v1/companies/{companyId}/purchase-orders/{poId}/change-status?status=` | Change le statut d'une commande : `DRAFT → SUBMITTED → RECEIVED → CLOSED`. | 404, 409 transition invalide |
| POST | `/api/v1/companies/{companyId}/purchase-orders/3-way-match?invoiceId=` | **3-way match** entre une facture (`PurchaseInvoice` de `:purchasing`) et une commande. Vérifie (a) qu'une commande existe pour le fournisseur, (b) que les quantités facturées ≤ quantités commandées, (c) que les prix facturés = prix commandés. Retourne `ThreeWayMatchResult {invoiceId, purchaseOrderId, matches, discrepancies[]}`. | 404 `PurchaseInvoice` |

### Types d'écarts `ThreeWayMatchResult.Discrepancy`

Le champ `discrepancies` est une liste d'objets `{type, detail, invoiceLineId, poLineId,
expected, actual}`. Les types d'écarts possibles :

| `type` | Détail |
|---|---|
| `NO_PURCHASE_ORDER` | Aucune commande n'existe pour le fournisseur de la facture (engagement non autorisé). |
| `QUANTITY_EXCEEDED` | Quantité facturée > quantité commandée sur une ligne (sur-facturation). |
| `PRICE_MISMATCH` | Prix unitaire facturé ≠ prix commandé sur une ligne (écart commercial). |
| `NO_MATCHING_PO_LINE` | La ligne de facture ne correspond à aucune ligne de commande (article ou description inconnus). |

Si `matches=true`, `discrepancies` est vide. Sinon, `matches=false` et la liste détaille les
écarts constatés.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ApplicationEventPublisher`.
- `:audit-trail` — auditing.
- `:company` — `ModuleAccessGuard` optionnel (le module est toujours-actif au MVP, mais la
  dépendance est posée pour permettre un gating futur).
- `:purchasing` — `PurchaseInvoice`, `PurchaseInvoiceLine`, `PurchaseInvoiceRepository`
  (pour le 3-way match — lecture seule). Le 3-way match vérifie la cohérence des lignes de
  facture d'achat avec les lignes de commande.
- `:third-parties` — `ThirdParty` (type `SUPPLIER`), `ThirdPartyRepository` (pour résoudre
  le nom du fournisseur dans `PurchaseOrderResponse.supplierName`).

### Modules qui dépendent de celui-ci

- Aucun au MVP. Le 3-way match est appelé explicitement par l'utilisateur via
  `POST /purchase-orders/3-way-match?invoiceId=` — pas de déclenchement automatique à la
  réception d'une facture (TODO v4.9 : auto-match optionnel).

### Événements publiés / consommés

- **Publie** : aucun au MVP (une `PurchaseOrderClosedEvent` serait à ajouter si un
  consommateur est identifié — ex. déclencher la clôture automatique des engagements
  budgétaires quand le chaînage sera implémenté).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V48__purchase_orders.sql` — **V48 — Finding #10**. Crée
  les tables `purchase_order` (entête : `supplier_id`, `order_number`, `order_date`,
  `status`, `currency`, `total_amount`) et `purchase_order_line` (lignes : `po_id`,
  `item_id`, `description`, `quantity`, `unit_price`, `received_quantity`, `line_total`).
  Contraintes : `chk_po_status` (4 valeurs), `chk_po_total` (>= 0), `chk_pol_quantity`
  (> 0). Index : `(company_id)`, `(company_id, supplier_id)`, `(supplier_id)`,
  `(company_id, order_number)` UNIQUE.

## Points d'attention

- ⚠️ **Aucune écriture d'engagement** — la commande n'est pas comptabilisée en engagement
  budgétaire au MVP (pas de débit 408 / crédit 607 à la commande). La commande ne sert que
  de référence au 3-way match.
- ⚠️ **3-way match manuel** — le match n'est pas déclenché automatiquement à la réception
  d'une facture (`POST /purchase-invoices/{id}/receive` dans `:purchasing`). L'utilisateur
  doit explicitement appeler `POST /purchase-orders/3-way-match?invoiceId=`. Un auto-match
  optionnel est prévu en v4.9.
- ⚠️ **Pas de pagination sur `GET /purchase-orders`** — retourne toutes les commandes. À
  limiter via un filtre côté UI pour les entreprises avec un volume important de commandes.
- ⚠️ **Pas de PATCH implémenté** — la modification d'une commande `DRAFT` n'est pas
  implémentée au MVP (l'utilisateur doit recréer la commande si elle doit être modifiée
  avant `SUBMITTED`).
- ⚠️ **`receivedQuantity` non vérifié contre `quantity`** — le 3-way match vérifie les
  écarts, mais n'empêche pas la réception d'une quantité supérieure à la commande (le
  résultat renvoie `QUANTITY_EXCEEDED` mais n'annule pas la facture — c'est à l'utilisateur
  de décider).

## Tests

Couvert par `ThreeWayMatchIntegrationTest` dans `:app` — cycle de vie complet (création →
soumission → réception → clôture), 3-way match OK (matches=true), 3-way match avec écarts
(`NO_PURCHASE_ORDER`, `QUANTITY_EXCEEDED`, `PRICE_MISMATCH`, `NO_MATCHING_PO_LINE`),
incrément de `receivedQuantity` sur plusieurs factures rapprochées.

## Activation

Le module `:purchase-orders` est **toujours-actif** (always-on) au MVP. Pas de gate
`PURCHASE_ORDERS` dans `BusinessTypeModuleService` ni de `ModuleAccessGuard` sur ses
endpoints. Toute entreprise peut créer des commandes fournisseurs et exécuter un 3-way
match.
