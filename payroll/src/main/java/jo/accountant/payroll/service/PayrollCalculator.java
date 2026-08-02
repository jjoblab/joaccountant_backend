package jo.accountant.payroll.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.employees.entity.Employee;
import jo.accountant.tax.entity.ContributionRule;
import jo.accountant.tax.entity.ContributionRule.ContributionBase;
import jo.accountant.tax.entity.ContributionRule.ContributionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calculateur de paie par tranches avec plafond PMSS/PMT + abattement CSG
 * (audit v4.7 §4.1 FIX CRITIQUE).
 *
 * <p><b>Problème</b> : la v4.7 utilisait un calcul simpliste {@code deduction = gross × rate / 100}
 * pour chaque WithholdingRule. Le calcul était grossièrement faux en France :
 * <ul>
 * <li>Pas de notion de plafond (PMSS France 2024 = 3 864 €/mois, PMT Haïti).</li>
 * <li>Pas d'assiette avec abattement (CSG déductible 6,865% sur 98,25% du brut).</li>
 * <li>Pas de tranche (Tranche A &lt; PMSS, Tranche B entre PMSS et 4×PMSS).</li>
 * <li>Charges patronales : un seul taux global, non persisté sur la run.</li>
 * </ul>
 *
 * <p><b>Solution</b> : calcul par tranches basé sur {@link ContributionRule}.
 *
 * <h2>Exemple CSG déductible France 2024</h2>
 * <pre>
 * brut = 5000 €
 * rule = CSG_DEDUCTIBLE, rate=6.865%, baseType=GROSS_ABATED, abatementRate=98.25%
 * assiette = 5000 × 98.25% = 4912.50 €
 * deduction = 4912.50 × 6.865% = 337.27 €
 * </pre>
 *
 * <h2>Exemple retraite Tranche A + Tranche B France 2024</h2>
 * <pre>
 * brut = 10000 €, PMSS = 3864 €
 * Tranche A : rule=RETRAITE_TA, rate=6.90%, baseType=CAPPED_GROSS, monthlyCeiling=3864
 * assiette = min(10000, 3864) = 3864 €
 * deduction TA = 3864 × 6.90% = 266.62 €
 * Tranche B : rule=RETRAITE_TB, rate=15.40%, baseType=TRANCHE_B, monthlyCeiling=3864, ceilingMultiplier=4
 * plafond_sup = 3864 × 4 = 15456 €
 * assiette TB = min(10000, 15456) - 3864 = 6136 €
 * deduction TB = 6136 × 15.40% = 944.94 €
 * Total retraite = 266.62 + 944.94 = 1211.56 €
 * </pre>
 *
 * <h2>Séparation employé / employeur</h2>
 * <p>Une {@link ContributionRule} avec {@link ContributionType#EMPLOYEE_AND_EMPLOYER} génère
 * 2 lignes séparées dans le résultat — une débitée au salarié (431), une débitée à l'employeur (645).
 *
 * <h2>Heures supplémentaires + absences + congés payés</h2>
 * <p>La surcharge {@link #calculate(UUID, UUID, Employee, List)} calcule le salaire brut à partir
 * de la fiche employé en appliquant :
 * <ul>
 * <li><b>Prorata base</b> : {@code baseSalary × (workingDays - absenceDays - paidLeaveDays) / workingDays}</li>
 * <li><b>HS +25%</b> : {@code overtimeHours25 × hourlyRate × 1.25}</li>
 * <li><b>HS +50%</b> : {@code overtimeHours50 × hourlyRate × 1.50}</li>
 * <li><b>Brut</b> : {@code baseProRata + HS25 + HS50}</li>
 * </ul>
 * <p>où :
 * <ul>
 * <li>{@code workingDays = 30} (convention mensuelle standard — 30 jours par mois)</li>
 * <li>{@code hourlyRate = baseSalary / 173.33} (durée légale mensuelle France 35h/sem × 52/12 ≈ 173.33h)</li>
 * </ul>
 *
 * <p><b>Limitation v4.7.2</b> : la régularisation annuelle n'est pas gérée. Les indemnités de
 * congés payés séparées (calculées sur 10% du brut ou maintien de salaire) seront ajoutées en v4.8.
 */
@Component
public class PayrollCalculator {

 private static final Logger LOG = LoggerFactory.getLogger(PayrollCalculator.class);
 private static final BigDecimal HUNDRED = new BigDecimal("100");

 /** nombre de jours travaillés par mois (convention mensuelle). */
 private static final BigDecimal WORKING_DAYS_PER_MONTH = new BigDecimal("30");

 /**
 * durée légale mensuelle en heures (France 35h/sem × 52/12 ≈ 173.33h).
 * Utilisée pour calculer le taux horaire de référence : {@code hourlyRate = baseSalary / 173.33}.
 *
 * <p><b>Lot B </b> — cette constante n'est plus utilisée pour le calcul réel : la
 * durée légale mensuelle est désormais lue depuis {@code Company.monthlyLegalHours}
 * (rétro-compat 173.33 si non configurée). Conservée comme fallback uniquement.
 */
 private static final BigDecimal DEFAULT_MONTHLY_LEGAL_HOURS = new BigDecimal("173.33");

 /** majoration HS +25% (coefficient 1.25). */
 private static final BigDecimal OVERTIME_25_RATE = new BigDecimal("1.25");

 /** majoration HS +50% (coefficient 1.50). */
 private static final BigDecimal OVERTIME_50_RATE = new BigDecimal("1.50");

 /** Lot B majoration HS +100% (coefficient 2.0) pour Haïti (au-delà de 56h/sem, dimanches/jours fériés). */
 private static final BigDecimal OVERTIME_100_RATE = new BigDecimal("2.00");

 /**
 * Lot B Jackson pour parser {@code bracketsJson} des ContributionRule PROGRESSIVE
 * (ex: AST Haïti). Injecté via constructeur ; nullable pour les tests unitaires qui
 * construisent PayrollCalculator sans Spring.
 */
 private final ObjectMapper objectMapper;

 /**
 * V89 — v7-6 : Repository des taux OFATMA Accidents par secteur.
 * Nullable pour les tests unitaires sans Spring (fallback taux 2% si null).
 */
 private final jo.accountant.payroll.repository.OfatmaSectorRateRepository ofatmaSectorRateRepository;

 /**
 * Constructeur par défaut — Lot B (injection ObjectMapper pour parser les bracketsJson
 * des ContributionRule PROGRESSIVE comme l'AST Haïti).
 */
 public PayrollCalculator(ObjectMapper objectMapper) {
 this(objectMapper, null);
 }

 /**
 * V89 — v7-6 : Constructeur avec OfatmaSectorRateRepository pour résolution dynamique
 * du taux OFATMA Accidents par secteur.
 */
 public PayrollCalculator(ObjectMapper objectMapper,
 jo.accountant.payroll.repository.OfatmaSectorRateRepository ofatmaSectorRateRepository) {
 this.objectMapper = objectMapper;
 this.ofatmaSectorRateRepository = ofatmaSectorRateRepository;
 }

 /**
 * Constructeur de compatibilité — utilisé par les tests unitaires sans Spring.
 * @deprecated utiliser le constructeur 1-arg avec ObjectMapper.
 */
 @Deprecated
 public PayrollCalculator() {
 this(null, null);
 }

 /**
 * V89 — v7-6 : Résout le taux OFATMA Accidents pour un secteur donné.
 *
 * <p>Si le sectorCode est null/blank → taux défaut 2,00% (rétro-compat V68).
 * Si le sectorCode n'est pas trouvé dans ofatma_sector_rate → taux défaut 2,00%.
 * Sinon → taux spécifique au secteur (0,50% à 6,00% selon la Loi OFATMA).
 */
 public BigDecimal resolveOfatmaAccidentRate(String sectorCode) {
 if (sectorCode == null || sectorCode.isBlank()) {
 return new BigDecimal("2.00"); // défaut V68
 }
 if (ofatmaSectorRateRepository == null) {
 LOG.warn("V89 — OfatmaSectorRateRepository non injecté, fallback taux 2.00%");
 return new BigDecimal("2.00");
 }
 return ofatmaSectorRateRepository
 .findBySectorCodeAndActiveTrue(sectorCode.trim().toUpperCase())
 .map(jo.accountant.payroll.entity.OfatmaSectorRate::getAccidentRate)
 .orElse(new BigDecimal("2.00")); // défaut si secteur inconnu
 }

 /**
 * Calcule les cotisations pour un salaire brut donné.
 *
 * <p>Variante "brut déjà calculé" — utilisée en rétro-compatibilité ou quand l'appelant a
 * déjà calculé le brut (par exemple pour un employé sans HS ni absences).
 *
 * @param companyId identifiant de l'entreprise (pour le cache des règles)
 * @param employeeId identifiant de l'employé (pour le log)
 * @param grossSalary salaire brut mensuel
 * @param rules règles de cotisation actives pour l'entreprise (employé + employeur)
 * @return résultat détaillé avec cotisations par règle + totaux
 */
 public PayrollCalculationResult calculate(UUID companyId, UUID employeeId,
 BigDecimal grossSalary, List<ContributionRule> rules) {
 if (grossSalary == null || grossSalary.compareTo(BigDecimal.ZERO) < 0) {
 throw new IllegalArgumentException("grossSalary ne peut pas être null ou négatif");
 }
 return doCalculate(employeeId, grossSalary, grossSalary, grossSalary,
 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, rules, null);
 }

 /**
 * Calcule les cotisations à partir de la fiche employé — .
 *
 * <p>Le salaire brut est calculé en appliquant le prorata absences/CP et les majorations HS :
 * <pre>
 * baseProRata = baseSalary × (workingDays - absenceDays - paidLeaveDays) / workingDays
 * hourlyRate = baseSalary / monthlyLegalHours (173.33 France / 208 Haïti — Lot B )
 * overtime25 = overtimeHours25 × hourlyRate × 1.25
 * overtime50 = overtimeHours50 × hourlyRate × 1.50
 * overtime100 = overtimeHours100 × hourlyRate × 2.00 (Lot B HS Haïti)
 * grossSalary = baseProRata + overtime25 + overtime50 + overtime100
 * </pre>
 *
 * <p>Si absenceDays + paidLeaveDays ≥ workingDays, le baseProRata est plafonné à 0 (l'employé
 * n'a pas travaillé du tout — seul le montant des HS reste, s'il y en a).
 *
 * <p><b>Lot B </b> — la durée légale mensuelle est lue depuis {@code monthlyLegalHours}
 * (paramètre explicite). Si null, fallback {@code DEFAULT_MONTHLY_LEGAL_HOURS=173.33} (France 35h).
 * Pour Haïti (48h/sem × 52/12 ≈ 208h), passer 208.
 *
 * @param companyId identifiant de l'entreprise (pour le cache des règles)
 * @param employeeId identifiant de l'employé (pour le log)
 * @param employee fiche employé (baseSalary, absenceDays, paidLeaveDays, HS25, HS50, HS100)
 * @param monthlyLegalHours durée légale mensuelle (173.33 France, 208 Haïti — Lot B ).
 * Si null, fallback 173.33 (rétro-compat).
 * @param rules règles de cotisation actives pour l'entreprise
 * @return résultat détaillé avec brut calculé + cotisations par règle + totaux
 */
 public PayrollCalculationResult calculate(UUID companyId, UUID employeeId,
 Employee employee,
 java.math.BigDecimal monthlyLegalHours,
 List<ContributionRule> rules) {
 if (employee == null) {
 throw new IllegalArgumentException("employee ne peut pas être null");
 }
 BigDecimal baseSalary = employee.getBaseSalary();
 BigDecimal absenceDays = nz(employee.getAbsenceDays());
 BigDecimal paidLeaveDays = nz(employee.getPaidLeaveDays());
 BigDecimal overtimeHours25 = nz(employee.getOvertimeHours25());
 BigDecimal overtimeHours50 = nz(employee.getOvertimeHours50());
 // Lot B HS +100% pour Haïti (au-delà de 56h/sem, dimanches/jours fériés).
 BigDecimal overtimeHours100 = nz(employee.getOvertimeHours100());

 // Lot B durée légale mensuelle configurable (France 173.33, Haïti 208).
 BigDecimal legalHours = (monthlyLegalHours != null && monthlyLegalHours.compareTo(BigDecimal.ZERO) > 0)
 ? monthlyLegalHours : DEFAULT_MONTHLY_LEGAL_HOURS;

 // --- V90 — v7-7 : Indemnité congés payés séparée du salaire de base ---
 // Code du Travail Haïti art. 156 : 15 jours ouvrables/an, indemnisés à 100%.
 // Jusqu'en v6.x, paidLeaveDays était déduit du salaire de base comme une absence —
 // non conforme. Maintenant :
 // - absenceDays → déduit du salaire de base (absences non rémunérées)
 // - paidLeaveDays → indemnité CP séparée ajoutée au brut (ne réduit plus le base)
 // 26 jours ouvrables/mois en Haïti (vs 30 anciennement pour la base ProRata).

 BigDecimal WORKING_DAYS_PER_MONTH_HT = new BigDecimal("26");

 // baseProRata = baseSalary × (workingDays - absenceDays) / workingDays
 // (les paidLeaveDays ne réduisent plus le base — ils génèrent une indemnité CP séparée)
 BigDecimal effectiveWorkedDays = WORKING_DAYS_PER_MONTH_HT.subtract(absenceDays);
 BigDecimal baseProRata;
 if (effectiveWorkedDays.compareTo(BigDecimal.ZERO) <= 0) {
 // L'employé n'a pas travaillé du tout ce mois — pas de salaire de base
 baseProRata = BigDecimal.ZERO;
 } else {
 baseProRata = baseSalary.multiply(effectiveWorkedDays)
 .divide(WORKING_DAYS_PER_MONTH_HT, 4, RoundingMode.HALF_UP);
 }

 // Indemnité CP séparée = baseSalary × paidLeaveDays / 26 (ne réduit plus le base)
 BigDecimal paidLeaveIndemnity = BigDecimal.ZERO;
 if (paidLeaveDays.compareTo(BigDecimal.ZERO) > 0) {
 paidLeaveIndemnity = baseSalary.multiply(paidLeaveDays)
 .divide(WORKING_DAYS_PER_MONTH_HT, 4, RoundingMode.HALF_UP);
 }

 // hourlyRate = baseSalary / monthlyLegalHours (Lot B configurable)
 BigDecimal hourlyRate = baseSalary.divide(legalHours, 6, RoundingMode.HALF_UP);
 // HS +25% = overtimeHours25 × hourlyRate × 1.25
 BigDecimal overtimeAmount25 = overtimeHours25.multiply(hourlyRate)
 .multiply(OVERTIME_25_RATE).setScale(4, RoundingMode.HALF_UP);
 // HS +50% = overtimeHours50 × hourlyRate × 1.50
 BigDecimal overtimeAmount50 = overtimeHours50.multiply(hourlyRate)
 .multiply(OVERTIME_50_RATE).setScale(4, RoundingMode.HALF_UP);
 // Lot B HS +100% = overtimeHours100 × hourlyRate × 2.00 (Haïti)
 BigDecimal overtimeAmount100 = overtimeHours100.multiply(hourlyRate)
 .multiply(OVERTIME_100_RATE).setScale(4, RoundingMode.HALF_UP);

 // Salaire brut total = base (après absences) + indemnité CP + HS
 BigDecimal grossSalary = baseProRata
 .add(paidLeaveIndemnity)
 .add(overtimeAmount25).add(overtimeAmount50).add(overtimeAmount100);

 if (LOG.isDebugEnabled()) {
 LOG.debug("V90 — PayrollCalculator {} : base={} legalHours={} absences={} CP={} (indemnity={}) HS25={} HS50={} HS100={} → baseProRata={} brut={}",
 employeeId, baseSalary, legalHours, absenceDays, paidLeaveDays, paidLeaveIndemnity,
 overtimeHours25, overtimeHours50, overtimeHours100,
 baseProRata, grossSalary);
 }

 // V89 — v7-6 : Résolution dynamique du taux OFATMA Accidents par secteur.
 // Si l'employé a un ofatmaSectorCode renseigné, on surcharge le taux 2% (défault V68)
 // par le taux spécifique au secteur (0,5% à 6% selon Loi OFATMA).
 BigDecimal ofatmaRateOverride = null;
 if (employee.getOfatmaSectorCode() != null && !employee.getOfatmaSectorCode().isBlank()) {
 ofatmaRateOverride = resolveOfatmaAccidentRate(employee.getOfatmaSectorCode());
 LOG.info("V89 — Employé {} sectorCode={} → taux OFATMA Accidents = {}%",
 employeeId, employee.getOfatmaSectorCode(), ofatmaRateOverride);
 }

 return doCalculate(employeeId, baseSalary, baseProRata, grossSalary,
 overtimeAmount25, overtimeAmount50, overtimeAmount100, rules, ofatmaRateOverride);
 }

 /**
 * Surcharge conservée pour rétro-compatibilité — utilise la durée légale par défaut 173.33h
 * (France). Les appelants qui veulent gérer Haïti doivent utiliser la surcharge 5-args avec
 * monthlyLegalHours explicite.
 */
 public PayrollCalculationResult calculate(UUID companyId, UUID employeeId,
 Employee employee, List<ContributionRule> rules) {
 return calculate(companyId, employeeId, employee, DEFAULT_MONTHLY_LEGAL_HOURS, rules);
 }

 /**
 * Cœur de calcul des cotisations (shared par les 2 surcharges).
 *
 * @param employeeId identifiant pour le log
 * @param baseSalary salaire de base (avant prorata) — pour le bulletin
 * @param baseProRata salaire de base après prorata absences/CP
 * @param grossSalary brut total = baseProRata + HS25 + HS50 + HS100
 * @param overtimeAmount25 montant des HS +25% en devise
 * @param overtimeAmount50 montant des HS +50% en devise
 * @param overtimeAmount100 montant des HS +100% en devise (Lot B Haïti)
 * @param rules règles de cotisation applicables
 */
 private PayrollCalculationResult doCalculate(UUID employeeId,
 BigDecimal baseSalary,
 BigDecimal baseProRata,
 BigDecimal grossSalary,
 BigDecimal overtimeAmount25,
 BigDecimal overtimeAmount50,
 BigDecimal overtimeAmount100,
 List<ContributionRule> rules,
 BigDecimal ofatmaRateOverride) {
 if (grossSalary == null || grossSalary.compareTo(BigDecimal.ZERO) < 0) {
 throw new IllegalArgumentException("grossSalary ne peut pas être null ou négatif");
 }

 // V87 — v7-5 : Séparer les règles ITS des règles de cotisations sociales.
 // L'ITS est calculé sur l'assiette (brut - cotisations sociales CNSS/OFATMA/AST),
 // il doit donc être traité APRÈS les autres règles.
 List<ContributionRule> socialRules = new ArrayList<>();
 List<ContributionRule> itsRules = new ArrayList<>();
 for (ContributionRule rule : rules) {
 if ("ITS_HT".equals(rule.getCode()) || "ITS".equals(rule.getCode())) {
 itsRules.add(rule);
 } else {
 socialRules.add(rule);
 }
 }

 List<ContributionLine> employeeContributions = new ArrayList<>();
 List<ContributionLine> employerContributions = new ArrayList<>();
 BigDecimal totalEmployeeDeductions = BigDecimal.ZERO;
 BigDecimal totalEmployerContributions = BigDecimal.ZERO;

 for (ContributionRule rule : socialRules) {
 BigDecimal assiette = computeBase(grossSalary, rule);
 if (assiette.compareTo(BigDecimal.ZERO) <= 0) {
 // Assiette nulle (ex: Tranche B sur un salaire < PMSS) → pas de cotisation
 continue;
 }

 // V89 — v7-6 : Pour OFATMA_HT_ACCIDENT, surcharger le taux par le taux sectoriel
 // (résolu depuis ofatma_sector_rate via Employee.ofatmaSectorCode).
 BigDecimal effectiveRate = rule.getRate();
 if ("OFATMA_HT_ACCIDENT".equals(rule.getCode()) && ofatmaRateOverride != null) {
 effectiveRate = ofatmaRateOverride;
 LOG.debug("V89 — OFATMA_HT_ACCIDENT taux surchargé : {} → {}",
 rule.getRate(), effectiveRate);
 }

 // Lot B si bracketType=PROGRESSIVE, calculer le montant via bracketsJson
 // (chaque palier taxé à son propre taux — ex: AST Haïti 0%/1%/2%/3%).
 BigDecimal amount;
 BigDecimal effectiveRateForBulletin;
 if (rule.getBracketType() == jo.accountant.core.tax.WithholdingBracketType.PROGRESSIVE
 && rule.getBracketsJson() != null && !rule.getBracketsJson().isBlank()) {
 BigDecimal[] progressive = computeProgressiveAmount(assiette, rule.getBracketsJson());
 amount = progressive[0];
 // Taux effectif moyen pour affichage sur le bulletin (peut être 0 si assiette=0)
 effectiveRateForBulletin = assiette.compareTo(BigDecimal.ZERO) > 0
 ? amount.multiply(HUNDRED).divide(assiette, 4, RoundingMode.HALF_UP)
 : BigDecimal.ZERO;
 } else {
 // Comportement historique : amount = assiette × effectiveRate / 100
 amount = assiette.multiply(effectiveRate)
 .divide(HUNDRED, 4, RoundingMode.HALF_UP);
 effectiveRateForBulletin = effectiveRate;
 }

 if (amount.compareTo(BigDecimal.ZERO) <= 0) {
 continue; // Cotisation nulle (ex: AST si salaire < premier threshold non nul)
 }

 switch (rule.getContributionType()) {
 case EMPLOYEE:
 employeeContributions.add(new ContributionLine(
 rule.getCode(), rule.getLabel(), effectiveRateForBulletin, rule.getBaseType().name(),
 assiette, amount, "EMPLOYEE"));
 totalEmployeeDeductions = totalEmployeeDeductions.add(amount);
 break;
 case EMPLOYER:
 employerContributions.add(new ContributionLine(
 rule.getCode(), rule.getLabel(), effectiveRateForBulletin, rule.getBaseType().name(),
 assiette, amount, "EMPLOYER"));
 totalEmployerContributions = totalEmployerContributions.add(amount);
 break;
 case EMPLOYEE_AND_EMPLOYER:
 employeeContributions.add(new ContributionLine(
 rule.getCode() + "_SAL", rule.getLabel() + " (salariale)",
 effectiveRateForBulletin, rule.getBaseType().name(), assiette, amount, "EMPLOYEE"));
 employerContributions.add(new ContributionLine(
 rule.getCode() + "_PAT", rule.getLabel() + " (patronale)",
 effectiveRateForBulletin, rule.getBaseType().name(), assiette, amount, "EMPLOYER"));
 totalEmployeeDeductions = totalEmployeeDeductions.add(amount);
 totalEmployerContributions = totalEmployerContributions.add(amount);
 break;
 }
 }

 // V87 — v7-5 : Calcul ITS sur l'assiette (brut - cotisations sociales salariales).
 // L'ITS est une retenue à la source (impôt sur le revenu salarial) — pas une cotisation
 // sociale. Il s'ajoute aux déductions salariales mais n'a pas de contrepartie employeur.
 if (!itsRules.isEmpty()) {
 BigDecimal itsTaxableBase = computeTaxableBaseForIts(
 grossSalary, totalEmployeeDeductions, BigDecimal.ZERO, BigDecimal.ZERO);
 // Note: les cotisations patronales ne réduisent pas l'assiette ITS (elles ne sont
 // pas déductibles du salaire imposable de l'employé). L'AST est déjà inclus dans
 // totalEmployeeDeductions si la règle AST est active.
 BigDecimal itsAmount = computeIts(itsTaxableBase, itsRules);
 if (itsAmount.compareTo(BigDecimal.ZERO) > 0) {
 ContributionRule itsRule = itsRules.get(0);
 BigDecimal effectiveRateForBulletin = itsTaxableBase.compareTo(BigDecimal.ZERO) > 0
 ? itsAmount.multiply(HUNDRED).divide(itsTaxableBase, 4, RoundingMode.HALF_UP)
 : BigDecimal.ZERO;
 employeeContributions.add(new ContributionLine(
 itsRule.getCode(), itsRule.getLabel(), effectiveRateForBulletin,
 itsRule.getBaseType().name(), itsTaxableBase, itsAmount, "EMPLOYEE"));
 totalEmployeeDeductions = totalEmployeeDeductions.add(itsAmount);
 }
 }

 BigDecimal netSalary = grossSalary.subtract(totalEmployeeDeductions);

 if (LOG.isDebugEnabled()) {
 LOG.debug("PayrollCalculator {} : gross={}, employeeDeductions={}, employerContributions={}, net={}",
 employeeId, grossSalary, totalEmployeeDeductions, totalEmployerContributions, netSalary);
 }

 return new PayrollCalculationResult(
 employeeId, baseSalary, baseProRata,
 overtimeAmount25, overtimeAmount50, overtimeAmount100,
 grossSalary,
 employeeContributions, employerContributions,
 totalEmployeeDeductions, totalEmployerContributions,
 netSalary);
 }

 /**
 * Lot B Calcule le montant d'une cotisation à barème progressif (ex: AST Haïti).
 *
 * <p>Format {@code bracketsJson} : {@code [{"threshold":0,"rate":0},{"threshold":50000,"rate":1},...]}.
 * Chaque palier taxe la part de l'assiette comprise entre son {@code threshold} et le
 * {@code threshold} du palier suivant à son propre {@code rate} (en %).
 *
 * <p>Exemple AST Haïti avec assiette=120 000 HTG :
 * <pre>
 * [0, 50000] × 0% = 0
 * [50000, 100000] × 1% = 500
 * [100000, 120000] × 2% = 400 (120000 < 150000 donc dernier palier)
 * Total = 900 HTG
 * </pre>
 *
 * @param assiette assiette de calcul (ex: salaire brut)
 * @param bracketsJson barème JSON
 * @return tableau [montant, ] (toujours size 1 — utilisera [0] pour le montant)
 */
 private BigDecimal[] computeProgressiveAmount(BigDecimal assiette, String bracketsJson) {
 if (objectMapper == null) {
 LOG.warn("ObjectMapper non injecté — impossible de parser bracketsJson, montant AST=0");
 return new BigDecimal[]{ BigDecimal.ZERO };
 }
 try {
 com.fasterxml.jackson.databind.JsonNode nodes = objectMapper.readTree(bracketsJson);
 if (!nodes.isArray() || nodes.isEmpty()) {
 return new BigDecimal[]{ BigDecimal.ZERO };
 }
 // Trier par threshold croissant (sécurité — les seeds V68 sont déjà triés)
 List<BigDecimal[]> brackets = new ArrayList<>();
 for (com.fasterxml.jackson.databind.JsonNode n : nodes) {
 BigDecimal threshold = n.has("threshold")
 ? n.get("threshold").decimalValue() : BigDecimal.ZERO;
 BigDecimal rate = n.has("rate")
 ? n.get("rate").decimalValue() : BigDecimal.ZERO;
 brackets.add(new BigDecimal[]{ threshold, rate });
 }
 brackets.sort((a, b) -> a[0].compareTo(b[0]));

 BigDecimal total = BigDecimal.ZERO;
 for (int i = 0; i < brackets.size(); i++) {
 BigDecimal lower = brackets.get(i)[0];
 BigDecimal rate = brackets.get(i)[1];
 BigDecimal upper = (i + 1 < brackets.size()) ? brackets.get(i + 1)[0] : null;
 // Part de l'assiette dans la tranche courante
 BigDecimal trancheUpper = (upper != null) ? assiette.min(upper) : assiette;
 if (trancheUpper.compareTo(lower) <= 0) break; // assiette < lower → hors tranche
 BigDecimal trancheAmount = trancheUpper.subtract(lower);
 total = total.add(trancheAmount.multiply(rate).divide(HUNDRED, 4, RoundingMode.HALF_UP));
 }
 return new BigDecimal[]{ total };
 } catch (Exception e) {
 LOG.warn("Échec parsing bracketsJson '{}', montant AST=0 : {}", bracketsJson, e.getMessage());
 return new BigDecimal[]{ BigDecimal.ZERO };
 }
 }

 /** Helper — null-safe zero pour BigDecimal. */
 private static BigDecimal nz(BigDecimal v) {
 return v != null ? v : BigDecimal.ZERO;
 }

 /**
 * Calcule l'assiette d'une cotisation selon son {@link ContributionBase}.
 *
 * <p>Formules :
 * <ul>
 * <li>{@code GROSS} : {@code gross}</li>
 * <li>{@code GROSS_ABATED} : {@code gross × abatementRate / 100}</li>
 * <li>{@code CAPPED_GROSS} : {@code min(gross, monthlyCeiling)}</li>
 * <li>{@code CAPPED_GROSS_ABATED} : {@code min(gross, monthlyCeiling) × abatementRate / 100}</li>
 * <li>{@code TRANCHE_B} : {@code min(gross, monthlyCeiling × ceilingMultiplier) - monthlyCeiling}
 * (plafonné à 0 si gross &lt; monthlyCeiling)</li>
 * </ul>
 */
 private BigDecimal computeBase(BigDecimal gross, ContributionRule rule) {
 BigDecimal ceiling = rule.getMonthlyCeiling();
 BigDecimal abatement = rule.getAbatementRate() != null ? rule.getAbatementRate() : HUNDRED;
 BigDecimal abatementFactor = abatement.divide(HUNDRED, 6, RoundingMode.HALF_UP);

 switch (rule.getBaseType()) {
 case GROSS:
 return gross;

 case GROSS_ABATED:
 return gross.multiply(abatementFactor).setScale(4, RoundingMode.HALF_UP);

 case CAPPED_GROSS:
 if (ceiling == null) return gross;
 return gross.min(ceiling);

 case CAPPED_GROSS_ABATED:
 if (ceiling == null) return gross.multiply(abatementFactor).setScale(4, RoundingMode.HALF_UP);
 return gross.min(ceiling).multiply(abatementFactor).setScale(4, RoundingMode.HALF_UP);

 case TRANCHE_B:
 if (ceiling == null) return BigDecimal.ZERO;
 BigDecimal multiplier = rule.getCeilingMultiplier() != null
 ? rule.getCeilingMultiplier() : new BigDecimal("4");
 BigDecimal upperBound = ceiling.multiply(multiplier);
 if (gross.compareTo(ceiling) <= 0) return BigDecimal.ZERO;
 return gross.min(upperBound).subtract(ceiling).max(BigDecimal.ZERO);

 default:
 LOG.warn("BaseType non géré : {} — fallback GROSS", rule.getBaseType());
 return gross;
 }
 }

 /**
 * Résultat d'un calcul de paie pour un employé.
 *
 * @param baseSalary salaire de base brut (avant prorata) — * @param baseProRata salaire de base après prorata absences/CP — * @param overtimeAmount25 montant des HS +25% en devise — * @param overtimeAmount50 montant des HS +50% en devise — * @param grossSalary brut total = baseProRata + HS25 + HS50
 * @param employeeContributions cotisations salariales détaillées
 * @param employerContributions cotisations patronales détaillées
 * @param totalEmployeeDeductions total des cotisations salariales
 * @param totalEmployerContributions total des cotisations patronales
 * @param netSalary net à payer = grossSalary - totalEmployeeDeductions
 */
 public record PayrollCalculationResult(
 UUID employeeId,
 BigDecimal baseSalary,
 BigDecimal baseProRata,
 BigDecimal overtimeAmount25,
 BigDecimal overtimeAmount50,
 BigDecimal overtimeAmount100,
 BigDecimal grossSalary,
 List<ContributionLine> employeeContributions,
 List<ContributionLine> employerContributions,
 BigDecimal totalEmployeeDeductions,
 BigDecimal totalEmployerContributions,
 BigDecimal netSalary
 ) {

 /**
 * Constructeur de compatibilité 11-args (avant Lot B sans overtimeAmount100).
 * Délègue avec overtimeAmount100=0.
 * @deprecated utiliser le constructeur 12-args avec overtimeAmount100 (Lot B ).
 */
 @Deprecated
 public PayrollCalculationResult(
 UUID employeeId,
 BigDecimal baseSalary,
 BigDecimal baseProRata,
 BigDecimal overtimeAmount25,
 BigDecimal overtimeAmount50,
 BigDecimal grossSalary,
 List<ContributionLine> employeeContributions,
 List<ContributionLine> employerContributions,
 BigDecimal totalEmployeeDeductions,
 BigDecimal totalEmployerContributions,
 BigDecimal netSalary
 ) {
 this(employeeId, baseSalary, baseProRata, overtimeAmount25, overtimeAmount50,
 BigDecimal.ZERO, grossSalary,
 employeeContributions, employerContributions,
 totalEmployeeDeductions, totalEmployerContributions, netSalary);
 }
 }

 /**
 * Ligne de cotisation détaillée — pour le bulletin de paie.
 */
 public record ContributionLine(
 String code,
 String label,
 BigDecimal rate, // en %
 String baseType, // ContributionBase name
 BigDecimal base, // assiette calculée
 BigDecimal amount, // montant de la cotisation
 String party // "EMPLOYEE" ou "EMPLOYER"
 ) {}

 // =========================================================================
 // V86 — v7-4 : 13e mois (Code du Travail Haïti art. 153)
 // =========================================================================

 /**
 * V86 — v7-4 : Calcul du 13e mois (Code du Travail Haïti art. 153).
 *
 * <p>Le 13e mois est obligatoire pour tout employé haïtien. Le calcul :
 * <ul>
 * <li>Si ancienneté ≥ 12 mois au 31/12/{year} : 1 mois de salaire de base.</li>
 * <li>Si ancienneté < 12 mois : prorata = baseSalary × (monthsWorked / 12).</li>
 * <li>Plafond 12 mois (un employé avec 24 mois d'ancienneté reçoit 1 mois, pas 2).</li>
 * </ul>
 *
 * <p>Cotisations sociales CNSS/OFATMA/AST <b>non appliquées</b> sur le 13e mois
 * (sauf si la loi change — à valider avec CNSS). En revanche, l'ITS (impôt sur salaire)
 * <b>est appliqué</b> sur le 13e mois (Code Fiscal art. 156) — voir
 * {@link #calculateThirteenthMonthNet}.
 *
 * @param employee l'employé (doit avoir thirteenthMonthEligible=true)
 * @param year année fiscale (le 13e mois est versé en décembre)
 * @return le montant du 13e mois brut
 */
 public BigDecimal calculateThirteenthMonth(jo.accountant.employees.entity.Employee employee, int year) {
 if (employee == null) {
 throw new IllegalArgumentException("employee ne peut pas être null");
 }
 if (Boolean.FALSE.equals(employee.getThirteenthMonthEligible())) {
 LOG.info("V86 — Employé {} non éligible au 13e mois (thirteenthMonthEligible=false)",
 employee.getId());
 return BigDecimal.ZERO;
 }

 java.time.LocalDate hireDate = employee.getHireDate();
 if (hireDate == null) {
 LOG.warn("V86 — Employé {} sans hireDate, 13e mois = 0", employee.getId());
 return BigDecimal.ZERO;
 }
 java.time.LocalDate december31 = java.time.LocalDate.of(year, 12, 31);

 // Si embauché après le 31/12 de l'année → pas éligible cette année
 if (hireDate.isAfter(december31)) {
 return BigDecimal.ZERO;
 }

 // Calcul des mois travaillés entre hireDate et 31/12/year
 long monthsWorked = java.time.temporal.ChronoUnit.MONTHS.between(hireDate, december31) + 1;
 if (monthsWorked > 12) monthsWorked = 12; // plafond 12 mois
 if (monthsWorked < 0) monthsWorked = 0;

 BigDecimal prorataFactor = BigDecimal.valueOf(monthsWorked)
 .divide(BigDecimal.valueOf(12), 4, java.math.RoundingMode.HALF_UP);

 BigDecimal baseSalary = employee.getBaseSalary() != null
 ? employee.getBaseSalary() : BigDecimal.ZERO;
 BigDecimal thirteenthMonthGross = baseSalary.multiply(prorataFactor)
 .setScale(2, java.math.RoundingMode.HALF_UP);

 LOG.info("V86 — 13e mois employé {} (embauché le {}, {} mois travaillés en {}) : brut = {} (prorata {})",
 employee.getId(), hireDate, monthsWorked, year, thirteenthMonthGross, prorataFactor);

 return thirteenthMonthGross;
 }

 /**
 * V86 — v7-4 : Calcule le 13e mois net (après ITS, sans cotisations sociales).
 *
 * @param employee l'employé
 * @param year année fiscale
 * @param itsRules règles ITS applicables (typiquement une seule règle ITS_HT active)
 * @return montant net du 13e mois
 */
 public BigDecimal calculateThirteenthMonthNet(jo.accountant.employees.entity.Employee employee,
 int year,
 java.util.List<ContributionRule> itsRules) {
 BigDecimal gross = calculateThirteenthMonth(employee, year);
 if (gross.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

 // ITS appliqué sur le 13e mois (barème progressif mensuel — voir v7-5)
 BigDecimal itsAmount = computeIts(gross, itsRules);
 return gross.subtract(itsAmount);
 }

 // =========================================================================
 // V87 — v7-5 : ITS Haïti (Impôt Traitements / Salaires)
 // =========================================================================

 /**
 * V87 — v7-5 : Calcule l'ITS (Impôt sur Traitements et Salaires) sur un salaire imposable.
 *
 * <p>Barème progressif mensuel (Code Fiscal art. 156) — appliqué sur le salaire brut
 * après déduction CNSS/OFATMA (assiette imposable = brut - cotisations sociales).
 *
 * <p>Pour les ONG exonérées (Company.taxExemptionStatus = NGO_EXEMPT), l'ITS n'est pas
 * appliqué (les employés d'ONG sont exonérés d'ITS) — ce filtrage doit être fait par
 * l'appelant en passant une liste vide de règles ITS.
 *
 * @param taxableBase assiette imposable (brut - CNSS - OFATMA - AST)
 * @param itsRules règles ITS actives (typiquement une seule règle ITS_HT)
 * @return montant de l'ITS
 */
 public BigDecimal computeIts(BigDecimal taxableBase, java.util.List<ContributionRule> itsRules) {
 if (taxableBase == null || taxableBase.compareTo(BigDecimal.ZERO) <= 0) {
 return BigDecimal.ZERO;
 }
 if (itsRules == null || itsRules.isEmpty()) {
 LOG.warn("V87 — Aucune règle ITS configurée, retour 0");
 return BigDecimal.ZERO;
 }

 ContributionRule itsRule = itsRules.get(0); // première règle active
 if (itsRule.getBracketType() != jo.accountant.core.tax.WithholdingBracketType.PROGRESSIVE) {
 // Fallback FLAT : taux unique appliqué à toute la base
 BigDecimal flatAmount = taxableBase.multiply(itsRule.getRate())
 .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
 LOG.info("V87 — ITS (FLAT) base={} rate={}→ montant={}",
 taxableBase, itsRule.getRate(), flatAmount);
 return flatAmount;
 }

 // Mode PROGRESSIVE — parser bracketsJson et calculer par tranches
 BigDecimal progressiveAmount = computeProgressiveAmount(taxableBase, itsRule.getBracketsJson())[0]
 .setScale(2, java.math.RoundingMode.HALF_UP);
 LOG.info("V87 — ITS (PROGRESSIVE) base={} brackets={} → montant={}",
 taxableBase, itsRule.getBracketsJson(), progressiveAmount);
 return progressiveAmount;
 }

 /**
 * V87 — v7-5 : Calcule l'assiette imposable ITS = brut - CNSS - OFATMA - AST.
 *
 * <p>Le plancher est 0 (si les cotisations dépassent le brut — cas théorique impossible
 * mais défensif).
 */
 public BigDecimal computeTaxableBaseForIts(BigDecimal grossSalary,
 BigDecimal cnssAmount,
 BigDecimal ofatmaAmount,
 BigDecimal astAmount) {
 BigDecimal taxable = grossSalary
 .subtract(nz(cnssAmount))
 .subtract(nz(ofatmaAmount))
 .subtract(nz(astAmount));
 return taxable.max(BigDecimal.ZERO);
 }
}
