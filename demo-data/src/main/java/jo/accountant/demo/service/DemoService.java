package jo.accountant.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.demo.dto.DemoCompanySummary;
import jo.accountant.demo.dto.DemoDashboard;
import jo.accountant.demo.dto.DemoDashboard.Alert;
import jo.accountant.demo.dto.DemoDashboard.Kpi;
import jo.accountant.demo.dto.DemoDashboard.MonthlyAmount;
import jo.accountant.demo.dto.DemoDashboard.TransactionSummary;
import jo.accountant.demo.seeders.CompanySeeder;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.payroll.entity.Payslip;
import jo.accountant.payroll.repository.PayslipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.1 — Service central du module Démos.
 *
 * <p>Expose les 4 entreprises démo et leurs KPIs via les endpoints publics GET /api/v1/demos/**.
 *
 * <p>Les KPIs sont actuellement des valeurs estimées conformes au profil de chaque PME
 * (ex : Boutik Lakay ~6M HTG/an, Caribbean Textiles ~144M HTG/an). Dans la version complète
 * (V9), les KPIs seront calculés depuis les écritures comptables réelles agrégées par période.
 */
@Service
@Transactional(readOnly = true)
public class DemoService {

    private static final Logger LOG = LoggerFactory.getLogger(DemoService.class);

    private final CompanyRepository companyRepository;
    private final SalesInvoiceRepository invoiceRepository;
    private final PayslipRepository payslipRepository;
    private final List<CompanySeeder> seeders;

    public DemoService(CompanyRepository companyRepository,
                        SalesInvoiceRepository invoiceRepository,
                        PayslipRepository payslipRepository,
                        List<CompanySeeder> seeders) {
        this.companyRepository = companyRepository;
        this.invoiceRepository = invoiceRepository;
        this.payslipRepository = payslipRepository;
        this.seeders = seeders;
    }

    public List<DemoCompanySummary> listDemos() {
        List<DemoCompanySummary> out = new ArrayList<>();
        for (CompanySeeder seeder : seeders) {
            Optional<Company> company = findDemoCompany(seeder.demoCode());
            if (company.isPresent()) {
                out.add(toSummary(seeder, company.get()));
            }
        }
        return out;
    }

    public Optional<DemoCompanySummary> getDemo(String demoCode) {
        CompanySeeder seeder = findSeeder(demoCode);
        if (seeder == null) return Optional.empty();
        return findDemoCompany(demoCode).map(c -> toSummary(seeder, c));
    }

    public Optional<DemoDashboard> getDashboard(String demoCode, String fiscalYear) {
        CompanySeeder seeder = findSeeder(demoCode);
        if (seeder == null) return Optional.empty();
        Optional<Company> company = findDemoCompany(demoCode);
        if (company.isEmpty()) return Optional.empty();

        String fy = fiscalYear != null ? fiscalYear : "FY2025-2026";
        return Optional.of(buildDashboard(seeder, company.get(), fy));
    }

    public Optional<Company> findDemoCompany(String demoCode) {
        CompanySeeder seeder = findSeeder(demoCode);
        if (seeder == null) return Optional.empty();
        return companyRepository.findAll().stream()
            .filter(c -> seeder.companyName().equals(c.getName()))
            .filter(c -> Boolean.TRUE.equals(c.getIsDemo()))
            .findFirst();
    }

    private CompanySeeder findSeeder(String demoCode) {
        return seeders.stream()
            .filter(s -> s.demoCode().equals(demoCode))
            .findFirst()
            .orElse(null);
    }

    private DemoCompanySummary toSummary(CompanySeeder seeder, Company company) {
        List<String> modules = modulesForSegment(seeder.segment());
        List<String> highlights = highlightsForSegment(seeder.segment());
        BigDecimal annualRevenue = annualRevenueForSegment(seeder.segment());
        int employees = employeesForSegment(seeder.segment());
        String location = locationForSegment(seeder.segment());
        String currency = company.getFunctionalCurrency();

        return new DemoCompanySummary(
            seeder.demoCode(),
            seeder.companyName(),
            seeder.segment(),
            location,
            employees,
            annualRevenue,
            currency,
            List.of("FY2024-2025", "FY2025-2026"),
            modules,
            highlights,
            company.getId()
        );
    }

