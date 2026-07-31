package jo.accountant.app.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * v2.5.0 — Task 16 : endpoint de recherche globale (Ctrl+K style).
 *
 * <p>{@code GET /api/v1/companies/{companyId}/search?q=<query>&limit=20}
 * interroge 5 modules en parallèle (MVP : séquentiel — la latence reste acceptable
 * car chaque requête est bornée à 5 résultats) et fusionne les résultats en une
 * seule réponse {@link GlobalSearchResponse}.
 *
 * <p>Modules recherchés :
 * <ol>
 *   <li><strong>third-parties</strong> — recherche par nom (case-insensitive,
 *       partial match) ; 5 résultats max.</li>
 *   <li><strong>invoicing</strong> — recherche par numéro de facture
 *       ({@code invoiceNumber}) ; 5 résultats max.</li>
 *   <li><strong>accounting-engine</strong> — recherche par référence OU
 *       description d'écriture comptable ; 5 résultats max.</li>
 *   <li><strong>chart-of-accounts</strong> — recherche par code OU libellé
 *       de compte ; 5 résultats max.</li>
 *   <li><strong>employees</strong> — recherche par nom (via le tiers rattaché),
 *       matricule, poste ou département ; 5 résultats max.</li>
 * </ol>
 *
 * <p>Le paramètre {@code limit} plafonne le nombre total de résultats (par défaut 20,
 * max 25 = 5 modules × 5 résultats). Le client mobile peut l'ignorer — il reçoit
 * toujours au plus 25 résultats.
 *
 * <p>Pas d'authentification spécifique au-delà du filtre JWT standard — la recherche
 * est accessible à tout utilisateur connecté à l'entreprise (l'isolation multi-tenant
 * est garantie par le {@code companyId} dans l'URL + les guards applicatifs).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/search")
@Tag(name = "Global Search",
        description = "v2.5.0 — Task 16 : recherche globale Ctrl+K (tiers, factures, écritures, comptes, employés)")
public class SearchController {

