package jo.accountant.employees.service;

import java.util.List;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.employees.dto.CreateEmployeeRequest;
import jo.accountant.employees.dto.EmployeeResponse;
import jo.accountant.employees.entity.ContractType;
import jo.accountant.employees.entity.Employee;
import jo.accountant.employees.entity.EmployeeStatus;
import jo.accountant.employees.repository.EmployeeRepository;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import jo.accountant.thirdparties.service.ThirdPartiesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des employés (module :employees).
 *
 * <p>Le module ne génère **aucune** écriture comptable (comme `:third-parties`). Les
 * écritures de paie sont générées par `:payroll` qui consomme cette entité en lecture.
 *
 * <p>Deux variantes de création :
 * <ul>
 * <li>L'employeur a déjà créé le tiers EMPLOYEE via `:third-parties` — on rattache
 * simplement l'employé au {@code thirdPartyId} existant.</li>
 * <li>L'employeur n'a pas encore créé le tiers — on crée le tiers en même temps que
 * l'employé ({@code createWithThirdParty}, voir {@link #create}).</li>
 * </ul>
 *
 * <p>Le statut `ACTIVE` est le statut par défaut. Le filtre `status=ACTIVE` est utilisé
 * par `:payroll` pour lister les salariés à payer sur une période.
 */
@Service
public class EmployeesService {

 private static final Logger LOG = LoggerFactory.getLogger(EmployeesService.class);
 /**
 * Hard cap pour list employees — empêche l'OOM (audit v4.7 §7.2 #5). Plus élevé que pour
 * les factures car les employés sont moins nombreux par construction (quelques centaines max).
 */
 private static final int EMPLOYEE_LIST_HARD_CAP = 500;

 private final EmployeeRepository employeeRepository;
 private final ThirdPartyRepository thirdPartyRepository;
 private final ThirdPartiesService thirdPartiesService;

 public EmployeesService(EmployeeRepository employeeRepository,
 ThirdPartyRepository thirdPartyRepository,
 ThirdPartiesService thirdPartiesService) {
 this.employeeRepository = employeeRepository;
 this.thirdPartyRepository = thirdPartyRepository;
 this.thirdPartiesService = thirdPartiesService;
 }

 // --- Création ---

 @Transactional
 public EmployeeResponse create(UUID companyId, CreateEmployeeRequest req) {
 if (req.thirdPartyId() == null && req.thirdPartyName() == null) {
 throw new ValidationException("THIRD_PARTY_REQUIRED",
 "Préciser soit thirdPartyId (tiers existant), soit thirdPartyName + " +
 "collectiveAccountId (tiers à créer).");
 }

 // Vérifier l'unicité de l'employeeNumber
 if (employeeRepository.findByCompanyIdAndEmployeeNumber(companyId, req.employeeNumber()).isPresent()) {
 throw new ConflictException("EMPLOYEE_NUMBER_ALREADY_EXISTS",
 "L'employeeNumber '" + req.employeeNumber() + "' existe déjà pour cette entreprise.");
 }

 UUID thirdPartyId;
 if (req.thirdPartyId() != null) {
 // Cas 1 : tiers existant — valider qu'il est de type EMPLOYEE
 ThirdParty tp = thirdPartyRepository.findById(req.thirdPartyId())
 .orElseThrow(() -> new NotFoundException("ThirdParty", req.thirdPartyId()));
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", req.thirdPartyId());
 }
 if (tp.getType() != ThirdPartyType.EMPLOYEE) {
 throw new ValidationException("THIRD_PARTY_NOT_EMPLOYEE",
 "Le tiers " + tp.getName() + " n'est pas un employé (type="
 + tp.getType() + ").");
 }
 thirdPartyId = tp.getId();
 } else {
 // Cas 2 : créer le tiers en même temps que l'employé
 if (req.collectiveAccountId() == null) {
 throw new ValidationException("COLLECTIVE_ACCOUNT_REQUIRED",
 "collectiveAccountId est requis quand thirdPartyName est fourni (le tiers " +
 "doit être rattaché à un compte collectif employés).");
 }
 ThirdPartyResponse tp = thirdPartiesService.createThirdParty(companyId, new CreateThirdPartyRequest(
 ThirdPartyType.EMPLOYEE, req.thirdPartyName(), req.collectiveAccountId(),
 null, null));
 thirdPartyId = tp.id();
 LOG.info("Tiers EMPLOYEE créé en même temps que l'employé : tpId={}", thirdPartyId);
 }

