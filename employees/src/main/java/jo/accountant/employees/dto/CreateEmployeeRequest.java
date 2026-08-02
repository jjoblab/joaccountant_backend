package jo.accountant.employees.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.employees.entity.ContractType;

/**
 * Corps de requête pour {@code POST .../employees}.
 *
 * <p>Deux variantes d'usage :
 * <ul>
 * <li>L'employeur a déjà créé le tiers EMPLOYEE via `:third-parties` — passer
 * {@code thirdPartyId} explicitement.</li>
 * <li>L'employeur n'a pas encore créé le tiers — passer {@code thirdPartyName} et le
 * {@code collectiveAccountId} du compte collectif employés (généralement classe 42
 * en SYSCOHADA). Le service crée le tiers en même temps que l'employé
 * ({@code createWithThirdParty}).</li>
 * </ul>
 *
 * @param thirdPartyId ID du tiers EMPLOYEE existant (optionnel si thirdPartyName fourni)
 * @param thirdPartyName Nom du tiers à créer (optionnel si thirdPartyId fourni)
 * @param collectiveAccountId Compte collectif employés (requis si thirdPartyName fourni)
 * @param employeeNumber Numéro d'employé unique par entreprise
 * @param position Libellé du poste
 * @param department Département (texte libre)
 * @param hireDate Date d'embauche
 * @param baseSalary Salaire de base (> 0)
 * @param salaryCurrency Code ISO 4217 (défaut : HTG)
 * @param contractType PERMANENT / FIXED_TERM / CONSULTANT
 * @param bankAccountNumber Numéro de compte bancaire pour virement (nullable)
 * @param overtimeHours25 Heures supplémentaires majorées à +25% (, défaut 0)
 * @param overtimeHours50 Heures supplémentaires majorées à +50% (, défaut 0)
 * @param overtimeHours100 Heures supplémentaires majorées à +100% (lot-B, Haïti >56h/dimanche/férié, défaut 0)
 * @param absenceDays Jours d'absence non rémunérés sur la période (, défaut 0)
 * @param paidLeaveDays Jours de congés payés pris sur la période (, défaut 0)
 * @param cnssNumber Matricule CNSS Haïti (10 chiffres, lot-B, nullable)
 * @param ofatmaSectorCode Code secteur OFATMA pour taux accidents variable 0.5-6% (lot-B, nullable)
 * @param thirteenthMonthEligible Éligibilité 13ᵉ mois (Code Travail art. 153, défaut true si pays=HT)
 */
public record CreateEmployeeRequest(
 UUID thirdPartyId,
 String thirdPartyName,
 UUID collectiveAccountId,
 @NotBlank String employeeNumber,
 String position,
 String department,
 @NotNull LocalDate hireDate,
 @NotNull @Positive BigDecimal baseSalary,
 String salaryCurrency,
 @NotNull ContractType contractType,
 String bankAccountNumber,
 @PositiveOrZero BigDecimal overtimeHours25,
 @PositiveOrZero BigDecimal overtimeHours50,
 @PositiveOrZero BigDecimal overtimeHours100,
 @PositiveOrZero BigDecimal absenceDays,
 @PositiveOrZero BigDecimal paidLeaveDays,
 String cnssNumber,
 String ofatmaSectorCode,
 Boolean thirteenthMonthEligible
) {
 public CreateEmployeeRequest {
 if (overtimeHours25 == null) overtimeHours25 = BigDecimal.ZERO;
 if (overtimeHours50 == null) overtimeHours50 = BigDecimal.ZERO;
 if (overtimeHours100 == null) overtimeHours100 = BigDecimal.ZERO;
 if (absenceDays == null) absenceDays = BigDecimal.ZERO;
 if (paidLeaveDays == null) paidLeaveDays = BigDecimal.ZERO;
 if (thirteenthMonthEligible == null) thirteenthMonthEligible = Boolean.TRUE;
 }

 /** Rétro-compatibilité — ancien constructeur 11-args sans les champs HS/absences. */
 public CreateEmployeeRequest(
 UUID thirdPartyId,
 String thirdPartyName,
 UUID collectiveAccountId,
 @NotBlank String employeeNumber,
 String position,
 String department,
 @NotNull LocalDate hireDate,
 @NotNull @Positive BigDecimal baseSalary,
 String salaryCurrency,
 @NotNull ContractType contractType,
 String bankAccountNumber
 ) {
 this(thirdPartyId, thirdPartyName, collectiveAccountId, employeeNumber, position,
 department, hireDate, baseSalary, salaryCurrency, contractType, bankAccountNumber,
 BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
 null, null, Boolean.TRUE);
 }

 /** Rétro-compatibilité — ancien constructeur 15-args (sans overtimeHours100/cnss/ofatma/13e mois). */
 public CreateEmployeeRequest(
 UUID thirdPartyId,
 String thirdPartyName,
 UUID collectiveAccountId,
 @NotBlank String employeeNumber,
 String position,
 String department,
 @NotNull LocalDate hireDate,
 @NotNull @Positive BigDecimal baseSalary,
 String salaryCurrency,
 @NotNull ContractType contractType,
 String bankAccountNumber,
 @PositiveOrZero BigDecimal overtimeHours25,
 @PositiveOrZero BigDecimal overtimeHours50,
 @PositiveOrZero BigDecimal absenceDays,
 @PositiveOrZero BigDecimal paidLeaveDays
 ) {
 this(thirdPartyId, thirdPartyName, collectiveAccountId, employeeNumber, position,
 department, hireDate, baseSalary, salaryCurrency, contractType, bankAccountNumber,
 overtimeHours25, overtimeHours50, BigDecimal.ZERO, absenceDays, paidLeaveDays,
 null, null, Boolean.TRUE);
 }
}
