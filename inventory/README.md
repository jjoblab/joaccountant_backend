# Module : inventory

> Stock, valorisation FIFO / coût moyen pondéré, calcul du COGS et alertes de réapprovisionnement.

## Rôle du module

Le module `:inventory` gère le stock de marchandises et produits. Il est **sectoriel** :
activé uniquement pour les types métier dont le mapping `business_type_module` inclut
`INVENTORY` (par défaut `RETAIL_COMMERCE`). Pour les autres types, l'endpoint retourne
`403 MODULE_NOT_ENABLED` — voir `ModuleAccessGuard` (restructuration :company §7.2). Il
fonctionne pour les 6 référentiels — les comptes de stock et de COGS sont référencés
par ID et validés sémantiquement via `ReportingClass` (audit M9).

Le module **génère des écritures comptables** via `:accounting-engine` pour les **sorties**
de stock (COGS) uniquement. Les **entrées** de stock ne génèrent **aucune écriture**
(audit M10 similaire à fixed-assets — l'entrée de stock est supposée constatée au moment de
la facture fournisseur, mais ce chaînage n'est pas implémenté).

## Ce qu'il fait précisément

### Entités principales

- `Warehouse` — entrepôt. `(companyId, label)` unique. Champs : `label`, `active`.
- `Item` — article / produit stocké. `(companyId, sku)` unique. Champs : `sku`, `label`,
  `unitOfMeasure`, `costingMethod` (FIFO/WEIGHTED_AVERAGE), `reorderThreshold` (nullable),
  `inventoryAccountId` (compte de stock — ex. 30 SYSCOHADA), `cogsAccountId` (compte de
  COGS — ex. 603).
- `StockMove` — mouvement de stock. Champs : `itemId`, `warehouseId`, `toWarehouseId`
  (pour TRANSFER), `moveDate`, `direction` (IN/OUT/TRANSFER), `quantity`, `unitCost`,
  `totalCost`, `sourceDocument`, `journalEntryId` (ID de l'écriture COGS pour les OUT, null
  pour IN/TRANSFER).
- `StockValuationLayer` — couche de valorisation FIFO. Champs : `itemId`, `warehouseId`,
  `remainingQuantity`, `unitCost`, `createdAt`. Consommée par les sorties FIFO dans l'ordre
  chronologique.
- `CostingMethod` (enum) — `FIFO`, `WEIGHTED_AVERAGE`. **LIFO non implémenté** (IFRS
  l'interdit).
- `StockMoveDirection` (enum) — `IN`, `OUT`, `TRANSFER`.

### Règles métier clés

1. **FIFO** — les sorties consomment les couches `StockValuationLayer` dans l'ordre
   chronologique (première entrée = première sortie). Le `unitCost` de la sortie est la
   moyenne pondérée des couches consommées.
2. **WEIGHTED_AVERAGE** — le `unitCost` est recalculé à chaque entrée :
   `(valeur stock actuel + valeur entrée) / (quantité stock + quantité entrée)`. Les sorties
   utilisent ce coût moyen.
3. **Stock négatif rejeté** — une sortie qui ferait passer le stock total en dessous de 0
   est refusée (409 `INSUFFICIENT_STOCK`).
4. **Écriture COGS uniquement pour OUT** — `D COGS / C Stock` pour le `totalCost` du
   mouvement. Source `sourceModule = INVENTORY`. Les entrées et transferts ne génèrent pas
   d'écriture.
5. **Idempotence synthétique** — `idempotencyKey = "inventory-cogs-" + move.getId()` au
   postage de l'écriture COGS.
6. **`approverEmails = List.of()`** (audit M12) — l'écriture COGS est postée via
   `accountingEngineService.postJournalEntry(companyId, entryId, List.of())`. Si une
   `ApprovalRule JOURNAL_ENTRY_POST` s'active, aucun approbateur n'est notifié.
7. **Validation sémantique des comptes** (audit M9) — `validateAccount` vérifie la
   `reportingClass` attendue :
   - `inventoryAccountId` → doit être `ACTIF`
   - `cogsAccountId` → doit être `CHARGES`
   422 `ACCOUNT_WRONG_REPORTING_CLASS` si un compte n'a pas la classe attendue.
8. **Seuil de réapprovisionnement** — si `item.reorderThreshold` est renseigné et que le
   stock total passe sous ce seuil après un mouvement OUT, un `LowStockEvent` est publié
   (consommé par `:notifications`).

### Cycle de vie des objets

- `Item` : créé → désactivable (pas d'endpoint public — opération DB manuelle). Pas de
  suppression physique.
- `StockMove` : créé → immuable. Le `journalEntryId` est renseigné après postage de
  l'écriture COGS (pour les OUT). Pas de modification ni suppression d'un mouvement —
  correction par contre-passation via `:accounting-engine`.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/inventory/warehouses` | Crée un entrepôt | 409 `WAREHOUSE_LABEL_EXISTS`, 422 `WAREHOUSE_LABEL_REQUIRED` |
| GET | `/api/v1/companies/{companyId}/inventory/items` | Liste les articles du tenant, triés par SKU ascendant (audit E-8, correction #1) | — |
| POST | `/api/v1/companies/{companyId}/inventory/items` | Crée un article + valider comptes | 409 `ITEM_SKU_EXISTS`, 422 `ITEM_SKU_REQUIRED`/`ITEM_LABEL_REQUIRED`/`UNIT_OF_MEASURE_REQUIRED`/`ACCOUNT_NOT_FOUND`/`ACCOUNT_INACTIVE`/`ACCOUNT_WRONG_REPORTING_CLASS` |
| POST | `/api/v1/companies/{companyId}/inventory/stock-moves` | Mouvement de stock (IN/OUT/TRANSFER) — génère écriture COGS pour OUT | 404 `Item`/`Warehouse`, 409 `INSUFFICIENT_STOCK`, 422 `QUANTITY_INVALID`/`JOURNAL_OD_NOT_FOUND`/`PERIOD_NOT_FOUND` |
| GET | `/api/v1/companies/{companyId}/inventory/stock-moves?from=&to=` | **Nouveau (Part E2)** — Liste tous les mouvements de stock (IN/OUT/TRANSFER) dont la `moveDate` est comprise entre `from` et `to` (inclus), triés par `moveDate` décroissant. Si `from`/`to` sont omis, borne inférieure = `1900-01-01` et borne supérieure = aujourd'hui. Utilisé pour le registre des mouvements de stock et l'export CSV `stock_movement_register` (`:reporting`). | — |
| GET | `/api/v1/companies/{companyId}/inventory/items/{itemId}/valuation` | Valorisation de stock d'un article (quantité totale + valeur totale) | 404 `Item` |
| GET | `/api/v1/companies/{companyId}/inventory/valuation` | **Nouveau (Part E1)** — Valorisation agrégée de tout le stock. Retourne une ligne par couple (article, entrepôt) ayant du stock restant : `{sku, label, warehouse, quantity, unitCost, totalValue}`. Utilisé pour le rapport de valorisation d'inventaire et l'export CSV `inventory_valuation` (`:reporting`). | — |

> **Note sur la pagination** : aucun des endpoints de liste (`GET /items`,
> `GET /stock-moves`, `GET /valuation`) n'est paginé au MVP. Le client mobile doit
> implémenter une recherche/filtre côté UI pour les entreprises avec un grand catalogue.

> **Stabilisation 2026-07-25 (Part E1/E2)** — ajout de 2 nouveaux endpoints de lecture
> agrégée : `GET /valuation` (Part E1) et `GET /stock-moves?from=&to=` (Part E2). Ils
> répondent à l'audit E-8 « Aucun endpoint de liste » : avant cette correction, le mobile
> ne pouvait pas lister les mouvements ni la valorisation agrégée via l'API — il devait
> interroger la base ou utiliser `:reporting`. Ces endpoints servent aussi de **vue JSON
> source** pour les exports CSV `inventory_valuation` et `stock_movement_register`
> (`:reporting`) — le mobile peut les appeler directement pour l'affichage in-app
> (tableau/graphe), puis déclencher le download CSV via `:reporting` pour le bouton
> « Télécharger ».

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `ReportingClass`, `ApplicationEventPublisher`.
- `:chart-of-accounts` — `Account`, `AccountRepository` (validation sémantique — audit M9).
- `:accounting-engine` — `AccountingEngineService.createJournalEntry` +
  `postJournalEntry`, `JournalRepository` (recherche du journal `"OD"`).

### Modules qui dépendent de celui-ci

- `:invoicing` — référence les articles pour les factures de marchandises (lien faible via
  SKU, pas par ID — pas de jointure dure).
- `:notifications` — consomme `LowStockEvent` pour alerter l'utilisateur.
- `:reporting` — valorisation de stock agrégée pour le bilan (compte de stock).

### Événements publiés / consommés

- **Publie** : `StockMoveCreatedEvent` (à chaque mouvement), `LowStockEvent` (quand le
  stock passe sous le seuil).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V11_001__inventory.sql` — tables `warehouse`, `item`,
  `stock_valuation_layer`, `stock_move`. Unique `(company_id, sku)` sur `item`. CHECK sur
  `costing_method` (2 valeurs), `direction` (3 valeurs), `quantity > 0`. Index sur
  `(company_id, item_id)` et `(warehouse_id, item_id)` pour `stock_valuation_layer`.

## Repository — méthodes de lecture (Part E1/E2)

Les repositories `ItemRepository`, `WarehouseRepository`, `StockMoveRepository` (Spring
Data JPA) exposent les méthodes de lecture suivantes. Aucune écriture custom — Spring Data
génère les requêtes à partir du nom des méthodes.

| Repository | Méthode | Usage |
|---|---|---|
| `ItemRepository` | `findByCompanyIdOrderBySku(companyId)` | `GET /items` et `GET /valuation` (Part E1) — index les libellés/SKU pour la valorisation agrégée. |
| `WarehouseRepository` | `findByCompanyIdOrderByLabel(companyId)` | `GET /valuation` (Part E1) — itère sur les entrepôts pour produire une ligne par couple (article, entrepôt). |
| `StockMoveRepository` | `findByItemIdOrderByMoveDate(itemId)` | Calcul de valorisation d'un article (`getValuation` — reconstitue le stock au FIFO/PMP). |
| `StockMoveRepository` | `findByItemIdAndWarehouseIdOrderByMoveDate(itemId, warehouseId)` | Calcul de valorisation d'un article dans un entrepôt spécifique. |
| `StockMoveRepository` | `findByCompanyIdOrderByMoveDateDesc(companyId)` | **Nouveau (Part E2)** — `GET /stock-moves` sans filtre date. |
| `StockMoveRepository` | `findByCompanyIdAndMoveDateBetweenOrderByMoveDateDesc(companyId, start, end)` | **Nouveau (Part E2)** — `GET /stock-moves?from=&to=`. Si `from`/`to` sont nuls, le service remplace par `1900-01-01` et `LocalDate.now()`. Aussi utilisé par `:reporting` pour l'export CSV `stock_movement_register`. |

## Points d'attention (hérités de l'audit)

- ⚠️ **M9 — `validateAccount` enrichi** : la version initiale ne validait que
  l'existence/tenant/activité. Désormais, la `reportingClass` attendue est vérifiée
  (`inventoryAccountId` → ACTIF, `cogsAccountId` → CHARGES). 422
  `ACCOUNT_WRONG_REPORTING_CLASS` si incohérent. **Breaking pour un client qui fournissait
  des comptes sémantiquement incohérents**.
- ⚠️ **Entrées de stock non comptabilisées** — `IN` ne génère aucune écriture (pas de
  D Stock / C Fournisseur). Conséquence : le solde du compte de stock ne reflète pas la
  valeur réelle du stock — sauf si l'utilisateur poste séparément les entrées via
  `:accounting-engine`. Le client mobile doit informer l'utilisateur que la réception de
  marchandises doit être comptabilisée manuellement (ou via la facture fournisseur en
  Phase 12 — mais le chaînage n'est pas implémenté).
- ⚠️ **M14 — Arrondis à 4 décimales codés en dur** — `setScale(4, HALF_UP)` sur COGS,
  average cost, consumption ratio. Pour une devise 0-décimales (XOF/XAF/JPY), les montants
  sont stockés avec 4 décimales.
- ✅ **Endpoints de liste ajoutés (Part E1/E2, audit E-8 corrigé)** — `GET /items`,
  `GET /stock-moves?from=&to=`, `GET /valuation` sont désormais exposés. Le client mobile
  peut lister les articles, les mouvements et la valorisation agrégée sans passer par la
  base ou `:reporting`. Les 3 endpoints ne sont pas paginés au MVP — prévoir une
  recherche/filtre côté UI pour les grandes entreprises.
- ⚠️ **Code journal `"OD"` en dur** — l'écriture COGS cherche le journal de code `"OD"`. Si
  l'entreprise n'a pas créé ce journal, lève `JOURNAL_OD_NOT_FOUND`. Le client mobile doit
  guider l'utilisateur vers la création d'un journal `"OD"` après initialisation du plan.
- ⚠️ **Pas de contrôle de rôle** sur les endpoints mutatifs (audit B5) — un `VIEWER` peut
  créer des entrepôts, articles, mouvements de stock.

## Tests

Couvert par `InventoryIntegrationTest` dans `:app` (11 tests) — création d'entrepôt,
article, mouvements IN/OUT, FIFO, COGS, valorisation, stock négatif rejeté, seuil de
réapprovisionnement.

## Activation (restructuration :company §7)

Le module `:inventory` est **sectoriel** : son utilisation exige que le module
`INVENTORY` soit activé pour la société. Le check se fait en tête de chaque endpoint
via `ModuleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY)` (composant du
module `:company`).

**Codes d'erreur** : `403 MODULE_NOT_ENABLED` si le module n'est pas activé pour la société.
Le message indique explicitement que l'activation peut se faire via
`POST /api/v1/companies/{id}/wizard/complete` ou via l'étape 8 du wizard (sélection
manuelle pour le type métier `CUSTOM`).

Le module est auto-activé à la complétion du wizard pour les types métier dont le mapping
`business_type_module` inclut `INVENTORY` (voir `V3_003__business_type.sql`).
