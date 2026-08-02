package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;

/**
 * Builder fluent pour créer des employés démo.
 
 *
 * @author jo@Dev


*/
public class EmployeeBuilder {

    private final Employee employee = new Employee();

    public EmployeeBuilder() {
        employee.setEmployeeNumber("EMP-" + UUID.randomUUID().toString().substring(0, 8));
        employee.setHireDate(LocalDate.of(2020, 1, 1));
        employee.setSalaryCurrency("HTG");
        employee.setContractType(ContractType.PERMANENT);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setBaseSalary(new BigDecimal("25000"));
        employee.setOvertimeHours25(BigDecimal.ZERO);
        employee.setOvertimeHours50(BigDecimal.ZERO);
        employee.setOvertimeHours100(BigDecimal.ZERO);
        employee.setAbsenceDays(BigDecimal.ZERO);
        employee.setPaidLeaveDays(BigDecimal.ZERO);
        employee.setThirteenthMonthEligible(true);
        employee.setOfatmaSectorCode("TRADE");
    }

    public EmployeeBuilder thirdPartyId(UUID id) { employee.setThirdPartyId(id); return this; }
    public EmployeeBuilder employeeNumber(String n) { employee.setEmployeeNumber(n); return this; }
    public EmployeeBuilder position(String p) { employee.setPosition(p); return this; }
    public EmployeeBuilder department(String d) { employee.setDepartment(d); return this; }
    public EmployeeBuilder hireDate(LocalDate d) { employee.setHireDate(d); return this; }
    public EmployeeBuilder baseSalary(BigDecimal s) { employee.setBaseSalary(s); return this; }
    public EmployeeBuilder salaryCurrency(String c) { employee.setSalaryCurrency(c); return this; }
    public EmployeeBuilder contractType(ContractType c) { employee.setContractType(c); return this; }
    public EmployeeBuilder status(EmployeeStatus s) { employee.setStatus(s); return this; }
    public EmployeeBuilder cnssNumber(String n) { employee.setCnssNumber(n); return this; }
    public EmployeeBuilder ofatmaSectorCode(String c) { employee.setOfatmaSectorCode(c); return this; }
    public EmployeeBuilder thirteenthMonthEligible(Boolean e) { employee.setThirteenthMonthEligible(e); return this; }
    public EmployeeBuilder overtimeHours50(BigDecimal h) { employee.setOvertimeHours50(h); return this; }
    public EmployeeBuilder overtimeHours100(BigDecimal h) { employee.setOvertimeHours100(h); return this; }

    public Employee build() {
        if (employee.getThirdPartyId() == null) throw new IllegalStateException("thirdPartyId required");
        return employee;
    }
}
