package jo.accountant.reporting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.accountingengine.dto.LedgerLine;
import jo.accountant.accountingengine.dto.TrialBalanceLine;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import jo.accountant.approvalworkflow.repository.ApprovalRequestRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.documentgeneration.service.DocumentGenerationService;
import jo.accountant.expenses.entity.ExpenseLine;
import jo.accountant.expenses.entity.ExpenseReport;
import jo.accountant.expenses.repository.ExpenseLineRepository;
import jo.accountant.expenses.repository.ExpenseReportRepository;
import jo.accountant.financialstatements.dto.BalanceSheet;
import jo.accountant.financialstatements.dto.IncomeStatement;
import jo.accountant.financialstatements.service.FinancialStatementsService;
import jo.accountant.fixedassets.entity.Asset;
import jo.accountant.fixedassets.repository.AssetRepository;
import jo.accountant.fixedassets.repository.DepreciationScheduleLineRepository;
import jo.accountant.fxoperations.entity.FxOperation;
import jo.accountant.fxoperations.repository.FxOperationRepository;
import jo.accountant.fundsgrants.dto.DonorReport;
import jo.accountant.fundsgrants.service.FundsGrantsService;
import jo.accountant.inventory.dto.InventoryValuationResponse;
import jo.accountant.inventory.dto.StockMoveResponse;
import jo.accountant.inventory.entity.Item;
import jo.accountant.inventory.repository.ItemRepository;
import jo.accountant.inventory.repository.WarehouseRepository;
import jo.accountant.inventory.service.InventoryService;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.SalesInvoice;
import jo.accountant.invoicing.repository.SalesInvoiceRepository;
import jo.accountant.payroll.entity.PayrollRun;
import jo.accountant.payroll.repository.PayslipRepository;
import jo.accountant.payroll.repository.PayrollRunRepository;
import jo.accountant.purchasing.entity.PurchaseInvoice;
import jo.accountant.purchasing.entity.PurchaseInvoiceStatus;
import jo.accountant.purchasing.repository.PurchaseInvoiceRepository;
import jo.accountant.reporting.dto.AgedBalance;
import jo.accountant.reporting.dto.Dashboard;
import jo.accountant.reporting.dto.ExportResult;
import jo.accountant.tax.dto.TaxDeclaration;
import jo.accountant.tax.service.TaxService;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import jo.accountant.timebilling.dto.UtilizationLine;
import jo.accountant.timebilling.service.TimeBillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de reporting (§13 Phase 17 — dernière phase du projet).
 *
 * <p>Responsabilités :
 * <ul>
 * <li>Export PDF (bilan, compte de résultat, grand livre, balance, rapport bailleur) —
 * produit via :document-generation (Phase 11) : :reporting orchestre uniquement
 * quels documents exporter et sur quelle période, sans dupliquer de logique de rendu PDF.</li>
 * <li>Export Excel/CSV (grand livre, balance) — généré directement par :reporting.</li>
 * <li>Tableau de bord de synthèse (position de trésorerie, balance âgée clients/fournisseurs,
 * principales charges).</li>
 * <li><b>Part C/D/E</b> — nouveaux exports CSV sectoriels agrégeant les données des modules
 * métier : tax_declaration, purchase_register, expense_register, payroll_summary,
 * inventory_valuation, stock_movement_register, time_billing_utilization,
 * fixed_assets_register, fx_operations_register.</li>
 * </ul>
 *
 * <p><b>Gating des exports par module activé</b> (Part C2) — les exports sectoriels
 * ({@code donor_report}, {@code tax_declaration}, {@code purchase_register},
 * {@code inventory_valuation}, {@code stock_movement_register},
 * {@code time_billing_utilization}, {@code fixed_assets_register},
 * {@code fx_operations_register}) ne sont accessibles que si le module correspondant est
 * activé pour la société (via {@link ModuleAccessGuard#ensureEnabled}). Les exports communs
 * (bilan, compte de résultat, grand livre, balance, dashboard, balances âgées,
 * {@code expense_register}, {@code payroll_summary}) restent toujours accessibles —
 * ce sont des modules always-on (EXPENSES, PAYROLL) ou des vues agrégées transverses.
 *
 * <p>Formats réglementaires par pays (ex. TAFIRE pour SYSCOHADA, liasse fiscale française) :
 * hors périmètre v1, à cadrer dans un futur prompt dédié une fois le socle validé en production.
 */
@Service
public class ReportingService {

 private static final Logger LOG = LoggerFactory.getLogger(ReportingService.class);

 private final AccountingEngineService accountingEngineService;
 private final FinancialStatementsService financialStatementsService;
 private final DocumentGenerationService documentGenerationService;
 private final FundsGrantsService fundsGrantsService;
 private final AccountRepository accountRepository;
 private final SalesInvoiceRepository invoiceRepository;
 private final ApprovalRequestRepository approvalRequestRepository;
 private final ModuleAccessGuard moduleAccessGuard;

 // --- Dépendances pour les nouveaux exports CSV (Part D / E) ---

 private final TaxService taxService;
 private final ThirdPartyRepository thirdPartyRepository;
 private final PurchaseInvoiceRepository purchaseInvoiceRepository;
 private final ExpenseReportRepository expenseReportRepository;
 private final ExpenseLineRepository expenseLineRepository;
 private final PayrollRunRepository payrollRunRepository;
 private final PayslipRepository payslipRepository;
 private final InventoryService inventoryService;
 private final ItemRepository itemRepository;
 private final WarehouseRepository warehouseRepository;
 private final TimeBillingService timeBillingService;
 private final AssetRepository assetRepository;
 private final DepreciationScheduleLineRepository depreciationScheduleLineRepository;
 private final FxOperationRepository fxOperationRepository;

 public ReportingService(AccountingEngineService accountingEngineService,
 FinancialStatementsService financialStatementsService,
 DocumentGenerationService documentGenerationService,
 FundsGrantsService fundsGrantsService,
 AccountRepository accountRepository,
 SalesInvoiceRepository invoiceRepository,
 ApprovalRequestRepository approvalRequestRepository,
 ModuleAccessGuard moduleAccessGuard,
 TaxService taxService,
 ThirdPartyRepository thirdPartyRepository,
 PurchaseInvoiceRepository purchaseInvoiceRepository,
 ExpenseReportRepository expenseReportRepository,
 ExpenseLineRepository expenseLineRepository,
 PayrollRunRepository payrollRunRepository,
 PayslipRepository payslipRepository,
 InventoryService inventoryService,
 ItemRepository itemRepository,
 WarehouseRepository warehouseRepository,
 TimeBillingService timeBillingService,
 AssetRepository assetRepository,
 DepreciationScheduleLineRepository depreciationScheduleLineRepository,
 FxOperationRepository fxOperationRepository) {
 this.accountingEngineService = accountingEngineService;
 this.financialStatementsService = financialStatementsService;
 this.documentGenerationService = documentGenerationService;
 this.fundsGrantsService = fundsGrantsService;
 this.accountRepository = accountRepository;
 this.invoiceRepository = invoiceRepository;
 this.approvalRequestRepository = approvalRequestRepository;
 this.moduleAccessGuard = moduleAccessGuard;
 this.taxService = taxService;
 this.thirdPartyRepository = thirdPartyRepository;
 this.purchaseInvoiceRepository = purchaseInvoiceRepository;
 this.expenseReportRepository = expenseReportRepository;
 this.expenseLineRepository = expenseLineRepository;
 this.payrollRunRepository = payrollRunRepository;
 this.payslipRepository = payslipRepository;
 this.inventoryService = inventoryService;
 this.itemRepository = itemRepository;
 this.warehouseRepository = warehouseRepository;
 this.timeBillingService = timeBillingService;
 this.assetRepository = assetRepository;
 this.depreciationScheduleLineRepository = depreciationScheduleLineRepository;
 this.fxOperationRepository = fxOperationRepository;
 }

 // --- Exports ---

 /**
 * Génère un export PDF ou Excel/CSV selon le type de document demandé.
 *
 * <p>PDF : délègue à :document-generation. Excel/CSV : généré directement.
 *
 * <p><b>Gating des exports sectoriels</b> (Part C2) — avant de dispatch vers la méthode
 * d'export spécialisée, on vérifie que le module correspondant est activé pour la société
 * via {@link ModuleAccessGuard#ensureEnabled}. Les exports communs (bilan, compte de
 * résultat, grand livre, balance, dashboard, balances âgées, {@code expense_register},
 * {@code payroll_summary}) ne sont pas gated — ce sont des modules always-on ou des vues
 * transverses.
 *
 * @param statement type de document : balance_sheet, income_statement, general_ledger,
 * trial_balance, donor_report, tax_declaration, purchase_register,
 * expense_register, payroll_summary, inventory_valuation,
 * stock_movement_register, time_billing_utilization,
 * fixed_assets_register, fx_operations_register
 * @param format pdf ou csv (selon le type — PDF pour balance_sheet/income_statement/
 * donor_report, CSV pour les autres)
 */
 @Transactional
 public ExportResult export(UUID companyId, String statement, String format,
 LocalDate from, LocalDate to, UUID resourceId) {
 LOG.info("Export demandé : companyId={} statement={} format={}", companyId, statement, format);

 // Part C2 — module gating par statement. Les statements non listés ici sont
 // toujours accessibles (common / always-on modules).
 ensureModuleEnabledForStatement(companyId, statement);

 return switch (statement.toLowerCase()) {
 case "balance_sheet" -> exportBalanceSheetPdf(companyId, to != null ? to : LocalDate.now());
 case "income_statement" -> exportIncomeStatementPdf(companyId,
 from != null ? from : LocalDate.of(LocalDate.now().getYear(), 1, 1),
 to != null ? to : LocalDate.now());
 case "general_ledger" -> exportGeneralLedger(companyId, from, to, format);
 case "trial_balance" -> exportTrialBalance(companyId, format);
 case "donor_report" -> exportDonorReportPdf(companyId, resourceId);
 // Part D — nouveaux exports CSV communs
 case "tax_declaration" -> exportTaxDeclarationCsv(companyId, from, to);
 case "purchase_register" -> exportPurchaseRegisterCsv(companyId, from, to);
 case "expense_register" -> exportExpenseRegisterCsv(companyId, from, to);
 case "payroll_summary" -> exportPayrollSummaryCsv(companyId, from, to);
 // Part E4 — nouveaux exports CSV sectoriels
 case "inventory_valuation" -> exportInventoryValuationCsv(companyId);
 case "stock_movement_register" -> exportStockMovementRegisterCsv(companyId, from, to);
 case "time_billing_utilization" -> exportTimeBillingUtilizationCsv(companyId, from, to);
 case "fixed_assets_register" -> exportFixedAssetsRegisterCsv(companyId);
 case "fx_operations_register" -> exportFxOperationsRegisterCsv(companyId, from, to);
 case "aged_balance_suppliers" -> exportSupplierAgedBalanceCsv(companyId);
 default -> throw new ValidationException("UNKNOWN_STATEMENT",
 "Type d'export inconnu : " + statement + ". Types supportés : " +
 "balance_sheet, income_statement, general_ledger, trial_balance, donor_report, " +
 "tax_declaration, purchase_register, expense_register, payroll_summary, " +
 "inventory_valuation, stock_movement_register, time_billing_utilization, " +
 "fixed_assets_register, fx_operations_register, aged_balance_suppliers");
 };
 }

 /**
 * Applique le gating par module activé pour un type d'export (Part C2).
 *
 * <p>Les statements non listés dans le switch sont communs / always-on — pas de gate.
 * La liste est volontairement exhaustive (un case par statement gated) pour qu'un
 * ajout futur de statement n'oublie pas de décider explicitement s'il doit être gated
 * ou non.
 */
 private void ensureModuleEnabledForStatement(UUID companyId, String statement) {
 if (statement == null) return;
 switch (statement.toLowerCase()) {
 case "donor_report" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
 case "tax_declaration" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TAX);
 case "purchase_register", "aged_balance_suppliers" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.PURCHASING);
 case "inventory_valuation", "stock_movement_register" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 case "time_billing_utilization" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.TIME_BILLING);
 case "fixed_assets_register" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FIXED_ASSETS);
 case "fx_operations_register" ->
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FX_OPERATIONS);
 // Modules always-on — pas de gate :
 // - expense_register (EXPENSES always-on)
 // - payroll_summary (PAYROLL always-on)
 // - balance_sheet, income_statement, general_ledger, trial_balance, dashboard,
 // aged_balance (FINANCIAL_STATEMENTS / ACCOUNTING_ENGINE / INVOICING — always-on)
 default -> { /* no-op */ }
 }
 }

 private ExportResult exportBalanceSheetPdf(UUID companyId, LocalDate asOf) {
 // Part C4 — resourceId policy pour la génération PDF.
 //
 // Pour les périodes OUVERTES (cas ici — on calcule le bilan à la volée sans snapshot),
 // on utilise un UUID aléatoire : les données sous-jacentes peuvent changer à tout
 // moment (nouvelles écritures, modifications de tiers, etc.), donc un cache indexé par
 // un ID déterministe serait incorrect — l'utilisateur pourrait récupérer un PDF périmé.
 // L'UUID aléatoire force donc :document-generation à régénérer le PDF à chaque appel.
 //
 // Pour les périodes CLOSED, la politique serait d'utiliser un ID déterministe dérivé
 // du snapshot figé (FinancialStatementSnapshot) — par exemple l'ID du snapshot lui-même.
 // Cela permettrait de cacher le PDF (immuable puisque la période est figée) et de
 // renvoyer le même contenu à tous les appelants. Non implémenté ici — à faire dans un
 // futur prompt (il faut d'abord exposer un moyen de résoudre le snapshot pour une
 // période donnée depuis :financial-statements).
 BalanceSheet bs = financialStatementsService.getBalanceSheet(companyId, asOf);
 Map<String, Object> variables = new HashMap<>();
 variables.put("asOf", asOf.toString());
 variables.put("totalAssets", bs.totalAssets().toString());
 variables.put("totalLiabilities", bs.totalLiabilities().toString());
 variables.put("totalEquity", bs.totalEquity().toString());
 variables.put("balanced", bs.balanced());

 UUID resourceId = UUID.randomUUID();
 documentGenerationService.generateDocument(
 companyId,
 jo.accountant.documentgeneration.entity.GeneratedDocumentType.BALANCE_SHEET,
 resourceId,
 variables);

 byte[] pdf = documentGenerationService.getDocumentContent(companyId, resourceId);
 return new ExportResult(companyId, "balance_sheet", "pdf", pdf,
 "application/pdf", "bilan-" + asOf + ".pdf");
 }

 private ExportResult exportIncomeStatementPdf(UUID companyId, LocalDate from, LocalDate to) {
 // Part C4 — resourceId policy : voir exportBalanceSheetPdf. Même politique pour le
 // compte de résultat — UUID aléatoire pour les périodes ouvertes (cas ici), ID
 // déterministe dérivé du snapshot figé pour les périodes closed (à implémenter).
 IncomeStatement is = financialStatementsService.getIncomeStatement(companyId, from, to);
 Map<String, Object> variables = new HashMap<>();
 variables.put("from", from.toString());
 variables.put("to", to.toString());
 variables.put("totalProducts", is.totalProducts().toString());
 variables.put("totalCharges", is.totalCharges().toString());
 variables.put("netResult", is.netResult().toString());

 UUID resourceId = UUID.randomUUID();
 documentGenerationService.generateDocument(
 companyId,
 jo.accountant.documentgeneration.entity.GeneratedDocumentType.INCOME_STATEMENT,
 resourceId,
 variables);

 byte[] pdf = documentGenerationService.getDocumentContent(companyId, resourceId);
 return new ExportResult(companyId, "income_statement", "pdf", pdf,
 "application/pdf", "compte-resultat-" + from + "-" + to + ".pdf");
 }

 private ExportResult exportGeneralLedger(UUID companyId, LocalDate from, LocalDate to,
 String format) {
 // Pour le grand livre, on a besoin d'un accountId — en Phase 17 simplifié,
 // on exporte tous les comptes. On prend le premier compte ACTIF trouvé.
 List<Account> accounts = accountRepository.findByCompanyIdOrderByCode(companyId);
 if (accounts.isEmpty()) {
 throw new ValidationException("NO_ACCOUNTS", "Aucun compte dans le plan comptable");
 }

 StringBuilder csv = new StringBuilder();
 csv.append("Date;Reference;Description;Compte;Debit;Credit;Solde cumule\n");

 for (Account account : accounts) {
 List<LedgerLine> lines = accountingEngineService.getLedger(
 companyId, account.getId(),
 from != null ? from : LocalDate.of(1900, 1, 1),
 to != null ? to : LocalDate.now());
 for (LedgerLine line : lines) {
 csv.append(String.format("%s;%s;%s;%s;%s;%s;%s\n",
 line.entryDate(), line.reference() != null ? line.reference() : "",
 line.description() != null ? line.description() : "",
 line.accountCode(),
 line.debit(), line.credit(), line.runningBalance()));
 }
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 return new ExportResult(companyId, "general_ledger", "csv", content,
 "text/csv", "grand-livre-" + from + "-" + to + ".csv");
 }

 private ExportResult exportTrialBalance(UUID companyId, String format) {
 List<TrialBalanceLine> lines = accountingEngineService.getTrialBalance(companyId);

 StringBuilder csv = new StringBuilder();
 csv.append("Code compte;Libelle;Total debit;Total credit;Solde\n");
 for (TrialBalanceLine line : lines) {
 csv.append(String.format("%s;%s;%s;%s;%s\n",
 line.accountCode(), line.accountLabel(),
 line.totalDebit(), line.totalCredit(), line.balance()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 return new ExportResult(companyId, "trial_balance", "csv", content,
 "text/csv", "balance-generale.csv");
 }

 private ExportResult exportDonorReportPdf(UUID companyId, UUID grantId) {
 // Part C4 — resourceId policy pour le rapport bailleur.
 //
 // Pour les subventions en cours (cas ici), on utilise un UUID aléatoire : la
 // consommation de la subvention (entrées de don, écritures de dépenses affectées)
 // évolue en continu — un cache déterministe renverrait un rapport périmé.
 //
 // Pour les subventions clôturées (FiscalYear CLOSED, voir FundsGrantsService.
 // closeFiscalYear), la politique serait d'utiliser un ID déterministe dérivé de la
 // subvention (par ex. l'ID du grant lui-même) : le rapport est immuable car les
 // écritures de la période clôturée ne peuvent plus être modifiées. Non implémenté —
 // à faire dans un futur prompt.
 if (grantId == null) {
 throw new ValidationException("GRANT_ID_REQUIRED",
 "resourceId (grantId) est requis pour donor_report");
 }
 DonorReport report = fundsGrantsService.getDonorReport(companyId, grantId);

 Map<String, Object> variables = new HashMap<>();
 variables.put("grantCode", report.grantCode());
 variables.put("grantLabel", report.grantLabel());
 variables.put("donorName", report.donorName());
 variables.put("totalReceived", report.totalReceived().toString());
 variables.put("totalSpent", report.totalSpent().toString());
 variables.put("balanceRemaining", report.balanceRemaining().toString());

 UUID resourceId = UUID.randomUUID();
 documentGenerationService.generateDocument(
 companyId,
 jo.accountant.documentgeneration.entity.GeneratedDocumentType.DONOR_REPORT,
 resourceId,
 variables);

 byte[] pdf = documentGenerationService.getDocumentContent(companyId, resourceId);
 return new ExportResult(companyId, "donor_report", "pdf", pdf,
 "application/pdf", "rapport-bailleur-" + report.grantCode() + ".pdf");
 }

 // --- Tableau de bord ---

 /**
 * Tableau de bord de synthèse (§13 Phase 17).
 *
 * <p>Position de trésorerie, balance âgée clients/fournisseurs, principales charges,
 * approbations en attente, factures échues.
 *
 * <p><b>Audit M4</b> : la version initiale de cette méthode filtrait les comptes par
 * <b>préfixe de code</b> SYSCOHADA ("5*" pour trésorerie, "411*" pour clients, "40*" pour
 * fournisseurs, "6*" pour charges, "7*" pour produits), ce qui la cassait entièrement pour
 * les autres référentiels (PCGR_CANADA : 5 = Avoir, 6 = Produits, 7 = Charges ; IFRS :
 * 5 = Charges ; etc.). La version corrigée filtre par {@link Account#getReportingClass()}
 * — agnostique du référentiel.
 */
 @Transactional(readOnly = true)
 public Dashboard getDashboard(UUID companyId) {
 // V8.3 — Protection défensive : si une étape échoue (ex. pas d'exercice fiscal,
 // NPE sur accountCode null, query SQL qui échoue), on retourne un dashboard
 // partiel à zéros plutôt qu'un HTTP 500 (cf. fff.txt — DashboardVM API error 500).
 BigDecimal cashPosition = BigDecimal.ZERO;
 BigDecimal totalReceivables = BigDecimal.ZERO;
 BigDecimal totalPayables = BigDecimal.ZERO;
 List<Dashboard.CategoryAmount> topExpenses = new ArrayList<>();
 List<Dashboard.CategoryAmount> topRevenues = new ArrayList<>();
 int overdueInvoices = 0;
 int pendingApprovals = 0;

 // ── 1. Trial balance + agrégation par catégorie ────────────────────
 try {
 List<TrialBalanceLine> trialBalance = accountingEngineService.getTrialBalance(companyId);

 // Charger tous les comptes de l'entreprise une seule fois pour éviter du N+1 et
 // récupérer la reportingClass de chaque compte (la TrialBalanceLine ne la porte pas).
 Map<UUID, Account> accountById = new HashMap<>();
 for (Account a : accountRepository.findByCompanyIdOrderByCode(companyId)) {
 accountById.put(a.getId(), a);
 }

 for (TrialBalanceLine line : trialBalance) {
 Account account = accountById.get(line.accountId());
 if (account == null) continue; // compte supprimé (rare — journalLine.accountId n'a pas de FK dure)
 jo.accountant.core.framework.ReportingClass rc = account.getReportingClass();
 BigDecimal balance = line.balance() != null ? line.balance() : BigDecimal.ZERO;
 // V8.3 — defense-in-depth : accountCode peut être "(code inconnu)" ou null
 // si la base a été corrompue — on garde une référence locale nullable.
 String code = line.accountCode();

 // Trésorerie = comptes d'ACTIF avec taxMappingCode = "CASH" (convention), ou à défaut
 // tous les comptes d'ACTIF dont le code commence par "5" (rétro-compat SYSCOHADA).
 // Audit E-C (correction) : un compte de trésorerie avec solde négatif (découvert
 // bancaire) est reclassé en totalPayables (dette) au lieu d'être déduit de cashPosition.
 // cashPosition ne doit jamais être négatif.
 boolean isCash = "CASH".equals(account.getTaxMappingCode())
 || (account.getTaxMappingCode() == null && code != null && code.startsWith("5")
 && rc == jo.accountant.core.framework.ReportingClass.ACTIF);
 if (isCash) {
 if (balance.compareTo(BigDecimal.ZERO) >= 0) {
 cashPosition = cashPosition.add(balance);
 } else {
 // Solde négatif = découvert bancaire → reclassé en dettes
 totalPayables = totalPayables.add(balance.negate());
 }
 }

 // Clients (créances) = comptes d'ACTIF marqués taxMappingCode = "ACCOUNTS_RECEIVABLE"
 // ou à défaut (rétro-compat SYSCOHADA) ceux dont le code commence par "411".
 // Audit E-C : un compte client avec solde négatif (avoir > facture) n'est pas une
 // créance mais une dette → reclassé en totalPayables.
 if ("ACCOUNTS_RECEIVABLE".equals(account.getTaxMappingCode())
 || (account.getTaxMappingCode() == null && code != null && code.startsWith("411")
 && rc == jo.accountant.core.framework.ReportingClass.ACTIF)) {
 if (balance.compareTo(BigDecimal.ZERO) >= 0) {
 totalReceivables = totalReceivables.add(balance);
 } else {
 totalPayables = totalPayables.add(balance.negate());
 }
 }

 // Fournisseurs (dettes) = comptes de PASSIF marqués taxMappingCode = "ACCOUNTS_PAYABLE"
 // ou à défaut (rétro-compat SYSCOHADA) ceux dont le code commence par "40".
 // Audit E-C : un compte fournisseur avec solde négatif (avance) est une créance →
 // reclassé en totalReceivables.
 if ("ACCOUNTS_PAYABLE".equals(account.getTaxMappingCode())
 || (account.getTaxMappingCode() == null && code != null && code.startsWith("40")
 && rc == jo.accountant.core.framework.ReportingClass.PASSIF)) {
 BigDecimal payableAmount = balance.negate(); // PASSIF: crédit - débit
 if (payableAmount.compareTo(BigDecimal.ZERO) >= 0) {
 totalPayables = totalPayables.add(payableAmount);
 } else {
 totalReceivables = totalReceivables.add(payableAmount.negate());
 }
 }

 // Charges = tous les comptes de ReportingClass.CHARGES (référentiel-agnostique)
 if (rc == jo.accountant.core.framework.ReportingClass.CHARGES) {
 topExpenses.add(new Dashboard.CategoryAmount(
 line.accountLabel(), line.totalDebit()));
 }
 // Produits = tous les comptes de ReportingClass.PRODUITS (référentiel-agnostique)
 if (rc == jo.accountant.core.framework.ReportingClass.PRODUITS) {
 topRevenues.add(new Dashboard.CategoryAmount(
 line.accountLabel(), line.totalCredit()));
 }
 }

 // Top 5 charges par montant
 topExpenses.sort((a, b) -> b.amount().compareTo(a.amount()));
 if (topExpenses.size() > 5) topExpenses = topExpenses.subList(0, 5);

 // Top 5 produits par montant
 topRevenues.sort((a, b) -> b.amount().compareTo(a.amount()));
 if (topRevenues.size() > 5) topRevenues = topRevenues.subList(0, 5);
 } catch (jo.accountant.core.exception.NotFoundException e) {
 // Pas d'exercice fiscal → trial balance vide. On continue avec les valeurs à zéro.
 LOG.warn("[Dashboard] trial balance ignorée pour companyId={} : {}", companyId, e.getMessage());
 } catch (Exception e) {
 // Toute autre erreur (SQL, NPE, etc.) — on log et on continue avec zéros.
 LOG.warn("[Dashboard] erreur non fatale lors du calcul trial balance pour companyId={}", companyId, e);
 }

 // ── 2. Factures échues ─────────────────────────────────────────────
 try {
 overdueInvoices = (int) invoiceRepository
 .findByCompanyIdAndStatus(companyId, InvoiceStatus.ISSUED).stream()
 .filter(inv -> inv.getDueDate() != null && inv.getDueDate().isBefore(LocalDate.now()))
 .count();
 } catch (Exception e) {
 LOG.warn("[Dashboard] erreur non fatale lors du comptage factures échues pour companyId={}", companyId, e);
 }

 // ── 3. Approbations en attente ─────────────────────────────────────
 try {
 // Audit M6 (corrigé + Part C3) : pendingApprovals calculé depuis ApprovalRequestRepository
 // via une requête de COUNT (au lieu de matérialiser toute la liste en Java puis .size()).
 // Avant cette correction, le KPI était hardcodé à 0 — le dashboard mentait.
 pendingApprovals = (int) approvalRequestRepository
 .countByCompanyIdAndStatus(companyId, ApprovalStatus.PENDING);
 } catch (Exception e) {
 LOG.warn("[Dashboard] erreur non fatale lors du comptage approbations pour companyId={}", companyId, e);
 }

 // ── 4. Analytics ─────────────────────────────
 // Chaque bloc est indépendant et best-effort : une erreur non fatale
 // (ex. exercice non ouvert, module inventory désactivé, NPE) laisse
 // le champ analytics à null — le mobile affiche alors "Données
 // indisponibles" pour cette section sans bloquer le reste du
 // dashboard.
 List<jo.accountant.reporting.dto.AnalyticsRatio> ratios = null;
 List<jo.accountant.reporting.dto.AnalyticsTopEntity> topClients = null;
 List<jo.accountant.reporting.dto.AnalyticsTopEntity> topSuppliers = null;
 List<jo.accountant.reporting.dto.AnalyticsAlert> alerts = null;
 jo.accountant.reporting.dto.AnalyticsPeriodComparison periodComparison = null;
 try {
 ratios = computeRatios(companyId);
 } catch (Exception e) {
 LOG.warn("[Dashboard] ratios indisponibles pour companyId={}: {}", companyId, e.getMessage());
 }
 try {
 topClients = computeTopClients(companyId);
 } catch (Exception e) {
 LOG.warn("[Dashboard] topClients indisponibles pour companyId={}: {}", companyId, e.getMessage());
 }
 try {
 topSuppliers = computeTopSuppliers(companyId);
 } catch (Exception e) {
 LOG.warn("[Dashboard] topSuppliers indisponibles pour companyId={}: {}", companyId, e.getMessage());
 }
 try {
 alerts = computeAlerts(companyId);
 } catch (Exception e) {
 LOG.warn("[Dashboard] alerts indisponibles pour companyId={}: {}", companyId, e.getMessage());
 }
 try {
 periodComparison = computePeriodComparison(companyId);
 } catch (Exception e) {
 LOG.warn("[Dashboard] periodComparison indisponible pour companyId={}: {}", companyId, e.getMessage());
 }

 return new Dashboard(companyId, cashPosition, totalReceivables, totalPayables,
 topExpenses, topRevenues, pendingApprovals, overdueInvoices,
 ratios, topClients, topSuppliers, alerts, periodComparison);
 }

 // ======================================================================
 // Analytics Dashboard
 // ======================================================================

 /**
 * Calcule les 3 ratios financiers principaux à partir du bilan et du
 * compte de résultat de l'exercice actif.
 *
 * <ul>
 * <li><b>Liquidité générale</b> = actif courant / passif courant (≥ 1,5 = bon) ;</li>
 * <li><b>Solvabilité</b> = total actif / total passif (≥ 1,2 = solide) ;</li>
 * <li><b>Rentabilité nette</b> = résultat net / total produits × 100 (≥ 5% = sain).</li>
 * </ul>
 *
 * <p>Les seuils d'interprétation sont conventionnels (pratique PME). Les
 * valeurs sont arrondies à 2 décimales. Si le bilan ou le CR ne peuvent
 * pas être calculés (ex. pas d'exercice), la méthode retourne une liste
 * vide.
 */
 private List<jo.accountant.reporting.dto.AnalyticsRatio> computeRatios(UUID companyId) {
 List<jo.accountant.reporting.dto.AnalyticsRatio> ratios = new ArrayList<>();
 LocalDate today = LocalDate.now();

 // ── Bilan : liquidité + solvabilité ──
 try {
 jo.accountant.financialstatements.dto.BalanceSheet bs =
 financialStatementsService.getBalanceSheet(companyId, today);

 // Actif courant = somme des sections "assets" dont reportingSubcategory
 // contient "COURANT". Idem pour passif courant côté "liabilities".
 BigDecimal currentAssets = sumSectionSubtotal(bs.assets(), "COURANT");
 BigDecimal currentLiabilities = sumSectionSubtotal(bs.liabilities(), "COURANT");
 BigDecimal totalAssets = bs.totalAssets() != null ? bs.totalAssets() : BigDecimal.ZERO;
 BigDecimal totalLiabilities = bs.totalLiabilities() != null ? bs.totalLiabilities() : BigDecimal.ZERO;

 // Liquidité générale
 double liquidity = currentLiabilities.compareTo(BigDecimal.ZERO) > 0
 ? currentAssets.divide(currentLiabilities, 2, java.math.RoundingMode.HALF_UP).doubleValue()
 : 0d;
 String liquidityInterp;
 if (liquidity >= 1.5d) liquidityInterp = "Bonne liquidité (≥ 1,5)";
 else if (liquidity >= 1.0d) liquidityInterp = "Liquidité acceptable (1,0 – 1,5)";
 else if (liquidity > 0d) liquidityInterp = "Liquidité fragile (< 1,0)";
 else liquidityInterp = "Données indisponibles";
 ratios.add(new jo.accountant.reporting.dto.AnalyticsRatio(
 "Liquidité générale", liquidity,
 "Actif courant ÷ Passif courant", liquidityInterp));

 // Solvabilité
 double solvency = totalLiabilities.compareTo(BigDecimal.ZERO) > 0
 ? totalAssets.divide(totalLiabilities, 2, java.math.RoundingMode.HALF_UP).doubleValue()
 : 0d;
 String solvencyInterp;
 if (solvency >= 1.2d) solvencyInterp = "Solvabilité solide (≥ 1,2)";
 else if (solvency >= 1.0d) solvencyInterp = "Solvabilité limite (1,0 – 1,2)";
 else if (solvency > 0d) solvencyInterp = "Solvabilité insuffisante (< 1,0)";
 else solvencyInterp = "Données indisponibles";
 ratios.add(new jo.accountant.reporting.dto.AnalyticsRatio(
 "Solvabilité", solvency,
 "Total actif ÷ Total passif", solvencyInterp));
 } catch (Exception e) {
 LOG.debug("[Analytics] bilan indisponible pour companyId={}: {}", companyId, e.getMessage());
 }

 // ── Compte de résultat : rentabilité nette ──
 try {
 LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
 jo.accountant.financialstatements.dto.IncomeStatement is =
 financialStatementsService.getIncomeStatement(companyId, yearStart, today);

 BigDecimal revenue = is.totalProducts() != null ? is.totalProducts() : BigDecimal.ZERO;
 BigDecimal net = is.netResult() != null ? is.netResult() : BigDecimal.ZERO;
 double profitability = revenue.compareTo(BigDecimal.ZERO) > 0
 ? net.divide(revenue, 4, java.math.RoundingMode.HALF_UP)
 .multiply(BigDecimal.valueOf(100))
 .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue()
 : 0d;
 String profitInterp;
 if (profitability >= 5d) profitInterp = "Rentabilité saine (≥ 5%)";
 else if (profitability >= 0d) profitInterp = "Rentabilité faible (0 – 5%)";
 else profitInterp = "Rentabilité négative (< 0%)";
 ratios.add(new jo.accountant.reporting.dto.AnalyticsRatio(
 "Rentabilité nette", profitability,
 "Résultat net ÷ Total produits × 100", profitInterp));
 } catch (Exception e) {
 LOG.debug("[Analytics] compte de résultat indisponible pour companyId={}: {}", companyId, e.getMessage());
 }

 return ratios;
 }

 /** Somme des subtotals des sections dont reportingSubcategory contient token. */
 private BigDecimal sumSectionSubtotal(List<jo.accountant.financialstatements.dto.BalanceSheet.Section> sections,
 String token) {
 BigDecimal sum = BigDecimal.ZERO;
 if (sections == null) return sum;
 for (jo.accountant.financialstatements.dto.BalanceSheet.Section s : sections) {
 if (s.reportingSubcategory() != null
 && s.reportingSubcategory().toUpperCase().contains(token)
 && s.subtotal() != null) {
 sum = sum.add(s.subtotal());
 }
 }
 return sum;
 }

 /**
 * Top 5 clients par volume de facturation (total TTC des factures de
 * ventes ISSUED/PARTIALLY_PAID/PAID, agrégé par thirdPartyId).
 */
 private List<jo.accountant.reporting.dto.AnalyticsTopEntity> computeTopClients(UUID companyId) {
 // Map thirdPartyId -> sum(totalAmount)
 Map<UUID, BigDecimal> amountByClient = new HashMap<>();
 for (InvoiceStatus st : new InvoiceStatus[]{
 InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID}) {
 for (SalesInvoice inv : invoiceRepository.findByCompanyIdAndStatus(companyId, st)) {
 if (inv.getThirdPartyId() == null) continue;
 BigDecimal total = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
 amountByClient.merge(inv.getThirdPartyId(), total, BigDecimal::add);
 }
 }
 return topEntitiesFrom(companyId, amountByClient);
 }

 /**
 * Top 5 fournisseurs par volume d'achat (total TTC des factures d'achat
 * RECEIVED/PARTIALLY_PAID/PAID, agrégé par thirdPartyId).
 */
 private List<jo.accountant.reporting.dto.AnalyticsTopEntity> computeTopSuppliers(UUID companyId) {
 Map<UUID, BigDecimal> amountBySupplier = new HashMap<>();
 for (PurchaseInvoiceStatus st : new PurchaseInvoiceStatus[]{
 PurchaseInvoiceStatus.RECEIVED, PurchaseInvoiceStatus.PARTIALLY_PAID, PurchaseInvoiceStatus.PAID}) {
 for (PurchaseInvoice inv : purchaseInvoiceRepository.findByCompanyIdAndStatus(companyId, st)) {
 if (inv.getThirdPartyId() == null) continue;
 BigDecimal total = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
 amountBySupplier.merge(inv.getThirdPartyId(), total, BigDecimal::add);
 }
 }
 return topEntitiesFrom(companyId, amountBySupplier);
 }

 /** Construit la liste triée top-5 des tiers à partir d'une map amountByThirdParty. */
 private List<jo.accountant.reporting.dto.AnalyticsTopEntity> topEntitiesFrom(
 UUID companyId, Map<UUID, BigDecimal> amountByThirdParty) {
 // Résoudre les noms des tiers en une seule passe.
 Map<UUID, String> nameById = new HashMap<>();
 for (ThirdParty tp : thirdPartyRepository.findByCompanyIdOrderByName(companyId)) {
 nameById.put(tp.getId(), tp.getName());
 }

 List<Map.Entry<UUID, BigDecimal>> sorted = new ArrayList<>(amountByThirdParty.entrySet());
 sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
 if (sorted.size() > 5) sorted = sorted.subList(0, 5);

 List<jo.accountant.reporting.dto.AnalyticsTopEntity> result = new ArrayList<>();
 int rank = 1;
 for (Map.Entry<UUID, BigDecimal> e : sorted) {
 String name = nameById.getOrDefault(e.getKey(), "Tiers supprimé");
 result.add(new jo.accountant.reporting.dto.AnalyticsTopEntity(
 e.getKey(), name, e.getValue(), rank++));
 }
 return result;
 }

 /**
 * Calcule les alertes métier :
 * <ul>
 * <li><b>OVERDUE_INVOICE</b> — factures de ventes échues depuis plus
 * de 90 jours et non réglées (status ISSUED ou PARTIALLY_PAID).
 * Severity = HIGH.</li>
 * <li><b>LOW_STOCK</b> — articles dont la quantité en stock est passée
 * sous le seuil de réapprovisionnement (uniquement si le module
 * INVENTORY est activé). Severity = MEDIUM.</li>
 * </ul>
 */
 private List<jo.accountant.reporting.dto.AnalyticsAlert> computeAlerts(UUID companyId) {
 List<jo.accountant.reporting.dto.AnalyticsAlert> alerts = new ArrayList<>();
 LocalDate today = LocalDate.now();
 LocalDate ninetyDaysAgo = today.minusDays(90);

 // ── Factures en retard > 90 jours ──
 int overdueCount = 0;
 BigDecimal overdueAmount = BigDecimal.ZERO;
 for (InvoiceStatus st : new InvoiceStatus[]{InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID}) {
 for (SalesInvoice inv : invoiceRepository.findByCompanyIdAndStatus(companyId, st)) {
 if (inv.getDueDate() == null) continue;
 if (inv.getDueDate().isBefore(ninetyDaysAgo)) {
 overdueCount++;
 BigDecimal bal = inv.getBalanceDue();
 if (bal != null && bal.compareTo(BigDecimal.ZERO) > 0) {
 overdueAmount = overdueAmount.add(bal);
 }
 }
 }
 }
 if (overdueCount > 0) {
 alerts.add(new jo.accountant.reporting.dto.AnalyticsAlert(
 "OVERDUE_INVOICE",
 String.format("%d facture(s) échue(s) depuis plus de 90 jours — solde dû %s",
 overdueCount, overdueAmount.toPlainString()),
 "HIGH",
 "Voir les factures"));
 }

 // ── Stock bas (si module INVENTORY activé) ──
 try {
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.INVENTORY);
 int lowStockCount = 0;
 for (Item item : itemRepository.findByCompanyIdOrderBySku(companyId)) {
 if (item.getReorderThreshold() == null) continue;
 try {
 jo.accountant.inventory.dto.ItemValuation val =
 inventoryService.getValuation(companyId, item.getId());
 if (val.totalQuantity() != null
 && val.totalQuantity().compareTo(item.getReorderThreshold()) < 0) {
 lowStockCount++;
 }
 } catch (Exception ignored) {
 // Article sans couche de stock — on l'ignore.
 }
 }
 if (lowStockCount > 0) {
 alerts.add(new jo.accountant.reporting.dto.AnalyticsAlert(
 "LOW_STOCK",
 String.format("%d article(s) sous le seuil de réapprovisionnement", lowStockCount),
 "MEDIUM",
 "Voir le stock"));
 }
 } catch (Exception e) {
 // Module INVENTORY non activé — pas d'alerte stock. C'est attendu.
 LOG.debug("[Analytics] module INVENTORY non activé pour companyId={}", companyId);
 }

 return alerts;
 }

 /**
 * Calcule la comparaison de période : somme TTC des factures de ventes
 * (ISSUED, PARTIALLY_PAID, PAID) pour M, M-1, Y et Y-1.
 */
 private jo.accountant.reporting.dto.AnalyticsPeriodComparison computePeriodComparison(UUID companyId) {
 LocalDate today = LocalDate.now();
 LocalDate monthStart = today.withDayOfMonth(1);
 LocalDate prevMonthStart = monthStart.minusMonths(1);
 LocalDate prevMonthEnd = monthStart.minusDays(1);
 LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
 LocalDate prevYearStart = LocalDate.of(today.getYear() - 1, 1, 1);
 LocalDate prevYearEnd = LocalDate.of(today.getYear() - 1, 12, 31);

 List<String> statuses = List.of(
 InvoiceStatus.ISSUED.name(),
 InvoiceStatus.PARTIALLY_PAID.name(),
 InvoiceStatus.PAID.name());

 BigDecimal currentMonth = sumSalesInvoiced(companyId, monthStart, today, statuses);
 BigDecimal previousMonth = sumSalesInvoiced(companyId, prevMonthStart, prevMonthEnd, statuses);
 BigDecimal currentYear = sumSalesInvoiced(companyId, yearStart, today, statuses);
 BigDecimal previousYear = sumSalesInvoiced(companyId, prevYearStart, prevYearEnd, statuses);

 return new jo.accountant.reporting.dto.AnalyticsPeriodComparison(
 currentMonth, previousMonth, currentYear, previousYear);
 }

 /** Wrapper défensif autour du repository pour ne pas casser le dashboard si la query échoue. */
 private BigDecimal sumSalesInvoiced(UUID companyId, LocalDate from, LocalDate to, List<String> statuses) {
 try {
 return invoiceRepository
 .sumTotalAmountByCompanyIdAndIssueDateBetweenAndStatusIn(companyId, from, to, statuses)
 .orElse(BigDecimal.ZERO);
 } catch (Exception e) {
 LOG.debug("[Analytics] sumSalesInvoiced failed companyId={} from={} to={}: {}",
 companyId, from, to, e.getMessage());
 return BigDecimal.ZERO;
 }
 }

 /**
 * Calcule la balance âgée des factures clients (audit M5).
 *
 * <p>Ventile le solde dû des factures ISSUED et PARTIALLY_PAID par tranche d'âge depuis
 * la date d'échéance :
 * <ul>
 * <li>{@code current} — pas encore échue (dueDate ≥ today)</li>
 * <li>{@code d0_30} — échue depuis 0 à 30 jours</li>
 * <li>{@code d31_60} — échue depuis 31 à 60 jours</li>
 * <li>{@code d61_90} — échue depuis 61 à 90 jours</li>
 * <li>{@code d90_plus} — échue depuis plus de 90 jours</li>
 * </ul>
 *
 * <p>Les factures VOID, DRAFT et PAID sont exclues (solde dû nul ou non pertinent).
 */
 @Transactional(readOnly = true)
 public AgedBalance getAgedBalance(UUID companyId) {
 LocalDate today = LocalDate.now();
 BigDecimal current = BigDecimal.ZERO;
 BigDecimal d0_30 = BigDecimal.ZERO;
 BigDecimal d31_60 = BigDecimal.ZERO;
 BigDecimal d61_90 = BigDecimal.ZERO;
 BigDecimal d90_plus = BigDecimal.ZERO;
 int invoiceCount = 0;

 // Inclure ISSUED et PARTIALLY_PAID (les deux statuts où il reste un solde dû).
 for (InvoiceStatus status : new InvoiceStatus[]{InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID}) {
 for (SalesInvoice inv : invoiceRepository.findByCompanyIdAndStatus(companyId, status)) {
 if (inv.getDueDate() == null) continue;
 BigDecimal balanceDue = inv.getBalanceDue();
 if (balanceDue == null || balanceDue.compareTo(BigDecimal.ZERO) <= 0) continue;
 invoiceCount++;

 long daysOverdue = ChronoUnit.DAYS.between(inv.getDueDate(), today);
 if (daysOverdue <= 0) {
 current = current.add(balanceDue);
 } else if (daysOverdue <= 30) {
 d0_30 = d0_30.add(balanceDue);
 } else if (daysOverdue <= 60) {
 d31_60 = d31_60.add(balanceDue);
 } else if (daysOverdue <= 90) {
 d61_90 = d61_90.add(balanceDue);
 } else {
 d90_plus = d90_plus.add(balanceDue);
 }
 }
 }

 BigDecimal total = current.add(d0_30).add(d31_60).add(d61_90).add(d90_plus);
 return new AgedBalance(companyId, current, d0_30, d31_60, d61_90, d90_plus, total, invoiceCount);
 }

 /**
 * Calcule la balance âgée des factures fournisseurs (Part D1).
 *
 * <p>Symétrique de {@link #getAgedBalance(UUID)} pour le côté fournisseur : ventile le
 * solde dû des {@link PurchaseInvoice} RECEIVED et PARTIALLY_PAID par tranche d'âge depuis
 * la date d'échéance ({@code dueDate}). Les factures DRAFT (non encore reçues — pas
 * d'écriture comptable) et VOID/PAID (solde dû nul) sont exclues.
 *
 * <p>Utilise directement {@link PurchaseInvoiceRepository} plutôt que les JournalLine
 * SUPPLIER — l'information d'échéance est portée par la facture elle-même (dueDate),
 * pas par l'écriture comptable (qui ne connaît que la date de l'écriture).
 */
 @Transactional(readOnly = true)
 public AgedBalance getSupplierAgedBalance(UUID companyId) {
 LocalDate today = LocalDate.now();
 BigDecimal current = BigDecimal.ZERO;
 BigDecimal d0_30 = BigDecimal.ZERO;
 BigDecimal d31_60 = BigDecimal.ZERO;
 BigDecimal d61_90 = BigDecimal.ZERO;
 BigDecimal d90_plus = BigDecimal.ZERO;
 int invoiceCount = 0;

 // RECEIVED et PARTIALLY_PAID = statuts où la facture a une écriture comptable
 // (crédit fournisseur) et un solde restant à payer.
 for (PurchaseInvoiceStatus status : new PurchaseInvoiceStatus[]{
 PurchaseInvoiceStatus.RECEIVED, PurchaseInvoiceStatus.PARTIALLY_PAID}) {
 for (PurchaseInvoice inv : purchaseInvoiceRepository.findByCompanyIdAndStatus(companyId, status)) {
 if (inv.getDueDate() == null) continue;
 BigDecimal balanceDue = inv.getBalanceDue();
 if (balanceDue == null || balanceDue.compareTo(BigDecimal.ZERO) <= 0) continue;
 invoiceCount++;

 long daysOverdue = ChronoUnit.DAYS.between(inv.getDueDate(), today);
 if (daysOverdue <= 0) {
 current = current.add(balanceDue);
 } else if (daysOverdue <= 30) {
 d0_30 = d0_30.add(balanceDue);
 } else if (daysOverdue <= 60) {
 d31_60 = d31_60.add(balanceDue);
 } else if (daysOverdue <= 90) {
 d61_90 = d61_90.add(balanceDue);
 } else {
 d90_plus = d90_plus.add(balanceDue);
 }
 }
 }

 BigDecimal total = current.add(d0_30).add(d31_60).add(d61_90).add(d90_plus);
 return new AgedBalance(companyId, current, d0_30, d31_60, d61_90, d90_plus, total, invoiceCount);
 }

 // ============================================================
 // Part D — Nouveaux exports CSV communs
 // ============================================================

 /**
 * D2 — Export CSV de la déclaration fiscale par période.
 *
 * <p>Appelle {@link TaxService#getDeclaration(UUID, LocalDate, LocalDate)} (même logique
 * que l'endpoint JSON {@code GET /tax/declarations}) et formate le résultat en CSV avec
 * les colonnes : taux, base imposable, TVA collectée, TVA déductible, TVA nette.
 *
 * <p>Note : la {@link TaxDeclaration} actuelle n'expose que la TVA collectée (par taux).
 * La TVA déductible (sur les achats) n'est pas encore agrégée par le service — la colonne
 * est donc laissée à 0 et la TVA nette = TVA collectée. À enrichir quand le service
 * fiscal exposera la TVA déductible par taux (futur prompt).
 *
 * <p>Si {@code from}/{@code to} sont null, défaut = année courante (1er janvier → aujourd'hui).
 */
 private ExportResult exportTaxDeclarationCsv(UUID companyId, LocalDate from, LocalDate to) {
 LocalDate start = from != null ? from : LocalDate.of(LocalDate.now().getYear(), 1, 1);
 LocalDate end = to != null ? to : LocalDate.now();

 TaxDeclaration declaration = taxService.getDeclaration(companyId, start, end);

 StringBuilder csv = new StringBuilder();
 csv.append("Taux (%);Base imposable;TVA collectee;TVA deductible;TVA nette\n");
 // Audit v4.7 §4.1 — afficher collecté et déductible séparément, puis net
 for (TaxDeclaration.TaxLine line : declaration.collectedLines()) {
 // Chercher la ligne déductible au même taux
 BigDecimal tvaDeductible = declaration.deductibleLines().stream()
 .filter(dl -> dl.rate().compareTo(line.rate()) == 0)
 .map(TaxDeclaration.TaxLine::taxAmount)
 .findFirst().orElse(BigDecimal.ZERO);
 BigDecimal tvaNette = line.taxAmount().subtract(tvaDeductible);
 csv.append(String.format("%s;%s;%s;%s;%s\n",
 line.rate(), line.taxableBase(), line.taxAmount(),
 tvaDeductible, tvaNette));
 }
 // Ajouter les taux déductibles sans collecté associé
 for (TaxDeclaration.TaxLine line : declaration.deductibleLines()) {
 boolean alreadyShown = declaration.collectedLines().stream()
 .anyMatch(cl -> cl.rate().compareTo(line.rate()) == 0);
 if (alreadyShown) continue;
 csv.append(String.format("%s;%s;%s;%s;%s\n",
 line.rate(), BigDecimal.ZERO, BigDecimal.ZERO,
 line.taxAmount(), line.taxAmount().negate()));
 }
 // Ligne de total
 BigDecimal totalNette = declaration.totalTaxCollected().subtract(declaration.totalTaxDeductible());
 csv.append(String.format("TOTAL;%s;%s;%s;%s\n",
 declaration.collectedLines().stream().map(TaxDeclaration.TaxLine::taxableBase)
 .reduce(BigDecimal.ZERO, BigDecimal::add)
 .add(declaration.deductibleLines().stream().map(TaxDeclaration.TaxLine::taxableBase)
 .reduce(BigDecimal.ZERO, BigDecimal::add)),
 declaration.totalTaxCollected(), declaration.totalTaxDeductible(), totalNette));
 // Ligne TVA due / crédit à reporter
 csv.append(String.format("\nTVA due;%s\n", declaration.taxDue()));
 csv.append(String.format("Crédit à reporter;%s\n", declaration.taxCreditToCarryForward()));

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 return new ExportResult(companyId, "tax_declaration", "csv", content,
 "text/csv", "declaration-fiscale-" + start + "-" + end + ".csv");
 }

 /**
 * D3 — Export CSV du registre des achats (factures fournisseurs).
 *
 * <p>Liste les factures d'achat de la période avec : n° facture, fournisseur, date,
 * HT, TVA, TTC, statut. Si {@code from}/{@code to} sont null, défaut = toutes les
 * factures triées par date d'émission décroissante.
 */
 private ExportResult exportPurchaseRegisterCsv(UUID companyId, LocalDate from, LocalDate to) {
 List<PurchaseInvoice> invoices;
 if (from != null && to != null) {
 invoices = purchaseInvoiceRepository
 .findByCompanyIdAndIssueDateBetweenOrderByIssueDateDesc(companyId, from, to);
 } else {
 invoices = purchaseInvoiceRepository.findByCompanyIdOrderByIssueDateDesc(companyId);
 }

 // Indexer les tiers fournisseurs une seule fois (évite N+1 sur le nom du fournisseur).
 Map<UUID, String> supplierNameById = new HashMap<>();
 for (ThirdParty tp : thirdPartyRepository.findByCompanyIdAndTypeOrderByName(
 companyId, ThirdPartyType.SUPPLIER)) {
 supplierNameById.put(tp.getId(), tp.getName());
 }

 StringBuilder csv = new StringBuilder();
 csv.append("N° facture;Fournisseur;Date;HT;TVA;TTC;Statut\n");
 for (PurchaseInvoice inv : invoices) {
 String supplierName = supplierNameById.getOrDefault(inv.getThirdPartyId(), "");
 String number = inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : "";
 String date = inv.getIssueDate() != null ? inv.getIssueDate().toString() : "";
 csv.append(String.format("%s;%s;%s;%s;%s;%s;%s\n",
 number, supplierName, date,
 inv.getSubtotal(), inv.getTaxAmount(), inv.getTotalAmount(),
 inv.getStatus()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 String period = (from != null ? from : "debut") + "-" + (to != null ? to : "fin");
 return new ExportResult(companyId, "purchase_register", "csv", content,
 "text/csv", "registre-achats-" + period + ".csv");
 }

 /**
 * D4 — Export CSV du registre des notes de frais.
 *
 * <p>Liste les notes de frais de la période avec : n° rapport, employé (ou
 * "exploitation générale" si non rattaché à un tiers), date, montant, catégorie, statut.
 * La catégorie est celle de la première ligne de la note (chaque ligne peut avoir sa
 * propre catégorie TRAVEL/MEALS/SUPPLIES/OTHER — on prend la première pour le résumé).
 *
 * <p>Si {@code from}/{@code to} sont null, défaut = toutes les notes triées par date
 * décroissante.
 */
 private ExportResult exportExpenseRegisterCsv(UUID companyId, LocalDate from, LocalDate to) {
 List<ExpenseReport> reports;
 if (from != null && to != null) {
 reports = expenseReportRepository
 .findByCompanyIdAndExpenseDateBetweenOrderByExpenseDateDesc(companyId, from, to);
 } else {
 reports = expenseReportRepository.findByCompanyIdOrderByExpenseDateDesc(companyId);
 }

 // Indexer les tiers employés (ThirdParty de type EMPLOYEE) une seule fois.
 Map<UUID, String> employeeNameById = new HashMap<>();
 for (ThirdParty tp : thirdPartyRepository.findByCompanyIdAndTypeOrderByName(
 companyId, ThirdPartyType.EMPLOYEE)) {
 employeeNameById.put(tp.getId(), tp.getName());
 }

 StringBuilder csv = new StringBuilder();
 csv.append("N° rapport;Employe;Date;Montant;Categorie;Statut\n");
 for (ExpenseReport report : reports) {
 // Employé — "exploitation générale" si la note n'est pas rattachée à un tiers.
 String employee = report.getThirdPartyId() != null
 ? employeeNameById.getOrDefault(report.getThirdPartyId(), "")
 : "exploitation generale";
 String date = report.getExpenseDate() != null ? report.getExpenseDate().toString() : "";
 // Catégorie = première ligne de la note (si elle a des lignes), sinon vide.
 String category = expenseLineRepository.findByReportIdOrderByCreatedAt(report.getId())
 .stream().findFirst().map(ExpenseLine::getCategory).orElse("");
 csv.append(String.format("%s;%s;%s;%s;%s;%s\n",
 report.getId(), employee, date, report.getTotalAmount(),
 category != null ? category : "", report.getStatus()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 String period = (from != null ? from : "debut") + "-" + (to != null ? to : "fin");
 return new ExportResult(companyId, "expense_register", "csv", content,
 "text/csv", "registre-notes-frais-" + period + ".csv");
 }

 /**
 * D5 — Export CSV du résumé de paie par campagne.
 *
 * <p>Liste les campagnes de paie avec : période (MM/AAAA), masse brute, masse nette,
 * charges patronales, effectif payé (nombre de bulletins). Si {@code from}/{@code to}
 * sont fournis, filtre par période (periodYear/periodMonth compris dans [from, to]).
 */
 private ExportResult exportPayrollSummaryCsv(UUID companyId, LocalDate from, LocalDate to) {
 List<PayrollRun> runs = payrollRunRepository
 .findByCompanyIdOrderByPeriodYearDescPeriodMonthDesc(companyId);

 StringBuilder csv = new StringBuilder();
 csv.append("Periode;Masse brute;Masse nette;Charges patronales;Effectif paye\n");
 for (PayrollRun run : runs) {
 // Filtre par période si from/to fournis (compare le premier jour du mois de la campagne).
 LocalDate periodStart = LocalDate.of(run.getPeriodYear(), run.getPeriodMonth(), 1);
 if (from != null && periodStart.isBefore(from.withDayOfMonth(1))) continue;
 if (to != null && periodStart.isAfter(to.withDayOfMonth(1))) continue;

 int payslipCount = payslipRepository.findByRunIdOrderByCreatedAt(run.getId()).size();
 String period = String.format("%02d/%04d", run.getPeriodMonth(), run.getPeriodYear());
 csv.append(String.format("%s;%s;%s;%s;%d\n",
 period, run.getTotalGross(), run.getTotalNet(),
 run.getTotalEmployerContributions(), payslipCount));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 String period = (from != null ? from : "debut") + "-" + (to != null ? to : "fin");
 return new ExportResult(companyId, "payroll_summary", "csv", content,
 "text/csv", "resume-paie-" + period + ".csv");
 }

 // ============================================================
 // Part E4 — Nouveaux exports CSV sectoriels
 // ============================================================

 /**
 * E4 — Export CSV de la valorisation agrégée de l'inventaire.
 *
 * <p>Une ligne par couple (article, entrepôt) ayant du stock restant. Délègue à
 * {@link InventoryService#getAggregatedValuation(UUID)} (même logique que l'endpoint JSON
 * {@code GET /inventory/valuation} — Part E1) et formate en CSV :
 * sku, libellé, entrepôt, quantité, coût unitaire, valeur totale.
 */
 private ExportResult exportInventoryValuationCsv(UUID companyId) {
 List<InventoryValuationResponse> rows = inventoryService.getAggregatedValuation(companyId);

 StringBuilder csv = new StringBuilder();
 csv.append("SKU;Libelle;Entrepot;Quantite;Cout unitaire;Valeur totale\n");
 for (InventoryValuationResponse r : rows) {
 csv.append(String.format("%s;%s;%s;%s;%s;%s\n",
 r.sku(), r.label(),
 r.warehouse() != null ? r.warehouse() : "",
 r.quantity(), r.unitCost(), r.totalValue()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 return new ExportResult(companyId, "inventory_valuation", "csv", content,
 "text/csv", "valorisation-stock-" + LocalDate.now() + ".csv");
 }

 /**
 * E4 — Export CSV du registre des mouvements de stock.
 *
 * <p>Liste tous les mouvements de stock (IN/OUT/TRANSFER) de la période. Délègue la
 * récupération à {@link InventoryService#listStockMoves(UUID, LocalDate, LocalDate)}
 * (même logique que l'endpoint JSON {@code GET /inventory/stock-moves} — Part E2) et
 * formate en CSV : date, article (SKU), entrepôt, direction, quantité, coût unitaire,
 * coût total, document source.
 */
 private ExportResult exportStockMovementRegisterCsv(UUID companyId, LocalDate from, LocalDate to) {
 List<StockMoveResponse> moves = inventoryService.listStockMoves(companyId, from, to);

 // Indexer articles + entrepôts une seule fois pour les libellés.
 Map<UUID, String> skuByItemId = new HashMap<>();
 for (Item item : itemRepository.findByCompanyIdOrderBySku(companyId)) {
 skuByItemId.put(item.getId(), item.getSku());
 }
 Map<UUID, String> warehouseLabelById = new HashMap<>();
 for (var wh : warehouseRepository.findByCompanyIdOrderByLabel(companyId)) {
 warehouseLabelById.put(wh.getId(), wh.getLabel());
 }

 StringBuilder csv = new StringBuilder();
 csv.append("Date;Article (SKU);Entrepot;Direction;Quantite;Cout unitaire;Cout total;Document source\n");
 for (StockMoveResponse m : moves) {
 csv.append(String.format("%s;%s;%s;%s;%s;%s;%s;%s\n",
 m.moveDate(),
 skuByItemId.getOrDefault(m.itemId(), ""),
 warehouseLabelById.getOrDefault(m.warehouseId(), ""),
 m.direction(), m.quantity(), m.unitCost(), m.totalCost(),
 m.sourceDocument() != null ? m.sourceDocument() : ""));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 String period = (from != null ? from : "debut") + "-" + (to != null ? to : "fin");
 return new ExportResult(companyId, "stock_movement_register", "csv", content,
 "text/csv", "registre-mouvements-stock-" + period + ".csv");
 }

 /**
 * E4 — Export CSV du taux d'utilisation des consultants.
 *
 * <p>Agrège par (projet, consultant) sur la période. Délègue à
 * {@link TimeBillingService#getUtilization(UUID, LocalDate, LocalDate)} (même logique que
 * l'endpoint JSON {@code GET /time-billing/utilization} — Part E3) et formate en CSV :
 * projet (code), libellé, consultant (UUID), heures saisies, heures facturées,
 * heures non facturées, taux d'utilisation (%).
 */
 private ExportResult exportTimeBillingUtilizationCsv(UUID companyId, LocalDate from, LocalDate to) {
 List<UtilizationLine> rows = timeBillingService.getUtilization(companyId, from, to);

 StringBuilder csv = new StringBuilder();
 csv.append("Projet (code);Libelle;Consultant;Heures saisies;Heures facturees;Heures non facturees;Taux utilisation (%)\n");
 for (UtilizationLine r : rows) {
 csv.append(String.format("%s;%s;%s;%s;%s;%s;%s\n",
 r.projectCode(), r.projectLabel(), r.consultant(),
 r.hoursLogged(), r.hoursBilled(), r.hoursUnbilled(),
 r.utilizationRate()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 String period = (from != null ? from : "debut") + "-" + (to != null ? to : "fin");
 return new ExportResult(companyId, "time_billing_utilization", "csv", content,
 "text/csv", "utilisation-consultants-" + period + ".csv");
 }

 /**
 * E4 — Export CSV du registre des immobilisations.
 *
 * <p>Liste toutes les immobilisations de l'entreprise avec : libellé, date d'acquisition,
 * coût d'acquisition, durée de vie (mois), valeur résiduelle, méthode d'amortissement,
 * amortissement cumulé, valeur nette comptable, statut. L'amortissement cumulé est la
 * somme des lignes d'échéancier postées (même logique que
 * {@code GET /fixed-assets} — FixedAssetsService.listAssets).
 */
 private ExportResult exportFixedAssetsRegisterCsv(UUID companyId) {
 List<Asset> assets = assetRepository.findByCompanyIdOrderByLabel(companyId);

 StringBuilder csv = new StringBuilder();
 csv.append("Libelle;Date acquisition;Cout acquisition;Duree (mois);Valeur residuelle;"
 + "Methode;Amortissement cumule;Valeur nette comptable;Statut\n");
 for (Asset asset : assets) {
 BigDecimal cumulative = depreciationScheduleLineRepository
 .findByAssetIdOrderByPeriodDate(asset.getId()).stream()
 .filter(jo.accountant.fixedassets.entity.DepreciationScheduleLine::isPosted)
 .map(jo.accountant.fixedassets.entity.DepreciationScheduleLine::getAmount)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal netBookValue = asset.getAcquisitionCost().subtract(cumulative);
 csv.append(String.format("%s;%s;%s;%d;%s;%s;%s;%s;%s\n",
 asset.getLabel(),
 asset.getAcquisitionDate() != null ? asset.getAcquisitionDate() : "",
 asset.getAcquisitionCost(),
 asset.getUsefulLifeMonths(),
 asset.getResidualValue(),
 asset.getDepreciationMethod(),
 cumulative, netBookValue, asset.getStatus()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 return new ExportResult(companyId, "fixed_assets_register", "csv", content,
 "text/csv", "registre-immobilisations-" + LocalDate.now() + ".csv");
 }

 /**
 * E4 — Export CSV du registre des opérations de change.
 *
 * <p>Liste toutes les opérations de change de l'entreprise sur la période (filtre par
 * {@code operationDate}). Colonnes : date, type, devise source, devise cible, montant
 * source, montant cible, taux, montant source (devise fonctionnelle), montant cible
 * (devise fonctionnelle), gain/perte de change, statut.
 */
 private ExportResult exportFxOperationsRegisterCsv(UUID companyId, LocalDate from, LocalDate to) {
 List<FxOperation> allOps = fxOperationRepository
 .findByCompanyIdOrderByOperationDateDesc(companyId);

 // Filtre par période en Java (le repository n'a pas encore de méthode "between" —
 // la liste des opérations est typiquement petite, le filtre client est acceptable).
 List<FxOperation> ops = allOps.stream()
 .filter(op -> {
 if (op.getOperationDate() == null) return false;
 if (from != null && op.getOperationDate().isBefore(from)) return false;
 if (to != null && op.getOperationDate().isAfter(to)) return false;
 return true;
 })
 .toList();

 StringBuilder csv = new StringBuilder();
 csv.append("Date;Type;Devise source;Devise cible;Montant source;Montant cible;Taux;"
 + "Montant source (fonctionnel);Montant cible (fonctionnel);Gain/Perte;Statut\n");
 for (FxOperation op : ops) {
 csv.append(String.format("%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s\n",
 op.getOperationDate(), op.getType(),
 op.getFromCurrency(), op.getToCurrency(),
 op.getFromAmount(), op.getToAmount(), op.getRate(),
 op.getFromAmountFunctional(), op.getToAmountFunctional(),
 op.getFxGainLoss(), op.getStatus()));
 }

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 String period = (from != null ? from : "debut") + "-" + (to != null ? to : "fin");
 return new ExportResult(companyId, "fx_operations_register", "csv", content,
 "text/csv", "registre-operations-change-" + period + ".csv");
 }

 /**
 * D1 (CSV) — Export CSV de la balance âgée fournisseurs.
 *
 * <p>Symétrique de la balance âgée clients — formate le résultat de
 * {@link #getSupplierAgedBalance(UUID)} en CSV avec une ligne par tranche d'âge.
 */
 private ExportResult exportSupplierAgedBalanceCsv(UUID companyId) {
 AgedBalance balance = getSupplierAgedBalance(companyId);

 StringBuilder csv = new StringBuilder();
 csv.append("Tranche;Montant\n");
 csv.append("Courant;").append(balance.current()).append("\n");
 csv.append("0-30 jours;").append(balance.d0_30()).append("\n");
 csv.append("31-60 jours;").append(balance.d31_60()).append("\n");
 csv.append("61-90 jours;").append(balance.d61_90()).append("\n");
 csv.append("90+ jours;").append(balance.d90_plus()).append("\n");
 csv.append("Total;").append(balance.totalBalanceDue()).append("\n");

 byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 return new ExportResult(companyId, "aged_balance_suppliers", "csv", content,
 "text/csv", "balance-agee-fournisseurs-" + LocalDate.now() + ".csv");
 }
}