 Employee emp = new Employee();
 emp.setCompanyId(companyId);
 emp.setThirdPartyId(thirdPartyId);
 emp.setEmployeeNumber(req.employeeNumber());
 emp.setPosition(req.position());
 emp.setDepartment(req.department());
 emp.setHireDate(req.hireDate());
 emp.setBaseSalary(req.baseSalary());
 emp.setSalaryCurrency(req.salaryCurrency() != null ? req.salaryCurrency().toUpperCase() : "HTG");
 emp.setContractType(req.contractType());
 emp.setStatus(EmployeeStatus.ACTIVE);
 emp.setBankAccountNumber(req.bankAccountNumber());
 // HS / absences / congés (défaut 0 si null)
 emp.setOvertimeHours25(req.overtimeHours25());
 emp.setOvertimeHours50(req.overtimeHours50());
 // (lot-B) — HS +100% Haïti (>56h, dimanches, jours fériés) + matricule CNSS/OFATMA
 emp.setOvertimeHours100(req.overtimeHours100());
 emp.setCnssNumber(req.cnssNumber());
 emp.setOfatmaSectorCode(req.ofatmaSectorCode());
 emp.setAbsenceDays(req.absenceDays());
 emp.setPaidLeaveDays(req.paidLeaveDays());
 Employee saved = employeeRepository.save(emp);

 LOG.info("Employé créé : id={} number={} contrat={}",
 saved.getId(), saved.getEmployeeNumber(), saved.getContractType());
 return loadResponse(companyId, saved.getId());
 }

 // --- Lecture ---

 @Transactional(readOnly = true)
 public List<EmployeeResponse> list(UUID companyId, EmployeeStatus statusFilter) {
 // Audit v4.7 §7.2 hard cap 500 pour empêcher l'OOM. La liste d'employés est
 // généralement petite (quelques centaines max), mais une entreprise groupe multi-tenant
 // pourrait avoir un volume plus important. Le cap est plus généreux que pour les factures
 // car les employés sont moins nombreux qu'eux par construction.
 List<Employee> emps = (statusFilter != null)
 ? employeeRepository.findByCompanyIdAndStatusOrderByIdAsc(companyId, statusFilter)
 : employeeRepository.findByCompanyIdOrderByHireDateDesc(companyId);
 if (emps.size() > EMPLOYEE_LIST_HARD_CAP) {
 LOG.warn("Employees list truncated for company {} : {} employees found, returning first {} only.",
 companyId, emps.size(), EMPLOYEE_LIST_HARD_CAP);
 emps = emps.subList(0, EMPLOYEE_LIST_HARD_CAP);
 }
 return emps.stream().map(e -> loadResponse(companyId, e.getId())).toList();
 }

 @Transactional(readOnly = true)
 public EmployeeResponse get(UUID companyId, UUID employeeId) {
 return loadResponse(companyId, employeeId);
 }

 // --- Mise à jour statut (ON_LEAVE / TERMINATED) ---

 @Transactional
 public EmployeeResponse changeStatus(UUID companyId, UUID employeeId, EmployeeStatus newStatus) {
 Employee emp = loadEmployee(companyId, employeeId);
 if (newStatus == EmployeeStatus.TERMINATED && emp.getTerminationDate() == null) {
 // Si on termine sans date explicite, on date la termination à aujourd'hui.
 emp.setTerminationDate(java.time.LocalDate.now());
 }
 emp.setStatus(newStatus);
 employeeRepository.save(emp);
 LOG.info("Statut employé mis à jour : id={} newStatus={}", emp.getId(), newStatus);
 return loadResponse(companyId, emp.getId());
 }

 // --- Helpers ---

 private EmployeeResponse loadResponse(UUID companyId, UUID employeeId) {
 Employee emp = loadEmployee(companyId, employeeId);
 String tpName = "";
 try {
 ThirdParty tp = thirdPartyRepository.findById(emp.getThirdPartyId()).orElse(null);
 if (tp != null) tpName = tp.getName();
 } catch (Exception ignored) { }
 return new EmployeeResponse(
 emp.getId(), emp.getCompanyId(), emp.getThirdPartyId(), tpName,
 emp.getEmployeeNumber(), emp.getPosition(), emp.getDepartment(),
 emp.getHireDate(), emp.getTerminationDate(), emp.getTerminationReason(),
 emp.getBaseSalary(),
 emp.getSalaryCurrency(), emp.getContractType(), emp.getStatus(),
 emp.getBankAccountNumber(),
 emp.getOvertimeHours25(), emp.getOvertimeHours50(),
 emp.getOvertimeHours100(),
 emp.getAbsenceDays(), emp.getPaidLeaveDays(),
 emp.getCnssNumber(), emp.getOfatmaSectorCode(),
 emp.getThirteenthMonthEligible(),
 emp.getCreatedAt(), emp.getUpdatedAt());
 }

 private Employee loadEmployee(UUID companyId, UUID employeeId) {
 Employee emp = employeeRepository.findById(employeeId)
 .orElseThrow(() -> new NotFoundException("Employee", employeeId));
 if (!emp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Employee", employeeId);
 }
 return emp;
 }
}
