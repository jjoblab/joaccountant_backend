package jo.accountant.employees.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.entity.EmployeeStatus;

/**
 * Réponse d'un employé.
 *
 * <p><b>(fix mobile 2026-07-26)</b> : ajout des champs d'affichage {@code firstName},
 * {@code lastName}, {@code email}, {@code jobTitle} — le mobile les attendait mais
 * l'API ne renvoyait que {@code thirdPartyName} et {@code position}.
 *
 * <p>Mapping :
 * <ul>
 *   <li>{@code firstName} = premier mot de {@code thirdPartyName} ;</li>
 *   <li>{@code lastName} = reste de {@code thirdPartyName} (sans le premier mot) ;</li>
 *   <li>{@code email} = {@code ThirdParty.email} (le tiers EMPLOYEE porte l'email) ;</li>
 *   <li>{@code jobTitle} = {@code Employee.position} (alias sémantique pour l'UI mobile).</li>
 * </ul>
 *
 * @author jo@Dev


*/
public record EmployeeResponse(
    UUID id,
    UUID companyId,
    UUID thirdPartyId,
    String thirdPartyName,
    String firstName,
    String lastName,
    String email,
    String jobTitle,
    String employeeNumber,
    String position,
    String department,
    LocalDate hireDate,
    LocalDate terminationDate,
    String terminationReason,
    BigDecimal baseSalary,
    String salaryCurrency,
    ContractType contractType,
    EmployeeStatus status,
    String bankAccountNumber,
    BigDecimal overtimeHours25,
    BigDecimal overtimeHours50,
    BigDecimal overtimeHours100,
    BigDecimal absenceDays,
    BigDecimal paidLeaveDays,
    String cnssNumber,
    String ofatmaSectorCode,
    Boolean thirteenthMonthEligible,
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Rétro-compatibilité — ancien constructeur sans les champs d'affichage
     * ({@code firstName}, {@code lastName}, {@code email}, {@code jobTitle}). Mis à null.
     */
    public EmployeeResponse(
        UUID id,
        UUID companyId,
        UUID thirdPartyId,
        String thirdPartyName,
        String employeeNumber,
        String position,
        String department,
        LocalDate hireDate,
        LocalDate terminationDate,
        String terminationReason,
        BigDecimal baseSalary,
        String salaryCurrency,
        ContractType contractType,
        EmployeeStatus status,
        String bankAccountNumber,
        BigDecimal overtimeHours25,
        BigDecimal overtimeHours50,
        BigDecimal overtimeHours100,
        BigDecimal absenceDays,
        BigDecimal paidLeaveDays,
        String cnssNumber,
        String ofatmaSectorCode,
        Boolean thirteenthMonthEligible,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, companyId, thirdPartyId, thirdPartyName,
            null, null, null, null,
            employeeNumber, position, department, hireDate, terminationDate, terminationReason,
            baseSalary, salaryCurrency, contractType, status, bankAccountNumber,
            overtimeHours25, overtimeHours50, overtimeHours100,
            absenceDays, paidLeaveDays, cnssNumber, ofatmaSectorCode, thirteenthMonthEligible,
            createdAt, updatedAt);
    }
}
