package jo.accountant.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.currency.CurrencyRoundingService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.inventory.dto.CreateItemRequest;
import jo.accountant.inventory.dto.CreateStockMoveRequest;
import jo.accountant.inventory.dto.CreateWarehouseRequest;
import jo.accountant.inventory.dto.InventoryValuationResponse;
import jo.accountant.inventory.dto.ItemResponse;
import jo.accountant.inventory.dto.ItemValuation;
import jo.accountant.inventory.dto.StockMoveResponse;
import jo.accountant.inventory.entity.CostingMethod;
import jo.accountant.inventory.entity.Item;
import jo.accountant.inventory.entity.StockMove;
import jo.accountant.inventory.entity.StockMoveDirection;
import jo.accountant.inventory.entity.StockValuationLayer;
import jo.accountant.inventory.entity.Warehouse;
import jo.accountant.inventory.event.LowStockEvent;
import jo.accountant.inventory.event.StockMoveCreatedEvent;
import jo.accountant.inventory.repository.ItemRepository;
import jo.accountant.inventory.repository.StockMoveRepository;
import jo.accountant.inventory.repository.StockValuationLayerRepository;
import jo.accountant.inventory.repository.WarehouseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion de stock (§13 Phase 9).
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Création d'entrepôts et d'articles</li>
 *   <li>Mouvements de stock (IN/OUT/TRANSFER)</li>
 *   <li>Valorisation FIFO ou coût moyen pondéré</li>
 *   <li>Génération d'écritures COGS pour les sorties (sourceModule=INVENTORY)</li>
 *   <li>Alerte de seuil de réapprovisionnement (événement LowStockEvent)</li>
 * </ul>
 *
 * <p><strong>LIFO n'est pas implémenté</strong> — IFRS l'interdit. Aucun flag "LIFO"
 * n'est exposé nulle part, même désactivé.
 */
