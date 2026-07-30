package jo.accountant.timebilling.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.currency.CurrencyRoundingService;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.timebilling.dto.BillableRateResponse;
import jo.accountant.timebilling.dto.CreateBillableRateRequest;
import jo.accountant.timebilling.dto.CreateProjectRequest;
import jo.accountant.timebilling.dto.CreateTimesheetEntryRequest;
import jo.accountant.timebilling.dto.ProjectResponse;
import jo.accountant.timebilling.dto.TimesheetEntryResponse;
import jo.accountant.timebilling.dto.UnbilledWip;
import jo.accountant.timebilling.dto.UtilizationLine;
import jo.accountant.timebilling.entity.BillableRate;
import jo.accountant.timebilling.entity.BillingType;
import jo.accountant.timebilling.entity.Project;
import jo.accountant.timebilling.entity.ProjectStatus;
import jo.accountant.timebilling.entity.TimesheetEntry;
import jo.accountant.timebilling.event.ProjectCreatedEvent;
import jo.accountant.timebilling.event.TimesheetEntryApprovedEvent;
import jo.accountant.timebilling.repository.BillableRateRepository;
import jo.accountant.timebilling.repository.ProjectRepository;
import jo.accountant.timebilling.repository.TimesheetEntryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de suivi du temps et facturation (§13 Phase 10).
 *
 * <p>Règles métier :
 * <ol>
 *   <li>Seules les entrées {@code approved=true} ET {@code billable=true} sont facturables.</li>
 *   <li>Le temps non facturé s'accumule comme WIP — pas d'écriture comptable tant que non
 *       facturé (sauf option revenue recognition, désactivée par défaut).</li>
 *   <li>Une entrée déjà {@code invoiced=true} ne peut pas être réutilisée (idempotence métier).</li>
 * </ol>
 */
@Service
public class TimeBillingService {

    private final ProjectRepository projectRepository;
    private final TimesheetEntryRepository entryRepository;
    private final BillableRateRepository rateRepository;
    private final CurrencyRoundingService roundingService;
    private final ApplicationEventPublisher events;

    public TimeBillingService(ProjectRepository projectRepository,
                              TimesheetEntryRepository entryRepository,
                              BillableRateRepository rateRepository,
                              CurrencyRoundingService roundingService,
                              ApplicationEventPublisher events) {
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.rateRepository = rateRepository;
        this.roundingService = roundingService;
        this.events = events;
    }

    // --- Projets ---

