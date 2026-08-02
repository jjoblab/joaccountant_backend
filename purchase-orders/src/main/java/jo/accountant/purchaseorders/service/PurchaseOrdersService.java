package jo.accountant.purchaseorders.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.purchaseorders.dto.CreatePurchaseOrderRequest;
import jo.accountant.purchaseorders.dto.CreatePurchaseOrderRequest.LineDto;
import jo.accountant.purchaseorders.dto.PurchaseOrderResponse;
import jo.accountant.purchaseorders.entity.PurchaseOrder;
import jo.accountant.purchaseorders.entity.PurchaseOrderLine;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;
import jo.accountant.purchaseorders.repository.PurchaseOrderLineRepository;
import jo.accountant.purchaseorders.repository.PurchaseOrderRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion des commandes fournisseurs.
 *
 * <p>Création / lecture / changement de statut des PurchaseOrders. Le module ne génère pas
 * d'écriture comptable au MVP — l'écriture est générée à la réception de la facture dans
 * :purchasing.
 *
 * <p>Le 3-way match (commande ↔ facture) est implémenté dans {@link ThreeWayMatchService}.
 
 *
 * @author jo@Dev


*/
@Service
public class PurchaseOrdersService {

 private static final Logger LOG = LoggerFactory.getLogger(PurchaseOrdersService.class);

 private final PurchaseOrderRepository poRepository;
 private final PurchaseOrderLineRepository poLineRepository;
 private final ThirdPartyRepository thirdPartyRepository;

 public PurchaseOrdersService(PurchaseOrderRepository poRepository,
 PurchaseOrderLineRepository poLineRepository,
 ThirdPartyRepository thirdPartyRepository) {
 this.poRepository = poRepository;
 this.poLineRepository = poLineRepository;
 this.thirdPartyRepository = thirdPartyRepository;
 }

 @Transactional
 public PurchaseOrderResponse create(UUID companyId, CreatePurchaseOrderRequest req) {
 validateCreateRequest(companyId, req);

 // Unicité du numéro de commande par entreprise
 if (poRepository.findByCompanyIdAndOrderNumber(companyId, req.orderNumber()).isPresent()) {
 throw new ConflictException("PO_NUMBER_ALREADY_EXISTS",
 "Le numéro de commande '" + req.orderNumber() + "' existe déjà pour cette entreprise.");
 }

 PurchaseOrder po = new PurchaseOrder();
 po.setCompanyId(companyId);
 po.setSupplierId(req.supplierId());
 po.setOrderNumber(req.orderNumber().trim());
 po.setOrderDate(req.orderDate());
 po.setCurrency(req.currency().toUpperCase());
 po.setStatus(req.status());
 po.setTotalAmount(BigDecimal.ZERO);
 PurchaseOrder saved = poRepository.save(po);

 // Persister les lignes + calculer le total
 BigDecimal total = BigDecimal.ZERO;
 for (LineDto line : req.lines()) {
 PurchaseOrderLine poLine = new PurchaseOrderLine();
 poLine.setCompanyId(companyId);
 poLine.setPoId(saved.getId());
 poLine.setItemId(line.itemId());
 poLine.setDescription(line.description().trim());
 poLine.setQuantity(line.quantity());
 poLine.setUnitPrice(line.unitPrice());
 poLine.setReceivedQuantity(BigDecimal.ZERO);
 poLineRepository.save(poLine);
 total = total.add(line.quantity().multiply(line.unitPrice()));
 }
 saved.setTotalAmount(total);
 poRepository.save(saved);

 LOG.info("Commande créée : id={} number={} supplier={} lignes={} total={}",
 saved.getId(), saved.getOrderNumber(), saved.getSupplierId(), req.lines().size(), total);
 return loadResponse(companyId, saved.getId());
 }

