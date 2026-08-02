package jo.accountant.demo.builders;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.entity.FiscalPeriod;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.Journal;
import jo.accountant.accountingengine.repository.FiscalPeriodRepository;
import jo.accountant.accountingengine.repository.FiscalYearRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.company.entity.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Contexte de données démo pour une entreprise.
 *
 * <p>Résout et cache les références nécessaires aux seeders :
 * <ul>
 * <li>Comptes collectifs par type de tiers (CLIENT → 411, SUPPLIER → 401, DONOR → 4xx, EMPLOYEE → 421)</li>
 * <li>Journaux par code (VT ventes, AC achats, BQ banque, OD opérations diverses)</li>
 * <li>Exercices fiscaux + périodes par date</li>
 * </ul>
 *
 * <p>Évite aux seeders de répéter la logique de résolution — un seul appel {@link #forCompany(Company)}
 * pré-charge tout le contexte.
 
 *
 * @author jo@Dev


*/
@Component
public class DemoDataContext {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataContext.class);

    private final AccountRepository accountRepository;
    private final JournalRepository journalRepository;
    private final FiscalYearRepository fiscalYearRepository;
    private final FiscalPeriodRepository fiscalPeriodRepository;

    public DemoDataContext(AccountRepository accountRepository,
                            JournalRepository journalRepository,
                            FiscalYearRepository fiscalYearRepository,
                            FiscalPeriodRepository fiscalPeriodRepository) {
        this.accountRepository = accountRepository;
        this.journalRepository = journalRepository;
        this.fiscalYearRepository = fiscalYearRepository;
        this.fiscalPeriodRepository = fiscalPeriodRepository;
    }

    public ResolvedContext forCompany(Company company) {
        ResolvedContext ctx = new ResolvedContext();
        ctx.companyId = company.getId();
        ctx.currency = company.getFunctionalCurrency();

        // Résoudre les comptes collectifs (411 clients, 401 fournisseurs, 421 personnel, 521 banque, 707 ventes)
        ctx.clientCollectiveAccount = safeFindAccountByCode(company.getId(), "411")
            .or(() -> findAccountByReportingClass(company.getId(), ReportingClass.ACTIF))
            .orElse(null);
        ctx.supplierCollectiveAccount = safeFindAccountByCode(company.getId(), "401")
            .or(() -> findAccountByReportingClass(company.getId(), ReportingClass.PASSIF))
            .orElse(null);
        ctx.employeeCollectiveAccount = safeFindAccountByCode(company.getId(), "421")
            .or(() -> findAccountByCode(company.getId(), "422"))
            .orElse(null);
        ctx.cashAccount = safeFindAccountByCode(company.getId(), "521")
            .or(() -> findAccountByReportingClass(company.getId(), ReportingClass.ACTIF))
            .orElse(null);
        ctx.salesAccount = safeFindAccountByCode(company.getId(), "707")
            .or(() -> findAccountByCode(company.getId(), "706"))
            .or(() -> findAccountByCode(company.getId(), "701"))
            .orElse(null);
        ctx.purchaseAccount = safeFindAccountByCode(company.getId(), "607")
            .or(() -> findAccountByCode(company.getId(), "601"))
            .orElse(null);
        ctx.vatCollectedAccount = safeFindAccountByCode(company.getId(), "443")
            .or(() -> findAccountByCode(company.getId(), "44571"))
            .orElse(null);
        ctx.vatDeductibleAccount = safeFindAccountByCode(company.getId(), "4436")
            .or(() -> findAccountByCode(company.getId(), "44566"))
            .orElse(null);
        ctx.payrollAccount = safeFindAccountByCode(company.getId(), "631")
            .or(() -> findAccountByCode(company.getId(), "641"))
            .orElse(null);
        ctx.donationRevenueAccount = safeFindAccountByCode(company.getId(), "758")
            .or(() -> findAccountByCode(company.getId(), "706"))
            .orElse(null);

        // Résoudre les journaux
        ctx.journalVT = findJournalByCode(company.getId(), "VT").orElse(null);
        ctx.journalAC = findJournalByCode(company.getId(), "AC").orElse(null);
        ctx.journalBQ = findJournalByCode(company.getId(), "BQ").orElse(null);
        ctx.journalOD = findJournalByCode(company.getId(), "OD").orElse(null);

        // Résoudre les exercices fiscaux
        ctx.fiscalYears = fiscalYearRepository.findByCompanyIdOrderByStartDateAsc(company.getId());

        LOG.info("V8.1 — Contexte démo résolu pour company {}: {} exercices fiscaux, journaux VT/AC/BQ/OD={}/{}/{}/{}, comptes résolus",
            company.getId(), ctx.fiscalYears.size(),
            ctx.journalVT != null, ctx.journalAC != null, ctx.journalBQ != null, ctx.journalOD != null);

        return ctx;
    }

    public Optional<FiscalPeriod> findFiscalPeriod(ResolvedContext ctx, LocalDate date) {
        for (FiscalYear fy : ctx.fiscalYears) {
            if (!date.isBefore(fy.getStartDate()) && !date.isAfter(fy.getEndDate())) {
                return fiscalPeriodRepository.findByFiscalYearIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    fy.getId(), date, date);
            }
        }
        return Optional.empty();
    }

    private Optional<Account> findAccountByCode(UUID companyId, String codePrefix) {
        // Cherche d'abord le code exact, puis par préfixe (ex: "411" → "411000" ou "411")
        return safeFindByCompanyIdAndCode(companyId, codePrefix)
            .or(() -> {
                List<Account> accounts = safeFindByCompanyIdOrderByCode(companyId);
                return accounts.stream()
                    .filter(a -> a.getCode() != null && a.getCode().startsWith(codePrefix))
                    .findFirst();
            });
    }

    /** — Wrapper safe qui catche les erreurs de cache (null non autorisé). */
    private Optional<Account> safeFindAccountByCode(UUID companyId, String codePrefix) {
        try {
            return findAccountByCode(companyId, codePrefix);
        } catch (Exception e) {
            LOG.warn("V8.1 — Résolution compte {} échouée pour company {} : {}", codePrefix, companyId, e.getMessage());
            return Optional.empty();
        }
    }

    /** — Wrapper safe pour findByCompanyIdAndCode (cache peut rejeter null). */
    private Optional<Account> safeFindByCompanyIdAndCode(UUID companyId, String code) {
        try {
            return accountRepository.findByCompanyIdAndCode(companyId, code);
        } catch (Exception e) {
            LOG.debug("V8.1 — findByCompanyIdAndCode échoué (cache null?) : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** — Wrapper safe pour findByCompanyIdOrderByCode. */
    private List<Account> safeFindByCompanyIdOrderByCode(UUID companyId) {
        try {
            return accountRepository.findByCompanyIdOrderByCode(companyId);
        } catch (Exception e) {
            LOG.debug("V8.1 — findByCompanyIdOrderByCode échoué : {}", e.getMessage());
            return List.of();
        }
    }

    private Optional<Account> findAccountByReportingClass(UUID companyId, ReportingClass rc) {
        try {
            return safeFindByCompanyIdOrderByCode(companyId).stream()
                .filter(a -> a.getReportingClass() == rc)
                .filter(Account::isActive)
                .findFirst();
        } catch (Exception e) {
            LOG.warn("V8.1 — findAccountByReportingClass échoué : {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Journal> findJournalByCode(UUID companyId, String code) {
        try {
            return journalRepository.findByCompanyIdAndCode(companyId, code);
        } catch (Exception e) {
            LOG.debug("V8.1 — findJournalByCode échoué : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Contexte résolu pour une entreprise — passé aux seeders. */
    public static class ResolvedContext {
        public UUID companyId;
        public String currency;
        public Account clientCollectiveAccount;
        public Account supplierCollectiveAccount;
        public Account employeeCollectiveAccount;
        public Account cashAccount;
        public Account salesAccount;
        public Account purchaseAccount;
        public Account vatCollectedAccount;
        public Account vatDeductibleAccount;
        public Account payrollAccount;
        public Account donationRevenueAccount;
        public Journal journalVT;
        public Journal journalAC;
        public Journal journalBQ;
        public Journal journalOD;
        public List<FiscalYear> fiscalYears;
    }
}
