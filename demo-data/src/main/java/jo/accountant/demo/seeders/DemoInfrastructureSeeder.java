package jo.accountant.demo.seeders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Crée l'infrastructure comptable pour les entreprises démo.
 *
 * <p>Utilise JdbcTemplate directement pour éviter les problèmes de cache Caffeine
 * (@Cacheable n'accepte pas les valeurs null).
 
 *
 * @author jo@Dev


*/
@Component
public class DemoInfrastructureSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DemoInfrastructureSeeder.class);

    private static final UUID PCN_HAITI_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID IFRS_FULL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CompanyRepository companyRepository;
    private final JdbcTemplate jdbcTemplate;

    public DemoInfrastructureSeeder(CompanyRepository companyRepository,
                                      JdbcTemplate jdbcTemplate) {
        this.companyRepository = companyRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void seedInfrastructureForAllDemoCompanies() {
        List<Company> demoCompanies = companyRepository.findAll().stream()
            .filter(c -> Boolean.TRUE.equals(c.getIsDemo()))
            .toList();

        LOG.info("V8.2 — Infrastructure seed pour {} entreprises démo", demoCompanies.size());

        for (Company company : demoCompanies) {
            try {
                seedInfrastructureForCompany(company);
            } catch (Exception e) {
                LOG.error("V8.2 — Infrastructure seed échoué pour {} : {}", company.getName(), e.getMessage(), e);
            }
        }
    }

    private void seedInfrastructureForCompany(Company company) {
        UUID companyId = company.getId();
        LOG.info("V8.2 — Infrastructure pour {} (id={})", company.getName(), companyId);

        // Set framework if missing
        UUID frameworkId = company.getAccountingFrameworkId();
        if (frameworkId == null) {
            frameworkId = company.isFreeZone() ? IFRS_FULL_ID : PCN_HAITI_ID;
            jdbcTemplate.update("UPDATE companies SET accounting_framework_id = ? WHERE id = ?",
                frameworkId, companyId);
        }

        // 1. Create chart of accounts via direct SQL (bypass cache)
        createChartOfAccounts(companyId, frameworkId, company.getBusinessTypeCode(), company.isFreeZone());

        // 2. Create 4 standard journals via direct SQL
        createJournal(companyId, "VT", "Journal des Ventes");
        createJournal(companyId, "AC", "Journal des Achats");
        createJournal(companyId, "BQ", "Journal de Banque");
        createJournal(companyId, "OD", "Opérations Diverses");

        // 3. Create fiscal year FY2025-2026 with 12 periods
        createFiscalYear(companyId, 2025, 10);

        LOG.info("V8.2 — Infrastructure complète pour {}", company.getName());
    }

    private void createChartOfAccounts(UUID companyId, UUID frameworkId, String businessTypeCode, boolean isFreeZone) {
        // Check if accounts already exist
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account WHERE company_id = ?", Integer.class, companyId);
        if (count != null && count > 0) {
            LOG.info("V8.2 — Plan comptable déjà existant ({} comptes) pour company {}", count, companyId);
            return;
        }

        // Create basic PCN_HAITI accounts (level 1 classes)
        String[][] pcnAccounts = {
            {"1", "Comptes de capitaux", "CAPITAUX_PROPRES", "CREDIT"},
            {"2", "Comptes d'immobilisations", "ACTIF", "DEBIT"},
            {"3", "Comptes de stocks", "ACTIF", "DEBIT"},
            {"4", "Comptes de tiers", "PASSIF", "CREDIT"},
            {"5", "Comptes financiers", "ACTIF", "DEBIT"},
            {"6", "Comptes de charges", "CHARGES", "DEBIT"},
            {"7", "Comptes de produits", "PRODUITS", "CREDIT"},
            {"8", "Comptes spéciaux", "OTHER", "DEBIT"}
        };

        for (String[] acc : pcnAccounts) {
            try {
                jdbcTemplate.update(
                    "INSERT INTO account (id, company_id, code, label, level, reporting_class, " +
                    "reporting_subcategory, normal_balance, locked, active, is_collective, path, version, " +
                    "created_at, updated_at) " +
                    "VALUES (uuidv7(), ?, ?, ?, 1, ?, ?, ?, TRUE, TRUE, TRUE, ?, 0, NOW(), NOW()) " +
                    "ON CONFLICT DO NOTHING",
                    companyId, acc[0], acc[1], acc[2], "N_A", acc[3], acc[0]
                );
            } catch (Exception e) {
                LOG.debug("V8.2 — Compte {} existe déjà", acc[0]);
            }
        }

        // Create level 2 accounts for key categories
        String[][] level2Accounts = {
            {"101", "Capital social", "1", "CAPITAUX_PROPRES", "CREDIT"},
            {"411", "Clients", "4", "PASSIF", "CREDIT"},
            {"401", "Fournisseurs", "4", "PASSIF", "CREDIT"},
            {"421", "Personnel - rémunérations dues", "4", "PASSIF", "CREDIT"},
            {"443", "TVA collectée", "4", "PASSIF", "CREDIT"},
            {"4436", "TVA déductible", "4", "PASSIF", "CREDIT"},
            {"521", "Banque", "5", "ACTIF", "DEBIT"},
            {"607", "Achats de marchandises", "6", "CHARGES", "DEBIT"},
            {"631", "Rémunérations du personnel", "6", "CHARGES", "DEBIT"},
            {"707", "Ventes de marchandises", "7", "PRODUITS", "CREDIT"},
            {"706", "Prestations de services", "7", "PRODUITS", "CREDIT"},
            {"108", "Écart de conversion (CTA)", "1", "CAPITAUX_PROPRES", "CREDIT"}
        };

        for (String[] acc : level2Accounts) {
            try {
                jdbcTemplate.update(
                    "INSERT INTO account (id, company_id, code, label, level, reporting_class, " +
                    "reporting_subcategory, normal_balance, locked, active, is_collective, path, version, " +
                    "created_at, updated_at) " +
                    "VALUES (uuidv7(), ?, ?, ?, 2, ?, ?, ?, FALSE, TRUE, FALSE, ?, 0, NOW(), NOW()) " +
                    "ON CONFLICT DO NOTHING",
                    companyId, acc[0], acc[1], acc[2], acc[3], "N_A", acc[4], acc[2] + "/" + acc[0]
                );
            } catch (Exception e) {
                LOG.debug("V8.2 — Compte {} existe déjà", acc[0]);
            }
        }

        LOG.info("V8.2 — Plan comptable créé pour company {}", companyId);
    }

    private void createJournal(UUID companyId, String code, String label) {
        // Check if journal exists
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal WHERE company_id = ? AND code = ?",
            Integer.class, companyId, code);
        if (count != null && count > 0) {
            return;
        }
        // V8.2inférer le type depuis le code (VT→VENTES, AC→ACHATS, etc.)
        // et setter active=true pour aligner avec le modèle enrichi.
        jo.accountant.accountingengine.entity.JournalType journalType =
            jo.accountant.accountingengine.entity.JournalType.fromCode(code);
        String typeValue = journalType != null ? journalType.name() : null;
        jdbcTemplate.update(
            "INSERT INTO journal (id, company_id, code, label, type, active, version, created_at, updated_at) " +
            "VALUES (uuidv7(), ?, ?, ?, ?, TRUE, 0, NOW(), NOW())",
            companyId, code, label, typeValue);
        LOG.info("V8.2 — Journal {} créé (type={})", code, typeValue);
    }

    private void createFiscalYear(UUID companyId, int startYear, int startMonth) {
        // Check if fiscal year exists
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fiscal_year WHERE company_id = ?",
            Integer.class, companyId);
        if (count != null && count > 0) {
            LOG.info("V8.2 — Exercice fiscal déjà existant pour company {}", companyId);
            return;
        }

        LocalDate startDate = LocalDate.of(startYear, startMonth, 1);
        LocalDate endDate = startDate.plusYears(1).minusDays(1);
        String label = "Exercice " + startDate.getYear() + "-" + endDate.getYear();

        // Insert fiscal year
        UUID fyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, company_id, start_date, end_date, label, status, version, " +
            "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'OPEN', 0, NOW(), NOW())",
            fyId, companyId, startDate, endDate, label);

        // Insert 12 monthly periods
        LocalDate periodStart = startDate;
        for (int i = 0; i < 12; i++) {
            LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
            String periodLabel = periodStart.getYear() + "-" + String.format("%02d", periodStart.getMonthValue());

            jdbcTemplate.update(
                "INSERT INTO fiscal_period (id, company_id, fiscal_year_id, start_date, end_date, " +
                "label, status, version, created_at, updated_at) " +
                "VALUES (uuidv7(), ?, ?, ?, ?, ?, 'OPEN', 0, NOW(), NOW())",
                companyId, fyId, periodStart, periodEnd, periodLabel);

            periodStart = periodStart.plusMonths(1);
        }

        // Activate this fiscal year on the company
        jdbcTemplate.update(
            "UPDATE companies SET active_fiscal_year_id = ? WHERE id = ?",
            fyId, companyId);

        LOG.info("V8.2 — Exercice fiscal créé : {} (12 périodes)", label);
    }
}