    @Transactional
    public ProjectResponse createProject(UUID companyId, CreateProjectRequest req) {
        if (req.code() == null || req.code().isBlank()) {
            throw new ValidationException("PROJECT_CODE_REQUIRED", "Le code du projet est requis");
        }
        if (projectRepository.findByCompanyIdAndCode(companyId, req.code().trim()).isPresent()) {
            throw new ConflictException("PROJECT_CODE_EXISTS",
                "Un projet avec le code '" + req.code() + "' existe déjà");
        }
        Project p = new Project();
        p.setCompanyId(companyId);
        p.setCode(req.code().trim());
        p.setLabel(req.label().trim());
        p.setClientThirdPartyId(req.clientThirdPartyId());
        p.setBillingType(req.billingType());
        p.setStatus(ProjectStatus.ACTIVE);
        Project saved = projectRepository.save(p);
        events.publishEvent(new ProjectCreatedEvent(saved, TenantContext.getUserId()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects(UUID companyId) {
        return projectRepository.findByCompanyIdOrderByCode(companyId).stream()
            .map(TimeBillingService::toResponse).toList();
    }

    // --- Tarifs ---

    @Transactional
    public BillableRateResponse createBillableRate(UUID companyId, CreateBillableRateRequest req) {
        BillableRate rate = new BillableRate();
        rate.setCompanyId(companyId);
        rate.setProjectId(req.projectId());
        rate.setResourceUserId(req.resourceUserId());
        rate.setHourlyRate(req.hourlyRate());
        rate.setCurrency(req.currency().toUpperCase());
        BillableRate saved = rateRepository.save(rate);
        return toResponse(saved);
    }

    /**
     * Résout le taux applicable pour un couple (projet, ressource) — du plus spécifique
     * au moins spécifique.
     */
    @Transactional(readOnly = true)
    public Optional<BillableRate> resolveRate(UUID companyId, UUID projectId, UUID resourceUserId) {
        // 1. Niveau projet + ressource (le plus spécifique)
        Optional<BillableRate> rate = rateRepository
            .findByCompanyIdAndProjectIdAndResourceUserId(companyId, projectId, resourceUserId);
        if (rate.isPresent()) return rate;

        // 2. Niveau projet
        rate = rateRepository.findByCompanyIdAndProjectIdAndResourceUserIdIsNull(companyId, projectId);
        if (rate.isPresent()) return rate;

        // 3. Niveau ressource
        rate = rateRepository.findByCompanyIdAndProjectIdIsNullAndResourceUserId(companyId, resourceUserId);
        if (rate.isPresent()) return rate;

        // 4. Niveau entreprise (défaut)
        return rateRepository.findByCompanyIdAndProjectIdIsNullAndResourceUserIdIsNull(companyId);
    }

    // --- Entrées de temps ---

    @Transactional
    public TimesheetEntryResponse createTimesheetEntry(UUID companyId, CreateTimesheetEntryRequest req) {
        Project project = loadProject(companyId, req.projectId());
        if (project.getStatus() == ProjectStatus.CLOSED) {
            throw new ConflictException("PROJECT_CLOSED",
                "Le projet " + project.getCode() + " est clôturé — saisie impossible");
        }

        TimesheetEntry entry = new TimesheetEntry();
        entry.setCompanyId(companyId);
        entry.setProjectId(project.getId());
        entry.setResourceUserId(req.resourceUserId());
        entry.setEntryDate(req.entryDate());
        entry.setHours(req.hours());
        entry.setBillable(req.billable());
        entry.setApproved(false);
        entry.setInvoiced(false);
        entry.setDescription(req.description());
        TimesheetEntry saved = entryRepository.save(entry);
        return toResponse(saved);
    }

    @Transactional
    public TimesheetEntryResponse approveEntry(UUID companyId, UUID entryId) {
        return approveEntry(companyId, entryId, TenantContext.getUserId());
    }

    /**
     * V7-9 — Approbation d'une entrée de temps avec vérification anti-auto-approbation.
     *
     * <p>Règle des quatre yeux (déontologie cabinet/services) : le consultant qui a saisi
     * l'entrée ne peut pas l'approuver lui-même. Si l'ID de l'approbateur correspond à
     * {@code entry.resourceUserId}, la requête est rejetée avec 403 SELF_APPROVAL_FORBIDDEN.
     *
     * <p>Cette vérification défensive était absente en v6.x — un consultant malveillant ou
     * négligent pouvait auto-approuver ses propres timesheets, ce qui contournait la
     * déontologie des cabinets (PME2 Moïse &amp; Associés).
     *
     * @param companyId  identifiant de l'entreprise
     * @param entryId    identifiant de l'entrée à approuver
     * @param approverId ID de l'utilisateur qui approuve (issu du JWT)
     * @throws jo.accountant.core.exception.ForbiddenException si approverId == entry.resourceUserId
     */
    @Transactional
    public TimesheetEntryResponse approveEntry(UUID companyId, UUID entryId, UUID approverId) {
        TimesheetEntry entry = loadEntry(companyId, entryId);

        // V7-9 — Bloquer l'auto-approbation (règle des quatre yeux).
        if (approverId != null && entry.getResourceUserId() != null
                && approverId.equals(entry.getResourceUserId())) {
            throw new jo.accountant.core.exception.ForbiddenException(
                "SELF_APPROVAL_FORBIDDEN",
                "Un consultant ne peut pas approuver sa propre timesheet (règle des quatre yeux). " +
                "L'approbateur doit être distinct du consultant qui a saisi l'entrée."
            );
        }

        if (entry.isApproved()) {
            throw new ConflictException("ENTRY_ALREADY_APPROVED",
                "L'entrée est déjà approuvée");
        }
        if (entry.isInvoiced()) {
            throw new ConflictException("ENTRY_ALREADY_INVOICED",
                "L'entrée est déjà facturée — approbation impossible");
        }
        entry.setApproved(true);
        TimesheetEntry saved = entryRepository.save(entry);
        events.publishEvent(new TimesheetEntryApprovedEvent(saved, approverId));
        return toResponse(saved);
    }

    // --- WIP (travail en cours) ---

    /**
     * Retourne le WIP d'un projet : entrées approuvées, billables, non facturées,
     * avec le taux applicable et le montant total.
     */
    @Transactional(readOnly = true)
    public UnbilledWip getUnbilled(UUID companyId, UUID projectId) {
        Project project = loadProject(companyId, projectId);
        List<TimesheetEntry> entries = entryRepository
            .findByProjectIdAndApprovedTrueAndBillableTrueAndInvoicedFalseOrderByEntryDate(project.getId());

        BigDecimal totalHours = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<UnbilledWip.UnbilledLine> lines = new ArrayList<>();

        for (TimesheetEntry entry : entries) {
            Optional<BillableRate> resolvedRate = resolveRate(companyId, project.getId(), entry.getResourceUserId());
            BigDecimal hourlyRate = resolvedRate.map(BillableRate::getHourlyRate).orElse(BigDecimal.ZERO);
            // Audit M14 : arrondi currency-aware (au lieu de setScale(4) en dur). La devise est
            // celle du BillableRate appliquable, ou HTG par défaut si aucun taux n'est défini.
            String currencyCode = resolvedRate.map(BillableRate::getCurrency).orElse("HTG");
            BigDecimal amount = roundingService.round(currencyCode, entry.getHours().multiply(hourlyRate));

            totalHours = totalHours.add(entry.getHours());
            totalAmount = totalAmount.add(amount);

            lines.add(new UnbilledWip.UnbilledLine(
                entry.getId(), entry.getEntryDate(), entry.getResourceUserId(),
                entry.getHours(), hourlyRate, amount));
        }

        return new UnbilledWip(project.getId(), project.getCode(), project.getLabel(),
            totalHours, totalAmount, lines);
    }

    // --- Helpers ---

    private Project loadProject(UUID companyId, UUID projectId) {
        Project p = projectRepository.findById(projectId)
            .orElseThrow(() -> new NotFoundException("Project", projectId));
        if (!p.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Project", projectId);
        }
        return p;
    }

    private TimesheetEntry loadEntry(UUID companyId, UUID entryId) {
        TimesheetEntry e = entryRepository.findById(entryId)
            .orElseThrow(() -> new NotFoundException("TimesheetEntry", entryId));
        if (!e.getCompanyId().equals(companyId)) {
            throw new NotFoundException("TimesheetEntry", entryId);
        }
        return e;
    }

    private static ProjectResponse toResponse(Project p) {
        return new ProjectResponse(p.getId(), p.getCompanyId(), p.getClientThirdPartyId(),
            p.getCode(), p.getLabel(), p.getStatus(), p.getBillingType(),
            p.getCreatedAt(), p.getUpdatedAt());
    }

    private static TimesheetEntryResponse toResponse(TimesheetEntry e) {
        return new TimesheetEntryResponse(e.getId(), e.getCompanyId(), e.getProjectId(),
            e.getResourceUserId(), e.getEntryDate(), e.getHours(), e.isBillable(),
            e.isApproved(), e.isInvoiced(), e.getDescription(),
            e.getCreatedAt(), e.getUpdatedAt());
    }

    private static BillableRateResponse toResponse(BillableRate r) {
        return new BillableRateResponse(r.getId(), r.getCompanyId(), r.getProjectId(),
            r.getResourceUserId(), r.getHourlyRate(), r.getCurrency(), r.getCreatedAt());
    }

    // --- Agrégation taux d'utilisation (Part E3) ---

    /**
     * Agrège le taux d'utilisation des consultants par projet sur une période (Part E3).
     *
     * <p>Une ligne par couple (projet, consultant) ayant au moins une entrée de temps sur la
     * période. Les heures sont ventilées en :
     * <ul>
     *   <li>{@code hoursLogged} — toutes les heures saisies sur la période (toutes entrées).</li>
     *   <li>{@code hoursBilled} — heures des entrées {@code billable=true},
     *       {@code approved=true}, {@code invoiced=true} (facturées au client).</li>
     *   <li>{@code hoursUnbilled} — heures des entrées {@code billable=true},
     *       {@code approved=true}, {@code invoiced=false} (WIP — facturable mais pas
     *       encore facturé).</li>
     * </ul>
     *
     * <p>Le {@code utilizationRate} (%) = (hoursBilled + hoursUnbilled) / hoursLogged × 100
     * (part des heures facturables — qu'elles soient déjà facturées ou en WIP — parmi toutes
     * les heures saisies). 0 si {@code hoursLogged = 0}.
     *
     * <p>Si {@code from} est null, borne inférieure = 1900-01-01. Si {@code to} est null,
     * borne supérieure = aujourd'hui.
     *
     * <p>Le {@code consultant} retourné est le {@code resourceUserId} sous forme de chaîne —
     * la résolution en nom affichable se fait côté client (voir javadoc de
     * {@link UtilizationLine}).
     */
    @Transactional(readOnly = true)
    public List<UtilizationLine> getUtilization(UUID companyId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.of(1900, 1, 1);
        LocalDate end = to != null ? to : LocalDate.now();

        // Indexer les projets de l'entreprise (tous, même sans entrée) par ID.
        Map<UUID, Project> projectById = new HashMap<>();
        for (Project p : projectRepository.findByCompanyIdOrderByCode(companyId)) {
            projectById.put(p.getId(), p);
        }

        // Agréger par (projectId, resourceUserId) : heures logged / billed / unbilled.
        // Clé composite = projectId + "|" + resourceUserId.
        Map<String, UtilizationAccumulator> acc = new HashMap<>();
        for (TimesheetEntry e : entryRepository
                .findByCompanyIdAndEntryDateBetweenOrderByEntryDateDesc(companyId, start, end)) {
            String key = e.getProjectId() + "|" + e.getResourceUserId();
            UtilizationAccumulator a = acc.computeIfAbsent(key,
                k -> new UtilizationAccumulator(e.getProjectId(), e.getResourceUserId()));
            a.hoursLogged = a.hoursLogged.add(e.getHours());
            // Entrée facturable ET approuvée = entre dans le "pot facturable" (billed ou unbilled).
            if (e.isBillable() && e.isApproved()) {
                if (e.isInvoiced()) {
                    a.hoursBilled = a.hoursBilled.add(e.getHours());
                } else {
                    a.hoursUnbilled = a.hoursUnbilled.add(e.getHours());
                }
            }
        }

        List<UtilizationLine> rows = new ArrayList<>();
        for (UtilizationAccumulator a : acc.values()) {
            Project p = projectById.get(a.projectId);
            String projectCode = p != null ? p.getCode() : "";
            String projectLabel = p != null ? p.getLabel() : "";
            BigDecimal billableHours = a.hoursBilled.add(a.hoursUnbilled);
            BigDecimal utilizationRate = a.hoursLogged.compareTo(BigDecimal.ZERO) > 0
                ? billableHours.multiply(BigDecimal.valueOf(100))
                    .divide(a.hoursLogged, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            rows.add(new UtilizationLine(
                a.projectId, projectCode, projectLabel,
                a.resourceUserId,
                a.resourceUserId != null ? a.resourceUserId.toString() : "",
                a.hoursLogged, a.hoursBilled, a.hoursUnbilled, utilizationRate));
        }
        return rows;
    }

    /** Accumulateur temporaire pour l'agrégation (projet, consultant). */
    private static final class UtilizationAccumulator {
        private final UUID projectId;
        private final UUID resourceUserId;
        private BigDecimal hoursLogged = BigDecimal.ZERO;
        private BigDecimal hoursBilled = BigDecimal.ZERO;
        private BigDecimal hoursUnbilled = BigDecimal.ZERO;

        UtilizationAccumulator(UUID projectId, UUID resourceUserId) {
            this.projectId = projectId;
            this.resourceUserId = resourceUserId;
        }
    }
}