 private void validateCreateRequest(UUID companyId, CreatePurchaseOrderRequest req) {
 ThirdParty supplier = thirdPartyRepository.findById(req.supplierId())
 .orElseThrow(() -> new NotFoundException("ThirdParty", req.supplierId()));
 if (!supplier.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", req.supplierId());
 }
 if (supplier.getType() != ThirdPartyType.SUPPLIER) {
 throw new ValidationException("THIRD_PARTY_NOT_SUPPLIER",
 "Le tiers " + supplier.getName() + " n'est pas un fournisseur (type="
 + supplier.getType() + ").");
 }
 if (req.lines() == null || req.lines().isEmpty()) {
 throw new ValidationException("PO_LINES_REQUIRED",
 "Une commande doit comporter au moins une ligne.");
 }
 for (LineDto line : req.lines()) {
 if (line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
 throw new ValidationException("PO_LINE_QUANTITY_INVALID",
 "La quantité doit être > 0 pour la ligne : " + line.description());
 }
 if (line.unitPrice() == null || line.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
 throw new ValidationException("PO_LINE_PRICE_INVALID",
 "Le prix unitaire doit être ≥ 0 pour la ligne : " + line.description());
 }
 }
 }

 @Transactional(readOnly = true)
 public PurchaseOrderResponse get(UUID companyId, UUID poId) {
 return loadResponse(companyId, poId);
 }

 @Transactional(readOnly = true)
 public List<PurchaseOrderResponse> list(UUID companyId) {
 return poRepository.findByCompanyIdOrderByOrderDateDesc(companyId).stream()
 .map(po -> loadResponse(companyId, po.getId()))
 .toList();
 }

 @Transactional
 public PurchaseOrderResponse changeStatus(UUID companyId, UUID poId, PurchaseOrderStatus newStatus) {
 PurchaseOrder po = loadPo(companyId, poId);
 po.setStatus(newStatus);
 poRepository.save(po);
 LOG.info("Statut commande mis à jour : id={} newStatus={}", po.getId(), newStatus);
 return loadResponse(companyId, po.getId());
 }

 // --- Helpers ---

 private PurchaseOrderResponse loadResponse(UUID companyId, UUID poId) {
 PurchaseOrder po = loadPo(companyId, poId);
 String supplierName = thirdPartyRepository.findById(po.getSupplierId())
 .filter(t -> t.getCompanyId().equals(companyId))
 .map(ThirdParty::getName)
 .orElse("");
 List<PurchaseOrderResponse.LineResponse> lines = poLineRepository
 .findByPoIdOrderByCreatedAt(po.getId()).stream()
 .map(l -> new PurchaseOrderResponse.LineResponse(
 l.getId(), l.getItemId(), l.getDescription(),
 l.getQuantity(), l.getUnitPrice(), l.getReceivedQuantity(),
 l.getQuantity().multiply(l.getUnitPrice())))
 .toList();
 return new PurchaseOrderResponse(
 po.getId(), po.getCompanyId(), po.getSupplierId(), supplierName,
 po.getOrderNumber(), po.getOrderDate(), po.getStatus(),
 po.getCurrency(), po.getTotalAmount(), lines,
 po.getCreatedAt(), po.getUpdatedAt());
 }

 private PurchaseOrder loadPo(UUID companyId, UUID poId) {
 PurchaseOrder po = poRepository.findById(poId)
 .orElseThrow(() -> new NotFoundException("PurchaseOrder", poId));
 if (!po.getCompanyId().equals(companyId)) {
 throw new NotFoundException("PurchaseOrder", poId);
 }
 return po;
 }

 /** Accès package-private pour ThreeWayMatchService — charge une commande sans lazy-loading. */
 PurchaseOrder loadPoInternal(UUID companyId, UUID poId) {
 return loadPo(companyId, poId);
 }

 /** Accès package-private pour ThreeWayMatchService — liste les lignes d'une commande. */
 List<PurchaseOrderLine> listLinesInternal(UUID poId) {
 return new ArrayList<>(poLineRepository.findByPoIdOrderByCreatedAt(poId));
 }
}