@Service
public class InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryService.class);

    private final WarehouseRepository warehouseRepository;
    private final ItemRepository itemRepository;
    private final StockMoveRepository stockMoveRepository;
    private final StockValuationLayerRepository layerRepository;
    private final AccountRepository accountRepository;
    private final JournalRepository journalRepository;
    private final AccountingEngineService accountingEngineService;
    private final CompanyRepository companyRepository;
    private final CurrencyRoundingService roundingService;
    private final ApplicationEventPublisher events;

    public InventoryService(WarehouseRepository warehouseRepository,
                            ItemRepository itemRepository,
                            StockMoveRepository stockMoveRepository,
                            StockValuationLayerRepository layerRepository,
                            AccountRepository accountRepository,
                            JournalRepository journalRepository,
                            AccountingEngineService accountingEngineService,
                            CompanyRepository companyRepository,
                            CurrencyRoundingService roundingService,
                            ApplicationEventPublisher events) {
        this.warehouseRepository = warehouseRepository;
        this.itemRepository = itemRepository;
        this.stockMoveRepository = stockMoveRepository;
        this.layerRepository = layerRepository;
        this.accountRepository = accountRepository;
        this.journalRepository = journalRepository;
        this.accountingEngineService = accountingEngineService;
        this.companyRepository = companyRepository;
        this.roundingService = roundingService;
        this.events = events;
    }

    /**
     * Résout la devise contextuelle pour un calcul d'arrondi (audit M14).
     *
     * <p>Les entités du module inventory (Item, StockMove, StockValuationLayer) ne portent pas
     * de champ devise — la valorisation est toujours en devise fonctionnelle de l'entreprise.
     * On récupère donc {@link Company#getFunctionalCurrency()} (avec fallback "HTG" si la
     * company est introuvable — ne devrait pas arriver car on vient de la charger).
     */
    private String resolveCurrency(UUID companyId) {
        return companyRepository.findById(companyId)
            .map(Company::getFunctionalCurrency)
            .orElse("HTG");
    }

    // --- Entrepôts ---

    @Transactional
    public Warehouse createWarehouse(UUID companyId, CreateWarehouseRequest req) {
        if (req.label() == null || req.label().isBlank()) {
            throw new ValidationException("WAREHOUSE_LABEL_REQUIRED", "Le libellé est requis");
        }
        if (warehouseRepository.findByCompanyIdAndLabel(companyId, req.label().trim()).isPresent()) {
            throw new ConflictException("WAREHOUSE_LABEL_EXISTS",
                "Un entrepôt avec ce libellé existe déjà");
        }
        Warehouse wh = new Warehouse();
        wh.setCompanyId(companyId);
        wh.setLabel(req.label().trim());
        return warehouseRepository.save(wh);
    }

    // --- Articles ---

    /**
     * Liste tous les articles du tenant, triés par SKU ascendant (audit E-8, correction #1).
     *
     * <p>Endpoint exposé : {@code GET /api/v1/companies/{companyId}/inventory/items}.
     * Avant ce correctif, le mobile {@code InventoryRepository.loadItems()} appelait
     * un endpoint inexistant et recevait un 404 systématique — l'écran Inventaire
     * restait vide en production.
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> listItems(UUID companyId) {
        return itemRepository.findByCompanyIdOrderBySku(companyId).stream()
            .map(this::toItemResponse)
            .toList();
    }

    /**
     * Récupère un article par son ID — correction 2026-07-26.
     *
     * <p>Avant, le mobile ne pouvait récupérer un article qu'en parcourant le cache local.
     */
    @Transactional(readOnly = true)
    public ItemResponse getItem(UUID companyId, UUID itemId) {
        jo.accountant.inventory.entity.Item item = itemRepository.findById(itemId)
            .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException("Item", itemId));
        if (!item.getCompanyId().equals(companyId)) {
            throw new jo.accountant.core.exception.NotFoundException("Item", itemId);
        }
        return toItemResponse(item);
    }

    @Transactional
    public ItemResponse createItem(UUID companyId, CreateItemRequest req) {
        if (req.sku() == null || req.sku().isBlank()) {
            throw new ValidationException("SKU_REQUIRED", "Le SKU est requis");
        }
        if (itemRepository.findByCompanyIdAndSku(companyId, req.sku().trim()).isPresent()) {
            throw new ConflictException("SKU_EXISTS", "Un article avec ce SKU existe déjà");
        }
        validateAccount(companyId, req.inventoryAccountId(), "inventory_account",
            jo.accountant.core.framework.ReportingClass.ACTIF);
        validateAccount(companyId, req.cogsAccountId(), "cogs_account",
            jo.accountant.core.framework.ReportingClass.CHARGES);

        Item item = new Item();
        item.setCompanyId(companyId);
        item.setSku(req.sku().trim());
        item.setLabel(req.label().trim());
        item.setUnitOfMeasure(req.unitOfMeasure().trim());
        item.setCostingMethod(req.costingMethod());
        item.setReorderThreshold(req.reorderThreshold());
        item.setInventoryAccountId(req.inventoryAccountId());
        item.setCogsAccountId(req.cogsAccountId());
        Item saved = itemRepository.save(item);
        return toItemResponse(saved);
    }

    /** Mappe l'entité {@link Item} vers le DTO {@link ItemResponse} (audit E-8 #1). */
    private ItemResponse toItemResponse(Item item) {
        return new ItemResponse(
            item.getId(),
            item.getCompanyId(),
            item.getSku(),
            item.getLabel(),
            item.getUnitOfMeasure(),
            item.getCostingMethod(),
            item.getReorderThreshold(),
            item.getInventoryAccountId(),
            item.getCogsAccountId()
        );
    }

    // --- Mouvements de stock ---

    /**
     * Crée un mouvement de stock.
     *
     * <p>Pour IN : crée une couche FIFO (ou met à jour le coût moyen).
     * Pour OUT : consomme les couches FIFO (ou utilise le coût moyen), calcule le COGS,
     *   génère une écriture comptable, vérifie le stock négatif, publie LowStockEvent si seuil franchi.
     * Pour TRANSFER : sort de l'entrepôt source et entre dans l'entrepôt destination.
     */
    @Transactional
    public StockMoveResponse postStockMove(UUID companyId, CreateStockMoveRequest req) {
        Item item = loadItem(companyId, req.itemId());
        Warehouse warehouse = loadWarehouse(companyId, req.warehouseId());

        if (req.direction() == StockMoveDirection.TRANSFER) {
            throw new ValidationException("TRANSFER_NOT_FULLY_SUPPORTED",
                "TRANSFER n'est pas encore supporté en Phase 9 — utiliser IN + OUT séparément");
        }

        StockMove move = new StockMove();
        move.setCompanyId(companyId);
        move.setItemId(item.getId());
        move.setWarehouseId(warehouse.getId());
        move.setMoveDate(req.moveDate());
        move.setDirection(req.direction());
        move.setQuantity(req.quantity());
        move.setSourceDocument(req.sourceDocument());
        // Initialiser avec des valeurs par défaut — seront mises à jour par handleIn/handleOut
        move.setUnitCost(BigDecimal.ZERO);
        move.setTotalCost(BigDecimal.ZERO);

        // Sauvegarder d'abord pour obtenir un ID (utilisé dans la clé idempotente du COGS)
        move = stockMoveRepository.save(move);

        if (req.direction() == StockMoveDirection.IN) {
            handleIn(move, item, req);
        } else if (req.direction() == StockMoveDirection.OUT) {
            handleOut(move, item, req);
        }

        StockMove saved = stockMoveRepository.save(move);
        events.publishEvent(new StockMoveCreatedEvent(saved, TenantContext.getUserId()));

        // Vérifier le seuil de réapprovisionnement
        checkReorderThreshold(companyId, item);

        return toResponse(saved);
    }

    private void handleIn(StockMove move, Item item, CreateStockMoveRequest req) {
        if (req.unitCost() == null || req.unitCost().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("UNIT_COST_REQUIRED",
                "Le coût unitaire est requis pour une entrée de stock");
        }
        move.setUnitCost(req.unitCost());
        move.setTotalCost(req.unitCost().multiply(req.quantity()));

        // Pour FIFO ET WEIGHTED_AVERAGE : créer une couche de valorisation.
        // FIFO : les couches sont consommées dans l'ordre (plus ancienne d'abord).
        // WEIGHTED_AVERAGE : les couches sont consommées proportionnellement (le coût moyen
        // est calculé à la sortie en divisant la valeur totale par la quantité totale).
        StockValuationLayer layer = new StockValuationLayer();
        layer.setCompanyId(move.getCompanyId());
        layer.setItemId(item.getId());
        layer.setWarehouseId(move.getWarehouseId());
        layer.setQuantityReceived(req.quantity());
        layer.setQuantityRemaining(req.quantity());
        layer.setUnitCost(req.unitCost());
        layer.setReceiptDate(req.moveDate());
        layer.setSourceStockMoveId(move.getId());
        layerRepository.save(layer);

        // Audit E-A (correction) : générer l'écriture d'entrée stock si un compte de
        // contrepartie est fourni (Fournisseur pour achat à crédit, ou Trésorerie pour achat
        // au comptant). Sans cette écriture, le compte de stock reste à 0 puis devient négatif
        // quand le COGS le crédite à la sortie — d'où des bilans avec actif négatif.
        if (req.counterpartyAccountId() != null) {
            generateStockReceiptEntry(move, item, req.counterpartyAccountId(), move.getTotalCost());
        }
    }

    /**
     * Génère l'écriture comptable d'entrée en stock (audit E-A).
     *
     * <p>L'écriture est :
     * <ul>
     *   <li>Débit : {@code item.inventoryAccountId} (compte de stock ACTIF) pour le coût total</li>
     *   <li>Crédit : {@code counterpartyAccountId} (Fournisseur PASSIF ou Trésorerie ACTIF)
     *       pour le même montant</li>
     * </ul>
     *
     * <p>Idempotence : clé déterministe {@code "inventory-receipt-" + move.getId()}.
     * Postage direct (sans workflow 4-yeux) — l'entrée de stock est validée par le BOOKKEEPER
     * qui crée le mouvement.
     */
    private void generateStockReceiptEntry(StockMove move, Item item, UUID counterpartyAccountId, BigDecimal totalCost) {
        Account inventoryAccount = accountRepository.findById(item.getInventoryAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte de stock introuvable : " + item.getInventoryAccountId()));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!inventoryAccount.getCompanyId().equals(move.getCompanyId())) {
            throw new NotFoundException("Account", item.getInventoryAccountId().toString());
        }
        Account counterpartyAccount = accountRepository.findById(counterpartyAccountId)
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte de contrepartie introuvable : " + counterpartyAccountId));
        if (!counterpartyAccount.getCompanyId().equals(move.getCompanyId())) {
            throw new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte de contrepartie introuvable : " + counterpartyAccountId);
        }

        String journalCode = journalRepository.findByCompanyIdAndCode(move.getCompanyId(), "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                "Journal OD introuvable. Créer un journal de code 'OD'."));

        List<LineDto> lines = new ArrayList<>();
        // Débit Stock (coût total)
        lines.add(new LineDto(inventoryAccount.getCode(), null, totalCost, null,
            "Entrée stock — " + item.getSku(), List.of()));
        // Crédit Fournisseur ou Trésorerie
        String counterpartyDesc = counterpartyAccount.getReportingClass() == jo.accountant.core.framework.ReportingClass.PASSIF
            ? "Fournisseur — entrée stock " + item.getSku()
            : "Trésorerie — entrée stock " + item.getSku();
        lines.add(new LineDto(counterpartyAccount.getCode(), null, null, totalCost,
            counterpartyDesc, List.of()));

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, move.getMoveDate(),
            "Entrée stock — " + item.getSku(),
            lines, JournalEntrySourceModule.INVENTORY);

        String idempotencyKey = "inventory-receipt-" + move.getId();
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            move.getCompanyId(), idempotencyKey, entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            move.getCompanyId(), entry.id(), List.of());

        move.setJournalEntryId(posted.id());
        LOG.info("Écriture d'entrée stock générée : move={} entry={} reference={}",
            move.getId(), posted.id(), posted.reference());
    }

    private void handleOut(StockMove move, Item item, CreateStockMoveRequest req) {
        // Vérifier le stock disponible
        BigDecimal availableStock = getAvailableStock(move.getCompanyId(), item.getId(), move.getWarehouseId());
        if (availableStock.compareTo(req.quantity()) < 0) {
            throw new ConflictException("INSUFFICIENT_STOCK",
                "Stock insuffisant : disponible=" + availableStock + " demandé=" + req.quantity());
        }

        BigDecimal cogs;
        if (item.getCostingMethod() == CostingMethod.FIFO) {
            cogs = consumeFifoLayers(move, item, req.quantity());
        } else {
            cogs = calculateWeightedAverage(move, item, req.quantity());
        }

        // Audit M14 : arrondi currency-aware (au lieu de setScale(4) en dur).
        String currencyCode = resolveCurrency(move.getCompanyId());
        move.setUnitCost(roundingService.round(currencyCode, cogs.divide(req.quantity(),
            CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP)));
        move.setTotalCost(roundingService.round(currencyCode, cogs));

        // Générer l'écriture COGS
        generateCogsEntry(move, item, cogs);
    }

    /**
     * Consomme les couches FIFO (plus anciennes d'abord) et retourne le COGS total.
     *
     * <p><b>Audit v4.7 §3.2 Finding HAUT — FIX race condition</b> : utilise
     * {@link StockValuationLayerRepository#findFifoLayersForUpdate} qui fait un
     * {@code SELECT ... FOR UPDATE} pessimiste. Sans ce verrou, deux mouvements OUT simultanés
     * pouvaient consommer la même couche FIFO → surconsommation et stock négatif.
     */
    private BigDecimal consumeFifoLayers(StockMove move, Item item, BigDecimal quantityToConsume) {
        // SELECT FOR UPDATE — verrouille les couches jusqu'au commit de la transaction
        List<StockValuationLayer> layers = layerRepository.findFifoLayersForUpdate(
            move.getCompanyId(), item.getId(), move.getWarehouseId());

        BigDecimal remaining = quantityToConsume;
        BigDecimal cogs = BigDecimal.ZERO;

        for (StockValuationLayer layer : layers) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal consume = layer.getQuantityRemaining().min(remaining);
            cogs = cogs.add(consume.multiply(layer.getUnitCost()));
            layer.setQuantityRemaining(layer.getQuantityRemaining().subtract(consume));
            layerRepository.save(layer);
            remaining = remaining.subtract(consume);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            // Ne devrait pas arriver — la vérification de stock disponible a déjà été faite
            throw new ConflictException("STOCK_LAYERS_EXHAUSTED",
                "Couches de stock insuffisantes malgré le stock total disponible — incohérence");
        }

        return cogs;
    }

    /**
     * Calcule le COGS au coût moyen pondéré.
     *
     * <p>En Phase 9 simplifié : le coût moyen = somme des (quantité × coût) de toutes les
     * couches restantes / somme des quantités restantes. Les couches sont créées pour FIFO
     * mais aussi utilisées pour WEIGHTED_AVERAGE (pour avoir une source de vérité unique).
     */
    private BigDecimal calculateWeightedAverage(StockMove move, Item item, BigDecimal quantityToConsume) {
        List<StockValuationLayer> layers = layerRepository.findFifoLayers(
            move.getCompanyId(), item.getId(), move.getWarehouseId());

        if (layers.isEmpty()) {
            throw new ConflictException("NO_STOCK_LAYERS",
                "Aucune couche de stock disponible pour le calcul du coût moyen");
        }

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (StockValuationLayer layer : layers) {
            totalValue = totalValue.add(layer.getQuantityRemaining().multiply(layer.getUnitCost()));
            totalQuantity = totalQuantity.add(layer.getQuantityRemaining());
        }

        // Audit M14 : arrondi currency-aware — les calculs intermédiaires gardent une précision
        // élevée (COMPUTATION_SCALE = 6), seul le résultat final (cogs) est arrondi au nombre
        // de décimales de la devise. Arrondir averageCost trop tôt produirait des COGS incorrects
        // (ex: 11.6667 arrondi à 11.67 × 80 = 933.60 au lieu de 933.3360).
        String currencyCode = resolveCurrency(move.getCompanyId());
        BigDecimal averageCost = totalValue.divide(totalQuantity,
            CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
        BigDecimal cogs = roundingService.round(currencyCode, averageCost.multiply(quantityToConsume));

        // Consommer proportionnellement de toutes les couches (précision interne, pas d'arrondi)
        BigDecimal consumptionRatio = quantityToConsume.divide(totalQuantity,
            CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
        for (StockValuationLayer layer : layers) {
            BigDecimal consume = layer.getQuantityRemaining().multiply(consumptionRatio)
                .setScale(CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
            layer.setQuantityRemaining(layer.getQuantityRemaining().subtract(consume));
            layerRepository.save(layer);
        }

        return cogs;
    }

    /**
     * Génère une écriture COGS : Débit COGS / Crédit Stock.
     */
    private void generateCogsEntry(StockMove move, Item item, BigDecimal cogs) {
        Account cogsAccount = accountRepository.findById(item.getCogsAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND", "Compte COGS introuvable"));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!cogsAccount.getCompanyId().equals(move.getCompanyId())) {
            throw new NotFoundException("Account", item.getCogsAccountId().toString());
        }
        Account inventoryAccount = accountRepository.findById(item.getInventoryAccountId())
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND", "Compte de stock introuvable"));
        // Audit v4.7 §6.2 — defense-in-depth
        if (!inventoryAccount.getCompanyId().equals(move.getCompanyId())) {
            throw new NotFoundException("Account", item.getInventoryAccountId().toString());
        }

        String journalCode = journalRepository.findByCompanyIdAndCode(move.getCompanyId(), "OD")
            .map(j -> j.getCode())
            .orElseThrow(() -> new ValidationException("JOURNAL_OD_NOT_FOUND",
                "Journal OD introuvable. Créer un journal de code 'OD'."));

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, move.getMoveDate(),
            "COGS — Sortie de stock " + item.getSku() + " × " + move.getQuantity(),
            List.of(
                new LineDto(cogsAccount.getCode(), null, cogs, null, "COGS", List.of()),
                new LineDto(inventoryAccount.getCode(), null, null, cogs, "Sortie de stock", List.of())
            ),
            JournalEntrySourceModule.INVENTORY
        );

        String idempotencyKey = "inventory-cogs-" + move.getId();
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            move.getCompanyId(), idempotencyKey, entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            move.getCompanyId(), entry.id(), List.of());

        move.setJournalEntryId(posted.id());
        LOG.info("COGS entry generated: item={} cogs={} entry={}", item.getSku(), cogs, posted.reference());
    }

    /**
     * Vérifie si le stock total d'un article passe sous son seuil de réapprovisionnement.
     * Si oui, publie un LowStockEvent (consommé par :notifications Phase 15).
     */
    private void checkReorderThreshold(UUID companyId, Item item) {
        if (item.getReorderThreshold() == null) return;

        BigDecimal totalStock = layerRepository.sumQuantityRemainingByItemId(companyId, item.getId());
        if (totalStock.compareTo(item.getReorderThreshold()) < 0) {
            events.publishEvent(new LowStockEvent(
                companyId, item.getId(), item.getSku(), item.getLabel(),
                totalStock, item.getReorderThreshold()));
            LOG.info("Low stock alert: item={} stock={} threshold={}", item.getSku(),
                totalStock, item.getReorderThreshold());
        }
    }

    private BigDecimal getAvailableStock(UUID companyId, UUID itemId, UUID warehouseId) {
        List<StockValuationLayer> layers = layerRepository.findFifoLayers(companyId, itemId, warehouseId);
        return layers.stream().map(StockValuationLayer::getQuantityRemaining)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- Valorisation ---

    @Transactional(readOnly = true)
    public ItemValuation getValuation(UUID companyId, UUID itemId) {
        Item item = loadItem(companyId, itemId);
        List<StockValuationLayer> layers = layerRepository
            .findByItemIdOrderByReceiptDate(item.getId());

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        List<ItemValuation.LayerDetail> layerDetails = new ArrayList<>();
        for (StockValuationLayer layer : layers) {
            if (layer.getQuantityRemaining().compareTo(BigDecimal.ZERO) > 0) {
                totalQuantity = totalQuantity.add(layer.getQuantityRemaining());
                totalValue = totalValue.add(layer.getQuantityRemaining().multiply(layer.getUnitCost()));
                layerDetails.add(new ItemValuation.LayerDetail(
                    layer.getId(), layer.getQuantityRemaining(), layer.getUnitCost(),
                    layer.getReceiptDate()));
            }
        }

        // Audit M14 : arrondi currency-aware (au lieu de setScale(4) en dur).
        String currencyCode = resolveCurrency(item.getCompanyId());
        BigDecimal avgCost = totalQuantity.compareTo(BigDecimal.ZERO) > 0
            ? roundingService.round(currencyCode,
                totalValue.divide(totalQuantity, CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP))
            : BigDecimal.ZERO;

        return new ItemValuation(item.getId(), item.getSku(), item.getLabel(),
            totalQuantity, totalValue, avgCost, layerDetails);
    }

    // --- Helpers ---

    private Item loadItem(UUID companyId, UUID itemId) {
        Item item = itemRepository.findById(itemId)
            .orElseThrow(() -> new NotFoundException("Item", itemId));
        if (!item.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Item", itemId);
        }
        return item;
    }

    private Warehouse loadWarehouse(UUID companyId, UUID warehouseId) {
        Warehouse wh = warehouseRepository.findById(warehouseId)
            .orElseThrow(() -> new NotFoundException("Warehouse", warehouseId));
        if (!wh.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Warehouse", warehouseId);
        }
        return wh;
    }

    /**
     * Valide qu'un compte existe, appartient au tenant, est actif — et (audit M9) qu'il a la
     * {@link jo.accountant.core.framework.ReportingClass} attendue pour son rôle.
     *
     * <p>Avant cette correction, {@code validateAccount} ne vérifiait que l'existence/l'activité,
     * ce qui permettait d'assigner un compte de PASSIF comme compte de stock ou un compte de
     * PRODUITS comme compte de COGS — l'écriture serait postée sans erreur mais le bilan et le
     * compte de résultat seraient faux.
     *
     * @param expectedReportingClass la {@link ReportingClass} attendue pour ce rôle, ou
     *        {@code null} pour ne pas vérifier la classe (rétro-compatibilité).
     */
    private void validateAccount(UUID companyId, UUID accountId, String fieldName,
                                 jo.accountant.core.framework.ReportingClass expectedReportingClass) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte introuvable pour " + fieldName));
        if (!account.getCompanyId().equals(companyId)) {
            throw new ValidationException("ACCOUNT_NOT_FOUND",
                "Compte introuvable pour " + fieldName);
        }
        if (!account.isActive()) {
            throw new ValidationException("ACCOUNT_INACTIVE",
                "Le compte " + account.getCode() + " est désactivé");
        }
        if (expectedReportingClass != null
                && account.getReportingClass() != expectedReportingClass) {
            throw new ValidationException("ACCOUNT_WRONG_REPORTING_CLASS",
                "Le compte " + account.getCode() + " (" + account.getReportingClass()
                + ") ne peut pas être utilisé comme " + fieldName
                + " : la classe attendue est " + expectedReportingClass + ".");
        }
    }

    /** Rétro-compatibilité — délègue à {@link #validateAccount(UUID, UUID, String, ReportingClass)}. */
    private void validateAccount(UUID companyId, UUID accountId, String fieldName) {
        validateAccount(companyId, accountId, fieldName, null);
    }

    private static StockMoveResponse toResponse(StockMove move) {
        return new StockMoveResponse(
            move.getId(), move.getItemId(), move.getWarehouseId(), move.getMoveDate(),
            move.getDirection(), move.getQuantity(), move.getUnitCost(), move.getTotalCost(),
            move.getSourceDocument(), move.getJournalEntryId(), move.getCreatedAt());
    }

    // --- Reporting agrégé (Part E1, E2) ---

    /**
     * Valorisation agrégée de tout le stock d'une entreprise (Part E1).
     *
     * <p>Une ligne par couple (article, entrepôt) ayant au moins une couche non épuisée.
     * La quantité et la valeur sont agrégées sur toutes les couches FIFO / coût moyen
     * pondéré non épuisées de ce couple. Le coût unitaire retourné est le coût moyen
     * pondéré (= totalValue / quantity, arrondi à la devise fonctionnelle).
     *
     * <p>Utilisé par :
     * <ul>
     *   <li>{@code GET /api/v1/companies/{companyId}/inventory/valuation} (JSON, Part E1) ;</li>
     *   <li>le CSV {@code inventory_valuation} exposé par :reporting (Part E4).</li>
     * </ul>
     *
     * <p>Réutilise la logique de {@link #getValuation(UUID, UUID)} (mêmes arrondis
     * currency-aware via {@code CurrencyRoundingService}) mais l'agrège par entrepôt
     * plutôt que sur tous entrepôts confondus.
     */
    @Transactional(readOnly = true)
    public List<InventoryValuationResponse> getAggregatedValuation(UUID companyId) {
        // Résoudre les libellés d'entrepôts une seule fois (évite du N+1 si beaucoup de lignes).
        Map<UUID, String> warehouseLabelById = new HashMap<>();
        for (Warehouse wh : warehouseRepository.findByCompanyIdOrderByLabel(companyId)) {
            warehouseLabelById.put(wh.getId(), wh.getLabel());
        }

        String currencyCode = resolveCurrency(companyId);
        List<InventoryValuationResponse> rows = new ArrayList<>();

        for (Item item : itemRepository.findByCompanyIdOrderBySku(companyId)) {
            // Couches FIFO/moyen pondéré non épuisées de l'article (tous entrepôts confondus).
            List<StockValuationLayer> layers = layerRepository
                .findByItemIdOrderByReceiptDate(item.getId());

            // Agréger par entrepôt : (quantity, totalValue) cumulés.
            Map<UUID, BigDecimal> qtyByWarehouse = new HashMap<>();
            Map<UUID, BigDecimal> valueByWarehouse = new HashMap<>();
            for (StockValuationLayer layer : layers) {
                if (layer.getQuantityRemaining().compareTo(BigDecimal.ZERO) <= 0) continue;
                qtyByWarehouse.merge(layer.getWarehouseId(), layer.getQuantityRemaining(), BigDecimal::add);
                valueByWarehouse.merge(layer.getWarehouseId(),
                    layer.getQuantityRemaining().multiply(layer.getUnitCost()), BigDecimal::add);
            }

            for (Map.Entry<UUID, BigDecimal> e : qtyByWarehouse.entrySet()) {
                UUID warehouseId = e.getKey();
                BigDecimal quantity = e.getValue();
                BigDecimal totalValue = valueByWarehouse.getOrDefault(warehouseId, BigDecimal.ZERO);
                BigDecimal unitCost = quantity.compareTo(BigDecimal.ZERO) > 0
                    ? roundingService.round(currencyCode,
                        totalValue.divide(quantity, CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP))
                    : BigDecimal.ZERO;
                rows.add(new InventoryValuationResponse(
                    item.getId(), item.getSku(), item.getLabel(),
                    warehouseId, warehouseLabelById.get(warehouseId),
                    quantity, unitCost, roundingService.round(currencyCode, totalValue)));
            }
        }
        return rows;
    }

    /**
     * Liste tous les mouvements de stock d'une entreprise sur une période (Part E2).
     *
     * <p>Si {@code from} est null, borne inférieure = 1900-01-01. Si {@code to} est null,
     * borne supérieure = aujourd'hui. Tri par {@code moveDate} décroissant.
     *
     * <p>Utilisé par :
     * <ul>
     *   <li>{@code GET /api/v1/companies/{companyId}/inventory/stock-moves?from=&to=} (JSON, Part E2) ;</li>
     *   <li>le CSV {@code stock_movement_register} exposé par :reporting (Part E4).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<StockMoveResponse> listStockMoves(UUID companyId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.of(1900, 1, 1);
        LocalDate end = to != null ? to : LocalDate.now();
        return stockMoveRepository
            .findByCompanyIdAndMoveDateBetweenOrderByMoveDateDesc(companyId, start, end)
            .stream()
            .map(InventoryService::toResponse)
            .toList();
    }
}