    private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);

    /** Nombre max de résultats par module (chaque module contribue jusqu'à 5). */
    private static final int PER_MODULE_LIMIT = 5;

    /** Plafond dur pour le paramètre {@code limit} de l'URL. */
    private static final int HARD_LIMIT_CAP = 25;

    private final ThirdPartyRepository thirdPartyRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountRepository accountRepository;
    private final EmployeeRepository employeeRepository;

    public SearchController(ThirdPartyRepository thirdPartyRepository,
                             SalesInvoiceRepository salesInvoiceRepository,
                             JournalEntryRepository journalEntryRepository,
                             AccountRepository accountRepository,
                             EmployeeRepository employeeRepository) {
        this.thirdPartyRepository = thirdPartyRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.accountRepository = accountRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Recherche globale sur 5 modules.
     *
     * @param companyId identifiant de l'entreprise (path variable)
     * @param query     texte recherché (paramètre {@code q}, min 2 caractères)
     * @param limit     nombre max de résultats à retourner (défaut 20, max 25)
     * @return réponse fusionnée contenant les résultats des 5 modules
     */
    @Operation(summary = "Recherche globale (Ctrl+K)",
            description = "Recherche simultanément dans 5 modules : tiers, factures, écritures, " +
                          "comptes du plan comptable, et employés. Chaque module contribue " +
                          "jusqu'à 5 résultats (max 25 au total).")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public GlobalSearchResponse search(
            @PathVariable UUID companyId,
            @Parameter(description = "Texte recherché (min 2 caractères)")
            @RequestParam(name = "q", defaultValue = "") String query,
            @Parameter(description = "Nombre max de résultats (défaut 20, max 25)")
            @RequestParam(name = "limit", defaultValue = "20") int limit) {

        String q = query == null ? "" : query.trim();
        int cappedLimit = Math.max(1, Math.min(limit, HARD_LIMIT_CAP));

        if (q.length() < 2) {
            LOG.debug("[GlobalSearch] companyId={} — query too short ({}), returning empty",
                    companyId, q.length());
            return new GlobalSearchResponse(q, List.of());
        }

        LOG.info("[GlobalSearch] companyId={} q='{}' limit={}", companyId, q, cappedLimit);
        long start = System.currentTimeMillis();

        List<GlobalSearchResult> results = new ArrayList<>(PER_MODULE_LIMIT * 5);

        // 1. Third parties
        try {
            List<ThirdParty> tps = thirdPartyRepository
                    .findByCompanyIdAndNameContainingIgnoreCaseOrderByName(companyId, q);
            if (tps != null) {
                for (ThirdParty tp : tps) {
                    if (results.size() >= cappedLimit) break;
                    if (tp == null || tp.getId() == null) continue;
                    results.add(new GlobalSearchResult(
                            "THIRD_PARTY",
                            tp.getId().toString(),
                            safe(tp.getName()),
                            thirdPartySubtitle(tp.getType()),
                            "Ouvrir la fiche tiers"));
                }
            }
        } catch (Exception e) {
            LOG.warn("[GlobalSearch] ThirdParty search failed — skipping: {}", e.toString());
        }

        // 2. Invoices
        try {
            var page = salesInvoiceRepository
                    .findByCompanyIdAndInvoiceNumberContainingIgnoreCaseOrderByIssueDateDesc(
                            companyId, q, PageRequest.of(0, PER_MODULE_LIMIT));
            for (SalesInvoice inv : page.getContent()) {
                if (results.size() >= cappedLimit) break;
                if (inv == null || inv.getId() == null) continue;
                results.add(new GlobalSearchResult(
                        "INVOICE",
                        inv.getId().toString(),
                        safe(inv.getInvoiceNumber()),
                        invoiceSubtitle(inv),
                        "Voir la facture"));
            }
        } catch (Exception e) {
            LOG.warn("[GlobalSearch] Invoice search failed — skipping: {}", e.toString());
        }

        // 3. Journal entries
        try {
            var page = journalEntryRepository
                    .searchByReferenceOrDescription(companyId, q, PageRequest.of(0, PER_MODULE_LIMIT));
            for (JournalEntry entry : page.getContent()) {
                if (results.size() >= cappedLimit) break;
                if (entry == null || entry.getId() == null) continue;
                String label = safe(entry.getReference());
                if (label.isEmpty()) label = safe(entry.getDescription());
                results.add(new GlobalSearchResult(
                        "ENTRY",
                        entry.getId().toString(),
                        label,
                        entrySubtitle(entry),
                        "Ouvrir l'écriture"));
            }
        } catch (Exception e) {
            LOG.warn("[GlobalSearch] JournalEntry search failed — skipping: {}", e.toString());
        }

        // 4. Chart of accounts
        try {
            List<Account> accounts = accountRepository.search(companyId, q);
            if (accounts != null) {
                for (Account acc : accounts) {
                    if (results.size() >= cappedLimit) break;
                    if (acc == null || acc.getId() == null) continue;
                    results.add(new GlobalSearchResult(
                            "ACCOUNT",
                            acc.getId().toString(),
                            safe(acc.getCode()) + " — " + safe(acc.getLabel()),
                            "Compte comptable",
                            "Ouvrir le plan comptable"));
                }
            }
        } catch (Exception e) {
            LOG.warn("[GlobalSearch] Account search failed — skipping: {}", e.toString());
        }

        // 5. Employees
        try {
            var page = employeeRepository
                    .searchByNameOrNumberOrPosition(companyId, q, PageRequest.of(0, PER_MODULE_LIMIT));
            for (Employee emp : page.getContent()) {
                if (results.size() >= cappedLimit) break;
                if (emp == null || emp.getId() == null || emp.getThirdPartyId() == null) continue;
                // Résolution best-effort du nom de l'employé via le tiers rattaché.
                String name = thirdPartyRepository.findById(emp.getThirdPartyId())
                        .map(ThirdParty::getName).orElse(null);
                if (name == null || name.isBlank()) {
                    name = safe(emp.getEmployeeNumber());
                }
                results.add(new GlobalSearchResult(
                        "EMPLOYEE",
                        emp.getId().toString(),
                        name,
                        employeeSubtitle(emp),
                        "Ouvrir la fiche employé"));
            }
        } catch (Exception e) {
            LOG.warn("[GlobalSearch] Employee search failed — skipping: {}", e.toString());
        }

        long elapsed = System.currentTimeMillis() - start;
        LOG.info("[GlobalSearch] companyId={} q='{}' → {} results in {}ms",
                companyId, q, results.size(), elapsed);
        return new GlobalSearchResponse(q, results);
    }

    // ────────────────────────────────────────────────────────────────────
    //  Helpers — sous-titres lisibles par module
    // ────────────────────────────────────────────────────────────────────

    private static String thirdPartySubtitle(ThirdPartyType type) {
        if (type == null) return "Tiers";
        return switch (type) {
            case CLIENT -> "Client";
            case SUPPLIER -> "Fournisseur";
            case DONOR -> "Donateur";
            case EMPLOYEE -> "Employé (tiers)";
            case OTHER -> "Tiers";
            default -> "Tiers";
        };
    }

    private static String invoiceSubtitle(SalesInvoice inv) {
        StringBuilder sb = new StringBuilder();
        if (inv.getIssueDate() != null) {
            sb.append(inv.getIssueDate());
        }
        if (inv.getTotalAmount() != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(inv.getTotalAmount().toPlainString());
            if (inv.getCurrency() != null) {
                sb.append(' ').append(inv.getCurrency());
            }
        }
        if (sb.length() == 0 && inv.getStatus() != null) {
            sb.append(inv.getStatus());
        }
        return sb.length() == 0 ? "Facture" : sb.toString();
    }

    private static String entrySubtitle(JournalEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.getEntryDate() != null) {
            sb.append("Écriture du ").append(entry.getEntryDate());
        }
        if (entry.getStatus() != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(entry.getStatus());
        }
        if (sb.length() == 0) return "Écriture comptable";
        return sb.toString();
    }

    private static String employeeSubtitle(Employee emp) {
        StringBuilder sb = new StringBuilder();
        if (emp.getEmployeeNumber() != null && !emp.getEmployeeNumber().isBlank()) {
            sb.append(emp.getEmployeeNumber());
        }
        if (emp.getPosition() != null && !emp.getPosition().isBlank()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(emp.getPosition());
        }
        if (sb.length() == 0) return "Employé";
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