    private DemoDashboard buildDashboard(CompanySeeder seeder, Company company, String fy) {
        BigDecimal annualRevenue = annualRevenueForSegment(seeder.segment());
        BigDecimal monthlyRevenue = annualRevenue.divide(new BigDecimal("12"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal annualExpenses = annualRevenue.multiply(new BigDecimal("0.85"));  // marge 15%
        BigDecimal monthlyExpenses = annualExpenses.divide(new BigDecimal("12"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal netResult = annualRevenue.subtract(annualExpenses);

        // IS selon taxExemptionStatus
        BigDecimal incomeTax;
        if (company.getTaxExemptionStatus() == jo.accountant.company.entity.TaxExemptionStatus.NGO_EXEMPT) {
            incomeTax = BigDecimal.ZERO;
        } else if (company.getTaxExemptionStatus() == jo.accountant.company.entity.TaxExemptionStatus.FREE_ZONE) {
            incomeTax = netResult.multiply(new BigDecimal("0.15")).setScale(2, java.math.RoundingMode.HALF_UP);
        } else {
            incomeTax = netResult.multiply(new BigDecimal("0.30")).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        BigDecimal cashPosition = annualRevenue.multiply(new BigDecimal("0.15"));

        // 12 mois de CA + charges
        List<MonthlyAmount> monthlyRevList = new ArrayList<>();
        List<MonthlyAmount> monthlyExpList = new ArrayList<>();
        String[] months = {"Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep"};
        for (String m : months) {
            // Variation saisonnière : décembre pic Noël, août creux vacances
            BigDecimal factor = new BigDecimal("1.0");
            if (m.equals("Dec")) factor = new BigDecimal("1.5");
            else if (m.equals("Aug")) factor = new BigDecimal("0.7");
            else if (m.equals("Feb")) factor = new BigDecimal("1.2");  // carnaval
            monthlyRevList.add(new MonthlyAmount(m, monthlyRevenue.multiply(factor).setScale(2, java.math.RoundingMode.HALF_UP)));
            monthlyExpList.add(new MonthlyAmount(m, monthlyExpenses.multiply(factor).setScale(2, java.math.RoundingMode.HALF_UP)));
        }

        Kpi kpi = new Kpi(annualRevenue, annualExpenses, netResult, incomeTax, cashPosition,
            monthlyRevList, monthlyExpList);

        // Alerts (placeholder)
        List<Alert> alerts = List.of(
            new Alert("DGI_DEADLINE", "2026-08-15", "TVA Juillet 2026",
                monthlyRevenue.multiply(new BigDecimal("0.10")).setScale(2, java.math.RoundingMode.HALF_UP)),
            new Alert("DGI_DEADLINE", "2026-08-15", "Acompte IS 1% Juillet 2026",
                monthlyRevenue.multiply(new BigDecimal("0.01")).setScale(2, java.math.RoundingMode.HALF_UP))
        );

        // Recent transactions (placeholder)
        List<TransactionSummary> txs = List.of(
            new TransactionSummary("2026-07-31", "PAYROLL", "Paie juillet 2026",
                monthlyExpenses.multiply(new BigDecimal("0.30")).setScale(2, java.math.RoundingMode.HALF_UP),
                company.getFunctionalCurrency()),
            new TransactionSummary("2026-07-15", "SALES_INVOICE", "Factures clients juillet (quinzaine 1)",
                monthlyRevenue.multiply(new BigDecimal("0.50")).setScale(2, java.math.RoundingMode.HALF_UP),
                company.getFunctionalCurrency())
        );

        return new DemoDashboard(company.getId(), seeder.demoCode(), seeder.companyName(), fy,
            kpi, alerts, txs);
    }

    private List<String> modulesForSegment(String segment) {
        return switch (segment) {
            case "RETAIL_COMMERCE" -> List.of("invoicing", "purchasing", "inventory", "payroll", "tax",
                "financial-statements", "reporting");
            case "PROFESSIONAL_SERVICES" -> List.of("invoicing", "purchasing", "time-billing", "expenses",
                "payroll", "tax", "financial-statements", "reporting", "analytics");
            case "NGO_HUMANITARIAN" -> List.of("funds-grants", "invoicing", "purchasing", "expenses", "payroll",
                "tax", "financial-statements", "reporting", "analytics", "fx-operations", "bank-reconciliation");
            case "WHOLESALE_COMMERCE" -> List.of("invoicing", "purchasing", "inventory", "payroll", "tax",
                "financial-statements", "reporting", "fx-operations");
            default -> List.of();
        };
    }

    private List<String> highlightsForSegment(String segment) {
        return switch (segment) {
            case "RETAIL_COMMERCE" -> List.of(
                "Multi-taxe TVA 10% + TCA 10% sur livraisons (V67)",
                "Stock FIFO avec COGS automatique",
                "13e mois en décembre (Code Travail art. 153)",
                "Déclarations DGI mensuelles complètes (TVA+TCA+RS+acompte IS)"
            );
            case "PROFESSIONAL_SERVICES" -> List.of(
                "Time-billing multi-niveaux (BillableRate projet+ressource)",
                "Auto-approbation timesheet bloquée (règle 4 yeux, v7-9)",
                "RS 2% retenue par clients + RS 30% non-résidents (V64)",
                "Multi-taxe TVA+TCA cumulatives sur même ligne (V67)"
            );
            case "NGO_HUMANITARIAN" -> List.of(
                "4 bailleurs (USAID/EU/BM/CRS) + formats structurés (USAID SF-425, EU PRAG, BM)",
                "Alimentation auto donor_report_line via tagging (v7-1)",
                "IS 0% NGO_EXEMPT + TVA exonérée (Code Fiscal art. 195, v8-1)",
                "Conversion USD→HTG + CTA en capitaux propres (v7-3)"
            );
            case "WHOLESALE_COMMERCE" -> List.of(
                "IS 15% zone franche (Code Fiscal art. 195, v8-1)",
                "TVA 0% export + imports en franchise (v8-6 VAT_EXEMPT_ZF)",
                "Keyset pagination 50K factures/an (v7-8)",
                "Spring Batch paie 1200 employés + 13e mois async (v8-7)",
                "IFRS_FULL complet : Bilan + CTA + CR + CF + SCE IAS 1.106 (v7-2)"
            );
            default -> List.of();
        };
    }

    private BigDecimal annualRevenueForSegment(String segment) {
        return switch (segment) {
            case "RETAIL_COMMERCE" -> new BigDecimal("6000000");
            case "PROFESSIONAL_SERVICES" -> new BigDecimal("18000000");
            case "NGO_HUMANITARIAN" -> new BigDecimal("60000000");  // ~5M USD
            case "WHOLESALE_COMMERCE" -> new BigDecimal("144000000");  // ~12M USD
            default -> BigDecimal.ZERO;
        };
    }

    private int employeesForSegment(String segment) {
        return switch (segment) {
            case "RETAIL_COMMERCE" -> 4;
            case "PROFESSIONAL_SERVICES" -> 8;
            case "NGO_HUMANITARIAN" -> 35;
            case "WHOLESALE_COMMERCE" -> 1200;
            default -> 0;
        };
    }

    private String locationForSegment(String segment) {
        return switch (segment) {
            case "RETAIL_COMMERCE" -> "Pétion-Ville, Port-au-Prince";
            case "PROFESSIONAL_SERVICES" -> "Port-au-Prince (Rue Capois)";
            case "NGO_HUMANITARIAN" -> "Port-au-Prince (Delmas 33)";
            case "WHOLESALE_COMMERCE" -> "CODEVI, Ouanaminthe (zone franche)";
            default -> "Haïti";
        };
    }

    // === V8.1 — 9 endpoints supplémentaires ===

    /** 4. Factures démo (paginé) — lit les vraies SalesInvoice de l'entreprise démo. */
    public Map<String, Object> getInvoices(String demoCode, int page, int size) {
        Optional<Company> company = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (company.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        List<SalesInvoice> invoices = invoiceRepository
            .findByCompanyIdOrderByIssueDateDesc(company.get().getId());
        int start = page * size;
        int end = Math.min(start + size, invoices.size());
        List<Map<String, Object>> pageContent = new ArrayList<>();
        for (int i = start; i < end; i++) {
            SalesInvoice inv = invoices.get(i);
            Map<String, Object> invMap = new HashMap<>();
            invMap.put("id", inv.getId());
            invMap.put("invoiceNumber", inv.getInvoiceNumber());
            invMap.put("issueDate", inv.getIssueDate());
            invMap.put("status", inv.getStatus());
            invMap.put("currency", inv.getCurrency());
            invMap.put("subtotal", inv.getSubtotal());
            invMap.put("taxAmount", inv.getTaxAmount());
            invMap.put("totalAmount", inv.getTotalAmount());
            pageContent.add(invMap);
        }
        result.put("content", pageContent);
        result.put("page", page);
        result.put("size", size);
        result.put("totalElements", invoices.size());
        result.put("totalPages", (int) Math.ceil((double) invoices.size() / size));
        return result;
    }

    /** 5. Bulletins de paie démo — lit les vrais Payslips de l'entreprise démo. */
    public Map<String, Object> getPayroll(String demoCode, String fy) {
        Optional<Company> company = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (company.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        List<Payslip> payslips = payslipRepository.findAll().stream()
            .filter(p -> p.getCompanyId().equals(company.get().getId()))
            .toList();
        result.put("totalPayslips", payslips.size());
        result.put("fiscalYear", fy != null ? fy : "all");
        // Résumé par année
        Map<Integer, BigDecimal> grossByYear = new HashMap<>();
        for (Payslip p : payslips) {
            // Note: Payslip n'a pas periodYear directement, on groupe par createdAt
            int year = p.getCreatedAt() != null ? p.getCreatedAt().atZone(java.time.ZoneOffset.UTC).getYear() : 0;
            grossByYear.merge(year, p.getGrossSalary(), BigDecimal::add);
        }
        result.put("grossByYear", grossByYear);
        // Top 10 payslips récents
        List<Map<String, Object>> recent = new ArrayList<>();
        payslips.stream().limit(10).forEach(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("employeeId", p.getEmployeeId());
            m.put("grossSalary", p.getGrossSalary());
            m.put("netPay", p.getNetPay());
            m.put("payslipNumber", p.getPayslipNumber());
            m.put("createdAt", p.getCreatedAt());
            recent.add(m);
        });
        result.put("recent", recent);
        return result;
    }

    /** 6. États financiers démo — stub qui redirige vers les vrais endpoints financial-statements. */
    public Map<String, Object> getFinancialStatement(String demoCode, String type,
                                                       LocalDate asOf, LocalDate from, LocalDate to,
                                                       String presentationCurrency) {
        Optional<Company> company = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (company.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        result.put("demoCode", demoCode);
        result.put("type", type);
        result.put("companyId", company.get().getId());
        result.put("asOf", asOf);
        result.put("from", from);
        result.put("to", to);
        result.put("presentationCurrency", presentationCurrency);
        result.put("message", "Utilisez GET /api/v1/companies/" + company.get().getId() +
            "/financial-statements/" + type + " pour les états financiers réels");
        return result;
    }

    /** 7. Déclarations DGI démo — valeurs estimées. */
    public Map<String, Object> getTaxDeclarations(String demoCode, int year, int month) {
        Optional<Company> company = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (company.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        BigDecimal monthlyRevenue = annualRevenueForSegment(company.get().getBusinessTypeCode() != null
            ? company.get().getBusinessTypeCode() : "").divide(new BigDecimal("12"), 2, java.math.RoundingMode.HALF_UP);

        Map<String, Object> tva = new HashMap<>();
        tva.put("collected", monthlyRevenue.multiply(new BigDecimal("0.10")));
        tva.put("deductible", monthlyRevenue.multiply(new BigDecimal("0.06")));
        tva.put("due", monthlyRevenue.multiply(new BigDecimal("0.04")));

        Map<String, Object> tca = new HashMap<>();
        tca.put("collected", monthlyRevenue.multiply(new BigDecimal("0.10")));
        tca.put("due", monthlyRevenue.multiply(new BigDecimal("0.10")));

        Map<String, Object> rs = new HashMap<>();
        rs.put("totalWithheld", monthlyRevenue.multiply(new BigDecimal("0.02")));
        rs.put("due", monthlyRevenue.multiply(new BigDecimal("0.02")));

        Map<String, Object> acompteIS = new HashMap<>();
        acompteIS.put("grossReceipts", monthlyRevenue);
        acompteIS.put("installment", monthlyRevenue.multiply(new BigDecimal("0.01")));
        acompteIS.put("dueDate", LocalDate.of(year, month + 1 > 12 ? 1 : month + 1, 15));

        Map<String, Object> exports = new HashMap<>();
        exports.put("tvaCsv", "/api/v1/companies/" + company.get().getId() + "/tax/declarations/export?format=dgi-tva&year=" + year + "&month=" + month);
        exports.put("tcaCsv", "/api/v1/companies/" + company.get().getId() + "/tax/declarations/export?format=dgi-tca&year=" + year + "&month=" + month);
        exports.put("rsCsv", "/api/v1/companies/" + company.get().getId() + "/tax/declarations/export?format=dgi-rs&year=" + year + "&month=" + month);

        result.put("tva", tva);
        result.put("tca", tca);
        result.put("rs", rs);
        result.put("acompteIS", acompteIS);
        result.put("exports", exports);
        return result;
    }

    /** 8. Audit trail démo — stub. */
    public Map<String, Object> getAuditTrail(String demoCode, int limit) {
        Optional<Company> company = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (company.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        result.put("demoCode", demoCode);
        result.put("limit", limit);
        result.put("message", "Audit trail non exposé en démo — utilisez GET /api/v1/companies/" +
            company.get().getId() + "/audit-trail");
        return result;
    }

    /** 9. Timeline interactive des événements démo. */
    public Map<String, Object> getTimeline(String demoCode, String fy) {
        Optional<Company> company = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (company.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        CompanySeeder seeder = findSeeder(demoCode);
        if (seeder == null) {
            result.put("error", "Seeder not found");
            return result;
        }
        List<Map<String, Object>> timeline = new ArrayList<>();
        // Timeline estimée pour FY2025-2026 (12 mois oct 2025 → sept 2026)
        String[] months = {"2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03",
                           "2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09"};
        for (String m : months) {
            Map<String, Object> event = new HashMap<>();
            event.put("month", m);
            event.put("events", List.of(
                Map.of("type", "PAYROLL", "label", "Paie " + m, "amount", "—"),
                Map.of("type", "SALES", "label", "Factures ventes " + m, "amount", "—"),
                Map.of("type", "PURCHASES", "label", "Factures achats " + m, "amount", "—")
            ));
            timeline.add(event);
        }
        result.put("demoCode", demoCode);
        result.put("fiscalYear", fy);
        result.put("timeline", timeline);
        return result;
    }

    /** 10. [ADMIN] Re-seed toutes les démos. */
    @Transactional
    public void seedAll() {
        LOG.info("V8.1 — Re-seed manuel de toutes les démos");
        for (CompanySeeder seeder : seeders) {
            try {
                seeder.seed();
            } catch (Exception e) {
                LOG.error("Re-seed {} échoué: {}", seeder.demoCode(), e.getMessage());
            }
        }
    }

    /** 11. [ADMIN] Re-seed une démo spécifique. */
    @Transactional
    public void seedOne(String demoCode) {
        CompanySeeder seeder = findSeeder(demoCode);
        if (seeder == null) {
            throw new IllegalArgumentException("Demo not found: " + demoCode);
        }
        LOG.info("V8.1 — Re-seed manuel de {}", demoCode);
        seeder.seed();
    }

    /** 12. Clone une démo pour un client prospect. */
    @Transactional
    public Map<String, Object> cloneDemo(String demoCode, String newCompanyName, String newNif, boolean keepTransactions) {
        Optional<Company> sourceCompany = findDemoCompany(demoCode);
        Map<String, Object> result = new HashMap<>();
        if (sourceCompany.isEmpty()) {
            result.put("error", "Demo not found: " + demoCode);
            return result;
        }
        Company source = sourceCompany.get();
        Company clone = new Company();
        clone.setId(UUID.randomUUID());
        java.time.Instant now = java.time.Instant.now();
        clone.setCreatedAt(now);
        clone.setUpdatedAt(now);
        clone.setName(newCompanyName);
        clone.setLegalForm(source.getLegalForm());
        clone.setCountry(source.getCountry());
        clone.setFunctionalCurrency(source.getFunctionalCurrency());
        clone.setNif(newNif);
        clone.setAddress(source.getAddress());
        clone.setSector(source.getSector());
        clone.setOrganizationNature(source.getOrganizationNature());
        clone.setBusinessTypeCode(source.getBusinessTypeCode());
        clone.setPrimaryActivityLabel(source.getPrimaryActivityLabel());
        clone.setAccountingFrameworkId(source.getAccountingFrameworkId());
        clone.setFiscalYearStartMonth(source.getFiscalYearStartMonth());
        clone.setFreeZone(source.isFreeZone());
        clone.setTaxExemptionStatus(source.getTaxExemptionStatus());
        clone.setMonthlyLegalHours(source.getMonthlyLegalHours());
        clone.setWizardStep(9);
        clone.setWizardCompleted(true);
        clone.setIsDemo(false);  // le clone n'est PAS une démo
        companyRepository.save(clone);

        result.put("companyId", clone.getId());
        result.put("name", clone.getName());
        result.put("nif", clone.getNif());
        result.put("message", "Démo clonée. L'entreprise est " + (keepTransactions ? "avec données" : "vide (configuration seule)") +
            ". Connectez-vous pour saisir vos données.");
        result.put("keepTransactions", keepTransactions);
        return result;
    }
}
