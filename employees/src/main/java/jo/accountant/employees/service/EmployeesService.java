package jo.accountant.employees.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.employees.dto.CreateEmployeeRequest;
import jo.accountant.employees.dto.EmployeeResponse;
import jo.accountant.employees.dto.UpdateEmployeeRequest;
import jo.accountant.employees.dto.UpdateEmployeeStatusRequest;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 *
 * <p><b>(fix mobile 2026-07-26)</b> : ajout des méthodes {@link #updateEmployee} (PATCH),
 * {@link #deleteEmployee} (soft-delete) et variante paginée {@link #list(UUID, EmployeeStatus, Pageable)}.
 * La méthode {@link #changeStatus(UUID, UUID, UpdateEmployeeStatusRequest)} accepte désormais
 * un DTO body (au lieu d'un {@code @RequestParam}) — fix du bug 400 "Required request param
 * 'status' is not present" lorsque le mobile envoyait un body JSON.
 *
 * @author jo@Dev


*/
@Service
public class EmployeesService {

 private static final Logger LOG = LoggerFactory.getLogger(EmployeesService.class);
 /**
 * Hard cap pour list employees — empêche l'OOM#5). Plus élevé que pour
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
 //HS +100% Haïti (>56h, dimanches, jours fériés) + matricule CNSS/OFATMA
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
 //hard cap 500 pour empêcher l'OOM. La liste d'employés est
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

 /**
 * Variante paginée de {@link #list(UUID, EmployeeStatus)} — pour le mobile qui charge
 * les employés par page (50 par défaut). Évite de charger toute la liste en mémoire.
 *
 * @param companyId identifiant du tenant
 * @param statusFilter filtre optionnel par statut (null = tous statuts)
 * @param pageable pagination (page, size, sort)
 * @return page d'EmployeeResponse avec champs d'affichage résolus
 */
 @Transactional(readOnly = true)
 public Page<EmployeeResponse> list(UUID companyId, EmployeeStatus statusFilter, Pageable pageable) {
 Page<Employee> page = (statusFilter != null)
 ? employeeRepository.findByCompanyIdAndStatus(companyId, statusFilter, pageable)
 : employeeRepository.findByCompanyId(companyId, pageable);
 return page.map(e -> loadResponse(companyId, e.getId()));
 }

 @Transactional(readOnly = true)
 public EmployeeResponse get(UUID companyId, UUID employeeId) {
 return loadResponse(companyId, employeeId);
 }

 // --- Mise à jour statut (ON_LEAVE / TERMINATED) ---

 /**
 * Change le statut d'un employé — accepte un DTO body (fix mobile 2026-07-26).
 *
 * <p>Si {@code status=TERMINATED} et {@code terminationDate} non fournie, la date du jour
 * est utilisée par défaut. Le {@code terminationReason} est persisté sur l'employé
 * (auparavant ignoré — bug).
 *
 * @param companyId identifiant du tenant
 * @param employeeId identifiant de l'employé
 * @param req DTO body contenant {@code status}, {@code terminationDate}, {@code terminationReason}
 * @return l'employé mis à jour
 */
 @Transactional
 public EmployeeResponse changeStatus(UUID companyId, UUID employeeId, UpdateEmployeeStatusRequest req) {
 if (req == null || req.status() == null || req.status().isBlank()) {
 throw new ValidationException("STATUS_REQUIRED", "Le statut est requis dans le body");
 }
 EmployeeStatus newStatus;
 try {
 newStatus = EmployeeStatus.valueOf(req.status().trim().toUpperCase());
 } catch (IllegalArgumentException e) {
 throw new ValidationException("INVALID_STATUS",
 "Statut invalide : " + req.status() + " (valeurs attendues : ACTIVE, ON_LEAVE, TERMINATED)");
 }

 Employee emp = loadEmployee(companyId, employeeId);
 if (newStatus == EmployeeStatus.TERMINATED) {
 // Date de fin de contrat : explicite (DTO) ou date du jour
 if (req.terminationDate() != null && !req.terminationDate().isBlank()) {
 try {
 emp.setTerminationDate(LocalDate.parse(req.terminationDate().trim()));
 } catch (java.time.format.DateTimeParseException e) {
 throw new ValidationException("INVALID_TERMINATION_DATE",
 "terminationDate doit être au format ISO yyyy-MM-dd (ex: 2026-09-30)");
 }
 } else if (emp.getTerminationDate() == null) {
 emp.setTerminationDate(LocalDate.now());
 }
 // Motif de fin de contrat (précédemment ignoré — bug fix)
 if (req.terminationReason() != null && !req.terminationReason().isBlank()) {
 emp.setTerminationReason(req.terminationReason().trim());
 }
 }
 emp.setStatus(newStatus);
 employeeRepository.save(emp);
 LOG.info("Statut employé mis à jour : id={} newStatus={} reason={}",
 emp.getId(), newStatus, emp.getTerminationReason());
 return loadResponse(companyId, emp.getId());
 }

 /**
 * Variante legacy acceptant directement l'enum {@link EmployeeStatus} — conservée pour
 * les callers internes et les tests existants (n'utilisent pas le DTO body).
 *
 * @param companyId identifiant du tenant
 * @param employeeId identifiant de l'employé
 * @param newStatus nouveau statut
 * @return l'employé mis à jour
 */
 @Transactional
 public EmployeeResponse changeStatus(UUID companyId, UUID employeeId, EmployeeStatus newStatus) {
 return changeStatus(companyId, employeeId,
 new UpdateEmployeeStatusRequest(newStatus.name(), null, null));
 }

 // --- Mise à jour partielle (PATCH) ---

 /**
 * Met à jour un employé — sémantique PATCH : seuls les champs non-nuls de {@code req}
 * sont appliqués. Les champs à {@code null} sont ignorés.
 *
 * <p>Le {@code employeeNumber}, {@code hireDate}, {@code thirdPartyId} et {@code status}
 * ne sont PAS modifiables via ce endpoint (cf. {@link UpdateEmployeeRequest}).
 *
 * @param companyId identifiant du tenant
 * @param employeeId identifiant de l'employé
 * @param req corps de la requête PATCH
 * @return l'employé mis à jour
 */
 @Transactional
 public EmployeeResponse updateEmployee(UUID companyId, UUID employeeId, UpdateEmployeeRequest req) {
 Employee emp = loadEmployee(companyId, employeeId);

 if (req.position() != null) {
 emp.setPosition(req.position().isBlank() ? null : req.position());
 }
 if (req.department() != null) {
 emp.setDepartment(req.department().isBlank() ? null : req.department());
 }
 if (req.baseSalary() != null) {
 if (req.baseSalary().signum() <= 0) {
 throw new ValidationException("INVALID_SALARY",
 "baseSalary doit être > 0 (reçu : " + req.baseSalary() + ")");
 }
 emp.setBaseSalary(req.baseSalary());
 }
 if (req.salaryCurrency() != null) {
 emp.setSalaryCurrency(req.salaryCurrency().isBlank() ? "HTG" : req.salaryCurrency().toUpperCase());
 }
 if (req.contractType() != null) {
 emp.setContractType(req.contractType());
 }
 if (req.bankAccountNumber() != null) {
 emp.setBankAccountNumber(req.bankAccountNumber().isBlank() ? null : req.bankAccountNumber());
 }
 if (req.cnssNumber() != null) {
 emp.setCnssNumber(req.cnssNumber().isBlank() ? null : req.cnssNumber());
 }
 if (req.ofatmaSectorCode() != null) {
 emp.setOfatmaSectorCode(req.ofatmaSectorCode().isBlank() ? null : req.ofatmaSectorCode());
 }
 if (req.thirteenthMonthEligible() != null) {
 emp.setThirteenthMonthEligible(req.thirteenthMonthEligible());
 }
 if (req.overtimeHours25() != null) {
 emp.setOvertimeHours25(req.overtimeHours25());
 }
 if (req.overtimeHours50() != null) {
 emp.setOvertimeHours50(req.overtimeHours50());
 }
 if (req.overtimeHours100() != null) {
 emp.setOvertimeHours100(req.overtimeHours100());
 }
 if (req.absenceDays() != null) {
 emp.setAbsenceDays(req.absenceDays());
 }
 if (req.paidLeaveDays() != null) {
 emp.setPaidLeaveDays(req.paidLeaveDays());
 }
 emp.setUpdatedBy(TenantContext.getUserId());
 Employee saved = employeeRepository.save(emp);
 LOG.info("Employé mis à jour (PATCH) : id={} by={}", saved.getId(), TenantContext.getUserId());
 return loadResponse(companyId, saved.getId());
 }

 // --- Suppression (soft-delete) ---

 /**
 * Supprime un employé — soft-delete en passant le statut à {@link EmployeeStatus#TERMINATED}
 * avec {@code terminationReason="Deleted by user"}.
 *
 * <p>On ne supprime jamais physiquement l'employé car :
 * <ul>
 *   <li>il peut être référencé par des {@code Payslip} historiques (paies déjà versées) ;</li>
 *   <li>il peut être référencé par des écritures comptables (via {@code thirdPartyId}) ;</li>
 *   <li>l'ID a pu être mis en cache côté mobile.</li>
 * </ul>
 *
 * <p>Le soft-delete via {@code TERMINATED} le rend invisible dans la liste des employés
 * actifs (filtre par défaut du mobile) tout en préservant l'historique.
 *
 * @param companyId identifiant du tenant
 * @param employeeId identifiant de l'employé à supprimer
 */
 @Transactional
 public void deleteEmployee(UUID companyId, UUID employeeId) {
 Employee emp = loadEmployee(companyId, employeeId);
 emp.setStatus(EmployeeStatus.TERMINATED);
 if (emp.getTerminationDate() == null) {
 emp.setTerminationDate(LocalDate.now());
 }
 emp.setTerminationReason("Deleted by user");
 emp.setUpdatedBy(TenantContext.getUserId());
 employeeRepository.save(emp);
 LOG.info("Employé supprimé (soft-delete TERMINATED) : id={} number={} by={}",
 employeeId, emp.getEmployeeNumber(), TenantContext.getUserId());
 }

 // --- Helpers ---

 private EmployeeResponse loadResponse(UUID companyId, UUID employeeId) {
 Employee emp = loadEmployee(companyId, employeeId);
 String tpName = "";
 String tpEmail = null;
 try {
 ThirdParty tp = thirdPartyRepository.findById(emp.getThirdPartyId()).orElse(null);
 if (tp != null) {
 tpName = tp.getName();
 tpEmail = tp.getEmail();
 }
 } catch (Exception ignored) { }

 // Découpage du nom en firstName / lastName — premier mot = prénom, reste = nom.
 // Si le nom est composé d'un seul mot, firstName = nom et lastName = null.
 String firstName = null;
 String lastName = null;
 if (tpName != null && !tpName.isBlank()) {
 String trimmed = tpName.trim();
 int spaceIdx = trimmed.indexOf(' ');
 if (spaceIdx > 0) {
 firstName = trimmed.substring(0, spaceIdx);
 lastName = trimmed.substring(spaceIdx + 1).trim();
 } else {
 firstName = trimmed;
 lastName = null;
 }
 }

 // jobTitle = alias sémantique de Employee.position pour l'UI mobile
 String jobTitle = emp.getPosition();

 return new EmployeeResponse(
 emp.getId(), emp.getCompanyId(), emp.getThirdPartyId(), tpName,
 firstName, lastName, tpEmail, jobTitle,
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
