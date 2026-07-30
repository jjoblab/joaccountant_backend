package jo.accountant.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository.AccountAggregate;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.demo.dto.DemoCompanySummary;
import jo.accountant.demo.dto.DemoDashboard;
import jo.accountant.demo.dto.DemoDashboard.Alert;
import jo.accountant.demo.dto.DemoDashboard.Kpi;
import jo.accountant.demo.dto.DemoDashboard.MonthlyAmount;
import jo.accountant.demo.dto.DemoDashboard.TransactionSummary;
import jo.accountant.demo.seeders.CompanySeeder;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.1 — Service central du module Démos.
 *
 * <p>V9 — KPIs calculés depuis les <em>vraies écritures comptables</em> agrégées par compte via
 * {@link JournalLineRepository#aggregateByAccountBetweenDates(UUID, LocalDate, LocalDate)}. Les
 * totaux CA / charges / trésorerie / IS mensuels sont désormais dérivés des mouvements réels postés
 * par les seeders (RetailCommerceSeeder, ProfessionalServicesSeeder, NgoHumanitarianSeeder,
 * FreeZoneIndustrySeeder) sur les 2 exercices fiscaux (FY2024-2025 + FY2025-2026).
 *
 * <p><b>Fallback estimé</b> : si l'agrégation SQL retourne une liste vide (seeder pas encore
 * exécuté, ou écritures DRAFT non postées), on retombe sur les valeurs estimées historiques de la
 * V8.1 (CA = 6M HTG pour BOUTIK_LAKAY, etc.) afin de ne jamais casser les dashboards publics.
 *
 * <p>Expose les 4 entreprises démo et leurs KPIs via les endpoints publics GET /api/v1/demos/**
 * (lecture seule, sans auth).
 */
@Service
@Transactional(readOnly = true)
public class DemoService {

  private static final Logger log = LoggerFactory.getLogger(DemoService.class);

  /** Libellés des 12 mois de l'exercice fiscal haïtien (Oct→Sep), dans l'ordre du FY. */
  private static final String[] FY_MONTH_LABELS = {
    "Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep"
  };

  private final CompanyRepository companyRepository;
  private final List<CompanySeeder> seeders;
  private final JournalLineRepository journalLineRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountRepository accountRepository;
  private final SalesInvoiceRepository salesInvoiceRepository;

