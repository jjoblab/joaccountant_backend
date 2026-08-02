package jo.accountant.purchaseorders.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.purchaseorders.dto.ThreeWayMatchResult;
import jo.accountant.purchaseorders.dto.ThreeWayMatchResult.Discrepancy;
import jo.accountant.purchaseorders.entity.PurchaseOrder;
import jo.accountant.purchaseorders.entity.PurchaseOrderLine;
import jo.accountant.purchaseorders.entity.PurchaseOrderStatus;
import jo.accountant.purchaseorders.repository.PurchaseOrderRepository;
import jo.accountant.purchasing.entity.PurchaseInvoice;
import jo.accountant.purchasing.entity.PurchaseInvoiceLine;
import jo.accountant.purchasing.repository.PurchaseInvoiceLineRepository;
import jo.accountant.purchasing.repository.PurchaseInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de 3-way match entre une commande fournisseur et une facture fournisseur.
 *
 * <p>Le 3-way match est une pratique standard de contrôle interne (audit v4.7 §6.2) :
 * <ol>
 * <li><b>Existence de commande</b> — au moins une commande (PurchaseOrder) existe pour le
 * fournisseur de la facture.</li>
 * <li><b>Quantités</b> — pour chaque ligne facturée, la quantité facturée ≤ quantité commandée.</li>
 * <li><b>Prix</b> — pour chaque ligne facturée, le prix unitaire facturé = prix unitaire commandé.</li>
 * </ol>
 *
 * <p>Si toutes les vérifications passent, le résultat est {@code matches=true}. Sinon, la liste
 * {@code discrepancies} détaille les écarts (NO_PURCHASE_ORDER, NO_MATCHING_PO_LINE,
 * QUANTITY_EXCEEDED, PRICE_MISMATCH).
 *
 * <p>Stratégie de matching :
 * <ul>
 * <li>Sélection de la commande : la plus récente du fournisseur, en privilégiant les statuts
 * SUBMITTED et RECEIVED (les CLOSED ont déjà été rapprochées, les DRAFT ne sont pas encore
 * validées).</li>
 * <li>Matching des lignes : par {@code itemId} si la ligne de facture en a un, sinon par
 * description exacte (case-insensitive).</li>
 * <li>Comparaison des quantités avec {@code compareTo} (BigDecimal exact, pas d'arrondi).</li>
 * <li>Comparaison des prix avec {@code compareTo} — le moindre écart lève PRICE_MISMATCH.</li>
 * </ul>
 */
@Service
public class ThreeWayMatchService {

 private static final Logger LOG = LoggerFactory.getLogger(ThreeWayMatchService.class);

 private final PurchaseInvoiceRepository invoiceRepository;
 private final PurchaseInvoiceLineRepository invoiceLineRepository;
 private final PurchaseOrderRepository poRepository;
 private final PurchaseOrdersService poService;

 public ThreeWayMatchService(PurchaseInvoiceRepository invoiceRepository,
 PurchaseInvoiceLineRepository invoiceLineRepository,
 PurchaseOrderRepository poRepository,
 PurchaseOrdersService poService) {
 this.invoiceRepository = invoiceRepository;
 this.invoiceLineRepository = invoiceLineRepository;
 this.poRepository = poRepository;
 this.poService = poService;
 }

 /**
 * Vérifie le 3-way match entre la facture fournie et la commande la plus récente du
 * même fournisseur.
 *
 * @param companyId tenant
 * @param invoiceId facture fournisseur à tester
 * @return résultat du 3-way match (matches + discrepancies détaillées)
 */
 @Transactional(readOnly = true)
 public ThreeWayMatchResult match(UUID companyId, UUID invoiceId) {
 // Charger la facture (avec defense-in-depth sur le tenant)
 PurchaseInvoice invoice = invoiceRepository.findById(invoiceId)
 .orElseThrow(() -> new NotFoundException("PurchaseInvoice", invoiceId));
 if (!invoice.getCompanyId().equals(companyId)) {
 throw new NotFoundException("PurchaseInvoice", invoiceId);
 }

 List<PurchaseInvoiceLine> invoiceLines = invoiceLineRepository
 .findByInvoiceIdOrderByCreatedAt(invoice.getId());

 List<Discrepancy> discrepancies = new ArrayList<>();

 // (a) Vérifier qu'une commande existe pour ce fournisseur
 UUID supplierId = invoice.getThirdPartyId();
 List<PurchaseOrder> supplierPOs = poRepository
 .findByCompanyIdAndSupplierIdOrderByOrderDateDesc(companyId, supplierId);
 if (supplierPOs.isEmpty()) {
 discrepancies.add(new Discrepancy(
 "NO_PURCHASE_ORDER",
 "Aucune commande n'existe pour le fournisseur " + supplierId,
 null, null, null, null));
 // Pas de sens à comparer les lignes sans PO — on retourne directement
 LOG.info("3-way match FAILED (no PO) : invoice={} supplier={}", invoiceId, supplierId);
 return new ThreeWayMatchResult(invoice.getId(), null, false, discrepancies);
 }

 // Sélectionner la PO la plus pertinente : SUBMITTED/RECEIVED d'abord, puis la plus récente.
 PurchaseOrder selectedPo = supplierPOs.stream()
 .max(Comparator
 .comparing((PurchaseOrder po) -> poRank(po.getStatus()))
 .thenComparing(PurchaseOrder::getOrderDate))
 .orElse(supplierPOs.get(0));

 List<PurchaseOrderLine> poLines = poService.listLinesInternal(selectedPo.getId());

 // (b) + (c) Vérifier chaque ligne facturée
 for (PurchaseInvoiceLine invLine : invoiceLines) {
 PurchaseOrderLine matchedPoLine = findMatchingPoLine(invLine, poLines);
 if (matchedPoLine == null) {
 discrepancies.add(new Discrepancy(
 "NO_MATCHING_PO_LINE",
 "Aucune ligne de commande ne correspond à la ligne facturée : "
 + invLine.getDescription(),
 invLine.getId(), null, null, null));
 continue;
 }

 // (b) Quantité facturée ≤ quantité commandée
 if (invLine.getQuantity().compareTo(matchedPoLine.getQuantity()) > 0) {
 discrepancies.add(new Discrepancy(
 "QUANTITY_EXCEEDED",
 "Quantité facturée (" + invLine.getQuantity()
 + ") > quantité commandée (" + matchedPoLine.getQuantity()
 + ") pour la ligne : " + invLine.getDescription(),
 invLine.getId(), matchedPoLine.getId(),
 matchedPoLine.getQuantity(), invLine.getQuantity()));
 }

 // (c) Prix facturé = prix commandé (compareTo → 0)
 if (invLine.getUnitPrice().compareTo(matchedPoLine.getUnitPrice()) != 0) {
 discrepancies.add(new Discrepancy(
 "PRICE_MISMATCH",
 "Prix unitaire facturé (" + invLine.getUnitPrice()
 + ") ≠ prix commandé (" + matchedPoLine.getUnitPrice()
 + ") pour la ligne : " + invLine.getDescription(),
 invLine.getId(), matchedPoLine.getId(),
 matchedPoLine.getUnitPrice(), invLine.getUnitPrice()));
 }
 }

 boolean matches = discrepancies.isEmpty();
 if (matches) {
 LOG.info("3-way match OK : invoice={} po={} lignes={}",
 invoiceId, selectedPo.getId(), invoiceLines.size());
 } else {
 LOG.info("3-way match FAILED : invoice={} po={} écarts={}",
 invoiceId, selectedPo.getId(), discrepancies.size());
 }

 return new ThreeWayMatchResult(invoice.getId(), selectedPo.getId(), matches, discrepancies);
 }

 /**
 * Trouve la ligne de commande correspondant à une ligne de facture.
 *
 * <p>Stratégie :
 * <ul>
 * <li>Si la ligne de facture a un {@code itemId} (les PurchaseInvoiceLines n'en ont pas
 * au MVP, mais le champ pourrait être ajouté plus tard), on cherche par itemId.</li>
 * <li>Sinon, on cherche par description exacte (case-insensitive).</li>
 * </ul>
 *
 * <p>Retourne la première ligne de commande qui matche, ou {@code null} si aucune ne matche.
 */
 private PurchaseOrderLine findMatchingPoLine(PurchaseInvoiceLine invLine,
 List<PurchaseOrderLine> poLines) {
 // PurchaseInvoiceLine n'a pas d'itemId au MVP — fallback sur la description.
 String invDesc = invLine.getDescription() == null ? "" : invLine.getDescription().trim().toLowerCase();
 for (PurchaseOrderLine poLine : poLines) {
 String poDesc = poLine.getDescription() == null ? "" : poLine.getDescription().trim().toLowerCase();
 if (poDesc.equals(invDesc)) {
 return poLine;
 }
 }
 return null;
 }

 /**
 * Rang d'un statut pour le tri (plus haut = plus pertinent pour le 3-way match).
 * RECEIVED > SUBMITTED > CLOSED > DRAFT — une commande reçue est le meilleur candidat
 * pour rapprocher une facture (marchandise livrée).
 */
 private int poRank(PurchaseOrderStatus status) {
 return switch (status) {
 case RECEIVED -> 4;
 case SUBMITTED -> 3;
 case CLOSED -> 2;
 case DRAFT -> 1;
 };
 }
}
