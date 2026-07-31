package jo.accountant.employees.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.entity.EmployeeStatus;

public record EmployeeResponse(
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
) {}