  public DemoService(
      CompanyRepository companyRepository,
      List<CompanySeeder> seeders,
      JournalLineRepository journalLineRepository,
      JournalEntryRepository journalEntryRepository,
      AccountRepository accountRepository,
      SalesInvoiceRepository salesInvoiceRepository) {
    this.companyRepository = companyRepository;
    this.seeders = seeders;
    this.journalLineRepository = journalLineRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.accountRepository = accountRepository;
    this.salesInvoiceRepository = salesInvoiceRepository;
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
    return seeders.stream().filter(s -> s.demoCode().equals(demoCode)).findFirst().orElse(null);
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
        company.getId());
  }

  // ==========================================================================
  // V9 — buildDashboard : KPIs calculés depuis les vraies écritures comptables
  // ==========================================================================

  private DemoDashboard buildDashboard(CompanySeeder seeder, Company company, String fy) {
    // 1. Plage de dates du FY (exercice fiscal haïtien : 01/10 → 30/09)
    LocalDate fyStart;
    LocalDate fyEnd;
    if (fy != null && fy.contains("2024-2025")) {
      fyStart = LocalDate.of(2024, 10, 1);
      fyEnd = LocalDate.of(2025, 9, 30);
    } else {
      // Défaut : FY2025-2026 (01/10/2025 → 30/09/2026)
      fyStart = LocalDate.of(2025, 10, 1);
      fyEnd = LocalDate.of(2026, 9, 30);
    }

    // 2. Tente l'agrégation réelle — fallback estimé si vide ou en erreur
    List<AccountAggregate> aggregates;
    try {
      aggregates =
          journalLineRepository.aggregateByAccountBetweenDates(company.getId(), fyStart, fyEnd);
    } catch (RuntimeException e) {
      log.warn(
          "Dashboard {} {} : échec agrégation SQL ({}) — fallback estimé",
          company.getName(),
          fy,
          e.getMessage());
      return buildEstimatedDashboard(seeder, company, fy);
    }
    if (aggregates == null || aggregates.isEmpty()) {
      log.info(
          "Dashboard {} {} : aucune écriture POSTED sur le FY — fallback estimé",
          company.getName(),
          fy);
      return buildEstimatedDashboard(seeder, company, fy);
    }
    return buildRealDashboard(seeder, company, fy, fyStart, fyEnd, aggregates);
  }

  /**
   * Calcule les KPIs depuis les vraies agrégations comptables.
   *
   * <p>Stratégie de requêtes SQL (4 requêtes max — vs 12 requêtes naïves par mois) :
   *
   * <ol>
   *   <li>{@code aggregateByAccountBetweenDates(companyId, fyStart, fyEnd)} — agrégats annuels par
   *       compte (pour CA / charges / netResult).
   *   <li>{@code accountRepository.findByCompanyIdOrderByCode(companyId)} — pré-charge tous les
   *       comptes (Map code→reportingClass) pour éviter 1 requête par agrégat.
   *   <li>{@code aggregateByAccountUpToDate(companyId, fyEnd)} — solde cumulé trésorerie à la
   *       clôture du FY (cashPosition).
   *   <li>{@code findAllPostedBetweenDates(companyId, fyStart, fyEnd)} — toutes les lignes du FY,
   *       groupées en Java par {@code entryDate.monthValue} pour les 12 MonthlyAmount (évite 12
   *       requêtes SQL).
   * </ol>
   */
  private DemoDashboard buildRealDashboard(
      CompanySeeder seeder,
      Company company,
      String fy,
      LocalDate fyStart,
      LocalDate fyEnd,
      List<AccountAggregate> aggregates) {
    UUID companyId = company.getId();
    String currency = company.getFunctionalCurrency();

    // Pré-charge tous les comptes en Map<code, reportingClass> (1 requête)
    Map<String, ReportingClass> reportingClassByCode = new HashMap<>();
    for (Account a : accountRepository.findByCompanyIdOrderByCode(companyId)) {
      reportingClassByCode.put(a.getCode(), a.getReportingClass());
    }

    // --- CA / charges / résultat net (depuis aggregates) ---
    BigDecimal totalRevenue = BigDecimal.ZERO;
    BigDecimal totalExpenses = BigDecimal.ZERO;
    for (AccountAggregate agg : aggregates) {
      String code = agg.getAccountCode();
      ReportingClass rc = reportingClassByCode.get(code);
      if (rc == null) continue; // compte inconnu (désactivé/supprimé) — ignore
      BigDecimal debit = nz(agg.getTotalDebit());
      BigDecimal credit = nz(agg.getTotalCredit());
      if (rc == ReportingClass.PRODUITS) {
        // Compte de produit : solde créditeur — revenue = credit - debit
        totalRevenue = totalRevenue.add(credit.subtract(debit));
      } else if (rc == ReportingClass.CHARGES) {
        // Compte de charge : solde débiteur — expense = debit - credit
        totalExpenses = totalExpenses.add(debit.subtract(credit));
      }
    }
    BigDecimal netResult = totalRevenue.subtract(totalExpenses);

    // --- IS selon taxExemptionStatus (v8-1) ---
    BigDecimal incomeTax = computeIncomeTax(company, netResult);

    // --- cashPosition : solde cumulé des comptes ACTIF de trésorerie (code commence par "5")
    //     à la date de clôture du FY (521 Banque, 530 Caisse, etc.) ---
    BigDecimal cashPosition;
    try {
      List<AccountAggregate> upToFyEnd =
          journalLineRepository.aggregateByAccountUpToDate(companyId, fyEnd);
      BigDecimal cash = BigDecimal.ZERO;
      for (AccountAggregate agg : upToFyEnd) {
        String code = agg.getAccountCode();
        if (code == null || !code.startsWith("5")) continue;
        ReportingClass rc = reportingClassByCode.get(code);
        if (rc != ReportingClass.ACTIF) continue;
        // Compte d'actif : solde débiteur — balance = debit - credit
        cash = cash.add(nz(agg.getTotalDebit()).subtract(nz(agg.getTotalCredit())));
      }
      cashPosition = cash;
    } catch (RuntimeException e) {
      log.warn(
          "Dashboard {} {} : échec cashPosition ({}) — fallback 15% CA",
          company.getName(), fy, e.getMessage());
      cashPosition = totalRevenue.multiply(new BigDecimal("0.15"));
    }

    // --- monthlyRevenue / monthlyExpenses : 12 MonthlyAmount groupés par mois ---
    List<MonthlyAmount> monthlyRevList = new ArrayList<>();
    List<MonthlyAmount> monthlyExpList = new ArrayList<>();
    BigDecimal[] revByMonth = new BigDecimal[12];
    BigDecimal[] expByMonth = new BigDecimal[12];
    for (int i = 0; i < 12; i++) {
      revByMonth[i] = BigDecimal.ZERO;
      expByMonth[i] = BigDecimal.ZERO;
    }
    try {
      // 1 requête SQL pour TOUTES les lignes POSTED du FY — groupé en Java par entryDate.monthValue
      List<JournalLine> lines =
          journalLineRepository.findAllPostedBetweenDates(companyId, fyStart, fyEnd);
      // 1 requête pour récupérer les dates des écritures (les lignes ne portent pas entryDate)
      Set<UUID> entryIds =
          lines.stream().map(JournalLine::getJournalEntryId).collect(Collectors.toSet());
      Map<UUID, LocalDate> entryDateById = new HashMap<>();
      if (!entryIds.isEmpty()) {
        for (JournalEntry e : journalEntryRepository.findAllById(entryIds)) {
          entryDateById.put(e.getId(), e.getEntryDate());
        }
      }
      for (JournalLine line : lines) {
        LocalDate entryDate = entryDateById.get(line.getJournalEntryId());
        if (entryDate == null) continue;
        int idx = monthIndexInFY(entryDate.getMonthValue());
        if (idx < 0) continue; // hors FY (ne devrait pas arriver grâce au filtre SQL)
        ReportingClass rc = reportingClassByCode.get(line.getAccountCode());
        if (rc == null) continue;
        BigDecimal debit = nz(line.getDebit());
        BigDecimal credit = nz(line.getCredit());
        if (rc == ReportingClass.PRODUITS) {
          revByMonth[idx] = revByMonth[idx].add(credit.subtract(debit));
        } else if (rc == ReportingClass.CHARGES) {
          expByMonth[idx] = expByMonth[idx].add(debit.subtract(credit));
        }
      }
    } catch (RuntimeException e) {
      log.warn(
          "Dashboard {} {} : échec monthly breakdown ({}) — fallback plat (CA/12, charges/12)",
          company.getName(),
          fy,
          e.getMessage());
      BigDecimal flatRev = totalRevenue.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
      BigDecimal flatExp = totalExpenses.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
      for (int i = 0; i < 12; i++) {
        revByMonth[i] = flatRev;
        expByMonth[i] = flatExp;
      }
    }
    for (int i = 0; i < 12; i++) {
      monthlyRevList.add(
          new MonthlyAmount(FY_MONTH_LABELS[i], revByMonth[i].setScale(2, RoundingMode.HALF_UP)));
      monthlyExpList.add(
          new MonthlyAmount(FY_MONTH_LABELS[i], expByMonth[i].setScale(2, RoundingMode.HALF_UP)));
    }

    // --- recentTransactions : 5 dernières JournalEntry POSTED dans le FY ---
    List<TransactionSummary> recentTxs = new ArrayList<>();
    try {
      List<JournalEntry> recent =
          journalEntryRepository
              .searchEntries(
                  companyId,
                  fyStart,
                  fyEnd,
                  null,
                  null,
                  JournalEntryStatus.POSTED,
                  PageRequest.of(0, 5))
              .getContent();
      for (JournalEntry e : recent) {
        List<JournalLine> entryLines =
            journalLineRepository.findByJournalEntryIdOrderByLineNumber(e.getId());
        // Total = somme des débits (= somme des crédits pour une écriture équilibrée)
        BigDecimal amount =
            entryLines.stream()
                .map(l -> nz(l.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        String type = mapSourceModule(e.getSourceModule());
        String label = e.getDescription();
        if (label == null || label.isBlank()) label = e.getReference();
        if (label == null || label.isBlank()) label = type;
        String dateStr = e.getEntryDate() != null ? e.getEntryDate().toString() : "";
        recentTxs.add(new TransactionSummary(dateStr, type, label, amount, currency));
      }
    } catch (RuntimeException e) {
      log.warn(
          "Dashboard {} {} : échec recent transactions ({})",
          company.getName(),
          fy,
          e.getMessage());
    }
    if (recentTxs.isEmpty()) {
      // Fallback : 2 transactions placeholder (déjà présente en V8.1)
      BigDecimal monthlyRev = totalRevenue.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
      BigDecimal monthlyExp = totalExpenses.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
      recentTxs =
          List.of(
              new TransactionSummary(
                  fyEnd.minusMonths(2).toString(),
                  "PAYROLL",
                  "Paie " + FY_MONTH_LABELS[10] + " " + fyEnd.getYear(),
                  monthlyExp.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP),
                  currency),
              new TransactionSummary(
                  fyEnd.minusMonths(2).toString(),
                  "SALES_INVOICE",
                  "Factures clients " + FY_MONTH_LABELS[10] + " " + fyEnd.getYear(),
                  monthlyRev.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP),
                  currency));
    }

    Kpi kpi =
        new Kpi(
            totalRevenue.setScale(2, RoundingMode.HALF_UP),
            totalExpenses.setScale(2, RoundingMode.HALF_UP),
            netResult.setScale(2, RoundingMode.HALF_UP),
            incomeTax,
            cashPosition.setScale(2, RoundingMode.HALF_UP),
            monthlyRevList,
            monthlyExpList);

    // --- Alerts (DGI deadlines) — garde l'existant, basé sur monthlyRevenue réel ---
    BigDecimal monthlyRevForAlerts =
        totalRevenue.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
    List<Alert> alerts =
        List.of(
            new Alert(
                "DGI_DEADLINE",
                "2026-08-15",
                "TVA Juillet 2026",
                monthlyRevForAlerts
                    .multiply(new BigDecimal("0.10"))
                    .setScale(2, RoundingMode.HALF_UP)),
            new Alert(
                "DGI_DEADLINE",
                "2026-08-15",
                "Acompte IS 1% Juillet 2026",
                monthlyRevForAlerts
                    .multiply(new BigDecimal("0.01"))
                    .setScale(2, RoundingMode.HALF_UP)));

    log.info(
        "Dashboard {} {} : CA={} {}, charges={} {}, résultat={} {}, IS={} {}",
        company.getName(),
        fy,
        totalRevenue.setScale(2, RoundingMode.HALF_UP),
        currency,
        totalExpenses.setScale(2, RoundingMode.HALF_UP),
        currency,
        netResult.setScale(2, RoundingMode.HALF_UP),
        currency,
        incomeTax,
        currency);

    return new DemoDashboard(
        companyId, seeder.demoCode(), seeder.companyName(), fy, kpi, alerts, recentTxs);
  }

  /**
   * Calcule l'Impôt sur les Sociétés (IS) selon {@link Company#getTaxExemptionStatus()} (v8-1, Code
   * Fiscal Haïti art. 195) :
   *
   * <ul>
   *   <li>{@code STANDARD} — IS 30% sur le résultat fiscal.
   *   <li>{@code FREE_ZONE} — IS réduit 15% (zone franche CODEVI/SONAPI).
   *   <li>{@code NGO_EXEMPT} — IS 0% (ONG agréée).
   * </ul>
   *
   * <p>Si le résultat est négatif ou nul, l'IS est de 0 (pas de minimum IS — simplification démo).
   */
  private BigDecimal computeIncomeTax(Company company, BigDecimal netResult) {
    if (netResult == null || netResult.signum() <= 0) return BigDecimal.ZERO;
    return switch (company.getTaxExemptionStatus()) {
      case NGO_EXEMPT -> BigDecimal.ZERO;
      case FREE_ZONE ->
          netResult.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);
      default -> netResult.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
    };
  }

  /**
   * Retourne l'index (0..11) d'un numéro de mois calendar (1=Jan … 12=Dec) dans l'ordre de
   * l'exercice fiscal haïtien Oct→Sep :
   *
   * <p>{@code Oct(10)→0, Nov(11)→1, Dec(12)→2, Jan(1)→3, Feb(2)→4, ..., Sep(9)→11}.
   *
   * @return index 0..11, ou -1 si le mois est hors plage (ne devrait pas arriver).
   */
  private int monthIndexInFY(int monthValue) {
    if (monthValue >= 10) return monthValue - 10; // Oct→0, Nov→1, Dec→2
    if (monthValue >= 1 && monthValue <= 9) return monthValue + 2; // Jan→3, ..., Sep→11
    return -1;
  }

  /**
   * Map un {@link JournalEntrySourceModule} vers un libellé court affiché dans les {@code
   * recentTransactions} du dashboard (PAYROLL, SALES_INVOICE, PURCHASE_INVOICE, EXPENSE_REPORT,
   * etc.).
   */
  private String mapSourceModule(JournalEntrySourceModule src) {
    if (src == null) return "MANUAL";
    return switch (src) {
      case INVOICING -> "SALES_INVOICE";
      case PURCHASING -> "PURCHASE_INVOICE";
      case PAYROLL -> "PAYROLL";
      case EXPENSES -> "EXPENSE_REPORT";
      case INVENTORY -> "INVENTORY";
      case FIXED_ASSETS -> "FIXED_ASSETS";
      case FUNDS_GRANTS -> "FUNDS_GRANTS";
      case REVERSAL -> "REVERSAL";
      case MANUAL -> "MANUAL";
    };
  }

  /** BigDecimal null-safe → ZERO. */
  private BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  // ==========================================================================
  // Fallback estimé (V8.1) — utilisé si aucun agrégat réel n'est disponible
  // ==========================================================================

  private DemoDashboard buildEstimatedDashboard(CompanySeeder seeder, Company company, String fy) {
    BigDecimal annualRevenue = annualRevenueForSegment(seeder.segment());
    BigDecimal monthlyRevenue = annualRevenue.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
    BigDecimal annualExpenses = annualRevenue.multiply(new BigDecimal("0.85")); // marge 15%
    BigDecimal monthlyExpenses =
        annualExpenses.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
    BigDecimal netResult = annualRevenue.subtract(annualExpenses);

    // IS selon taxExemptionStatus
    BigDecimal incomeTax;
    if (company.getTaxExemptionStatus()
        == jo.accountant.company.entity.TaxExemptionStatus.NGO_EXEMPT) {
      incomeTax = BigDecimal.ZERO;
    } else if (company.getTaxExemptionStatus()
        == jo.accountant.company.entity.TaxExemptionStatus.FREE_ZONE) {
      incomeTax = netResult.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);
    } else {
      incomeTax = netResult.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal cashPosition = annualRevenue.multiply(new BigDecimal("0.15"));

    // 12 mois de CA + charges (variation saisonnière — décembre pic Noël, août creux)
    List<MonthlyAmount> monthlyRevList = new ArrayList<>();
    List<MonthlyAmount> monthlyExpList = new ArrayList<>();
    for (String m : FY_MONTH_LABELS) {
      BigDecimal factor = new BigDecimal("1.0");
      if (m.equals("Dec")) factor = new BigDecimal("1.5");
      else if (m.equals("Aug")) factor = new BigDecimal("0.7");
      else if (m.equals("Feb")) factor = new BigDecimal("1.2"); // carnaval
      monthlyRevList.add(
          new MonthlyAmount(m, monthlyRevenue.multiply(factor).setScale(2, RoundingMode.HALF_UP)));
      monthlyExpList.add(
          new MonthlyAmount(m, monthlyExpenses.multiply(factor).setScale(2, RoundingMode.HALF_UP)));
    }

    Kpi kpi =
        new Kpi(
            annualRevenue,
            annualExpenses,
            netResult,
            incomeTax,
            cashPosition,
            monthlyRevList,
            monthlyExpList);

    List<Alert> alerts =
        List.of(
            new Alert(
                "DGI_DEADLINE",
                "2026-08-15",
                "TVA Juillet 2026",
                monthlyRevenue.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP)),
            new Alert(
                "DGI_DEADLINE",
                "2026-08-15",
                "Acompte IS 1% Juillet 2026",
                monthlyRevenue.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP)));

    List<TransactionSummary> txs =
        List.of(
            new TransactionSummary(
                "2026-07-31",
                "PAYROLL",
                "Paie juillet 2026",
                monthlyExpenses.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP),
                company.getFunctionalCurrency()),
            new TransactionSummary(
                "2026-07-15",
                "SALES_INVOICE",
                "Factures clients juillet (quinzaine 1)",
                monthlyRevenue.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP),
                company.getFunctionalCurrency()));

    log.info(
        "Dashboard {} {} (ESTIMÉ fallback) : CA={} {}, charges={} {}, résultat={} {}, IS={} {}",
        company.getName(),
        fy,
        annualRevenue,
        company.getFunctionalCurrency(),
        annualExpenses,
        company.getFunctionalCurrency(),
        netResult,
        company.getFunctionalCurrency(),
        incomeTax,
        company.getFunctionalCurrency());

    return new DemoDashboard(
        company.getId(), seeder.demoCode(), seeder.companyName(), fy, kpi, alerts, txs);
  }

  // ==========================================================================
  // Helpers statiques par segment (toujours utilisés pour les summary)
  // ==========================================================================

  private List<String> modulesForSegment(String segment) {
    return switch (segment) {
      case "RETAIL_COMMERCE" ->
          List.of(
              "invoicing",
              "purchasing",
              "inventory",
              "payroll",
              "tax",
              "financial-statements",
              "reporting");
      case "PROFESSIONAL_SERVICES" ->
          List.of(
              "invoicing",
              "purchasing",
              "time-billing",
              "expenses",
              "payroll",
              "tax",
              "financial-statements",
              "reporting",
              "analytics");
      case "NGO_HUMANITARIAN" ->
          List.of(
              "funds-grants",
              "invoicing",
              "purchasing",
              "expenses",
              "payroll",
              "tax",
              "financial-statements",
              "reporting",
              "analytics",
              "fx-operations",
              "bank-reconciliation");
      case "WHOLESALE_COMMERCE" ->
          List.of(
              "invoicing",
              "purchasing",
              "inventory",
              "payroll",
              "tax",
              "financial-statements",
              "reporting",
              "fx-operations");
      default -> List.of();
    };
  }

  private List<String> highlightsForSegment(String segment) {
    return switch (segment) {
      case "RETAIL_COMMERCE" ->
          List.of(
              "Multi-taxe TVA 10% + TCA 10% sur livraisons (V67)",
              "Stock FIFO avec COGS automatique",
              "13e mois en décembre (Code Travail art. 153)",
              "Déclarations DGI mensuelles complètes (TVA+TCA+RS+acompte IS)");
      case "PROFESSIONAL_SERVICES" ->
          List.of(
              "Time-billing multi-niveaux (BillableRate projet+ressource)",
              "Auto-approbation timesheet bloquée (règle 4 yeux, v7-9)",
              "RS 2% retenue par clients + RS 30% non-résidents (V64)",
              "Multi-taxe TVA+TCA cumulatives sur même ligne (V67)");
      case "NGO_HUMANITARIAN" ->
          List.of(
              "4 bailleurs (USAID/EU/BM/CRS) + formats structurés (USAID SF-425, EU PRAG, BM)",
              "Alimentation auto donor_report_line via tagging (v7-1)",
              "IS 0% NGO_EXEMPT + TVA exonérée (Code Fiscal art. 195, v8-1)",
              "Conversion USD→HTG + CTA en capitaux propres (v7-3)");
      case "WHOLESALE_COMMERCE" ->
          List.of(
              "IS 15% zone franche (Code Fiscal art. 195, v8-1)",
              "TVA 0% export + imports en franchise (v8-6 VAT_EXEMPT_ZF)",
              "Keyset pagination 50K factures/an (v7-8)",
              "Spring Batch paie 1200 employés + 13e mois async (v8-7)",
              "IFRS_FULL complet : Bilan + CTA + CR + CF + SCE IAS 1.106 (v7-2)");
      default -> List.of();
    };
  }

  private BigDecimal annualRevenueForSegment(String segment) {
    return switch (segment) {
      case "RETAIL_COMMERCE" -> new BigDecimal("6000000");
      case "PROFESSIONAL_SERVICES" -> new BigDecimal("18000000");
      case "NGO_HUMANITARIAN" -> new BigDecimal("60000000"); // ~5M USD
      case "WHOLESALE_COMMERCE" -> new BigDecimal("144000000"); // ~12M USD
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
}
