package jo.accountant.employees.dto;

import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import jo.accountant.employees.entity.ContractType;

/**
 * Corps de requête pour {@code PATCH .../employees/{employeeId}}.
 *
 * <p>Sémantique <strong>PATCH</strong> : seuls les champs non-nuls sont mis à jour.
 * Les champs non fournis (ou explicitement {@code null}) sont ignorés — la valeur
 * existante est préservée.
 *
 * <p>Le {@code employeeNumber}, {@code hireDate}, {@code thirdPartyId}, {@code status},
 * {@code terminationDate} et {@code terminationReason} ne sont PAS modifiables via ce
 * endpoint :
 * <ul>
 *   <li>{@code employeeNumber} est unique par entreprise (UC) — sa modification nécessite
 *       une logique de réconciliation des écritures et documents associés ;</li>
 *   <li>{@code hireDate} est historique (déterminé à l'embauche) ;</li>
 *   <li>{@code thirdPartyId} est structurel (lien vers le tiers EMPLOYEE) ;</li>
 *   <li>{@code status} / {@code terminationDate} / {@code terminationReason} sont gérés
 *       via le endpoint dédié {@code POST /employees/{id}/status}.</li>
 * </ul>
 *
 * @param position libellé du poste (nullable = pas de modification)
 * @param department département (nullable = pas de modification)
 * @param baseSalary salaire de base (nullable = pas de modification ; doit être > 0 si fourni)
 * @param salaryCurrency code ISO 4217 (nullable = pas de modification)
 * @param contractType PERMANENT / FIXED_TERM / CONSULTANT (nullable = pas de modification)
 * @param bankAccountNumber numéro de compte bancaire (nullable = pas de modification)
 * @param cnssNumber matricule CNSS Haïti (nullable = pas de modification)
 * @param ofatmaSectorCode code secteur OFATMA (nullable = pas de modification)
 * @param thirteenthMonthEligible éligibilité 13ᵉ mois (nullable = pas de modification)
 * @param overtimeHours25 HS +25% (nullable = pas de modification)
 * @param overtimeHours50 HS +50% (nullable = pas de modification)
 * @param overtimeHours100 HS +100% Haïti (nullable = pas de modification)
 * @param absenceDays jours d'absence (nullable = pas de modification)
 * @param paidLeaveDays jours de congés payés (nullable = pas de modification)
 *
 * @author jo@Dev


*/
public record UpdateEmployeeRequest(
    String position,
    String department,
    @PositiveOrZero BigDecimal baseSalary,
    String salaryCurrency,
    ContractType contractType,
    String bankAccountNumber,
    String cnssNumber,
    String ofatmaSectorCode,
    Boolean thirteenthMonthEligible,
    @PositiveOrZero BigDecimal overtimeHours25,
    @PositiveOrZero BigDecimal overtimeHours50,
    @PositiveOrZero BigDecimal overtimeHours100,
    @PositiveOrZero BigDecimal absenceDays,
    @PositiveOrZero BigDecimal paidLeaveDays
) {
}
