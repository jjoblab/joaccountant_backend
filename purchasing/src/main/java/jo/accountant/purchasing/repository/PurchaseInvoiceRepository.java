package jo.accountant.purchasing.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.purchasing.entity.PurchaseInvoice;
import jo.accountant.purchasing.entity.PurchaseInvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des factures d'achat.
 *
 * <p><b>pagination Pageable</b> : variantes paginées ({@code Page<>}) disponibles
 * pour les endpoints volumineux. Les variantes {@code List<>} sont conservées pour
 * rétro-compatibilité (appels internes sans pagination).
 */
public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, UUID> {

 List<PurchaseInvoice> findByCompanyIdOrderByIssueDateDesc(UUID companyId);

 List<PurchaseInvoice> findByCompanyIdAndStatus(UUID companyId, PurchaseInvoiceStatus status);

 /**
 * Factures d'achat d'une entreprise dont la {@code issueDate} est comprise entre
 * {@code start} et {@code end} (inclus), triées par {@code issueDate} décroissant.
 *
 * <p>Utilisé par {@code GET /purchase-invoices?fiscalYearId=} (restructuration 2026-07-25
 * suite 4) pour filtrer les factures par exercice fiscal.
 */
 List<PurchaseInvoice> findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
 UUID companyId, LocalDate start, LocalDate end);

 // ── variantes paginées (rétro-compat : les méthodes List<> ci-dessus sont conservées) ──

 /** Variante paginée — toutes les factures d'achat, triées par issueDate décroissant. */
 Page<PurchaseInvoice> findByCompanyIdOrderByIssueDateDesc(UUID companyId, Pageable pageable);

 /** Variante paginée — factures d'achat d'un exercice fiscal (between start/end), triées par issueDate desc. */
 Page<PurchaseInvoice> findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(
 UUID companyId, LocalDate start, LocalDate end, Pageable pageable);
}
