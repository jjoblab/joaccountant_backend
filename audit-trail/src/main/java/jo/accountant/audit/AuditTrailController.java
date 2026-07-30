package jo.accountant.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuditTrailController — expose les logs d'audit en lecture seule (phantom fix).
 *
 * <p>Le module audit-trail était passif (event listener uniquement) — aucun
 * endpoint REST n'existait. Le mobile AuditLogFragment + AuditLogDetailFragment
 * appelaient GET /audit-trail qui 404. Ce controller corrige le phantom.
 *
 * <p><b>R-09 (lot-C-perf-devops) — pagination + filtres</b> : l'ancien endpoint
 * chargeait {@code findByCompanyIdOrderByOccurredAtDesc(companyId)} — toutes les
 * lignes d'audit en mémoire → OOM certain sur 100M+ lignes. Désormais, l'endpoint
 * accepte {@code page}, {@code size}, {@code entityType}, {@code actorUserId},
 * {@code from}, {@code to} et retourne un {@link Page} de {@link AuditLogResponse}.
 * Hard cap sur {@code size} = 200 (au-delà, silencieusement clampé).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/audit-trail")
@Tag(name = "Audit Trail", description = "Journal d'audit forensique (lecture seule)")
public class AuditTrailController {

    /** Hard cap sur la taille de page — protège contre les requêtes malveillantes. */
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 50;

    private final AuditLogRepository repository;
    private final RoleChecker roleChecker;

    public AuditTrailController(AuditLogRepository repository, RoleChecker roleChecker) {
        this.repository = repository;
        this.roleChecker = roleChecker;
    }

    /**
     * Liste paginée et filtrée des logs d'audit (R-09).
     *
     * <p>Tri toujours par {@code occurredAt DESC}. La pagination est 0-indexée.
     * Taille de page hard-cappée à {@value #MAX_PAGE_SIZE}.
     */
    @Operation(summary = "Lister les logs d'audit (paginé)",
        description = "Retourne une page de logs d'audit filtrés par entityType, actorUserId, " +
                      "plage de dates (from/to). Tri par occurredAt descendant. " +
                      "Hard cap sur size = " + MAX_PAGE_SIZE + ".")
    @GetMapping
    public Page<AuditLogResponse> list(@PathVariable UUID companyId,
                                        @CurrentUser UUID userId,
                                        @RequestParam(required = false, defaultValue = "0") int page,
                                        @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                                        @RequestParam(required = false) String entityType,
                                        @RequestParam(required = false) UUID actorUserId,
                                        @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to) {
        roleChecker.ensureRole(companyId, "VIEWER");
        int safeSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<AuditLog> logs = repository.findByCompanyIdWithFilters(
            companyId,
            (entityType != null && !entityType.isBlank()) ? entityType : null,
            actorUserId,
            from,
            to,
            pageable);
        return logs.map(AuditLogResponse::from);
    }

    /**
     * @deprecated ancien endpoint non paginé — conservé pour compatibilité, mais
     * délègue désormais à {@link #list(UUID, UUID, int, int, String, UUID, Instant, Instant)}
     * avec une taille de page = {@value #MAX_PAGE_SIZE}. Ne pas utiliser dans le frontend
     * mobile : préférez l'endpoint paginé. Sera supprimé en v4.9.
     */
    @Deprecated
    @Operation(summary = "[DEPRECATED] Lister tous les logs d'audit (non paginé)",
        description = "DEPRECATED — charge toutes les lignes en mémoire. Utiliser GET /audit-trail " +
                      "avec pagination (page=, size=, entityType=, actorUserId=, from=, to=).")
    @GetMapping("/all")
    public List<AuditLogResponse> listAll(@PathVariable UUID companyId,
                                           @CurrentUser UUID userId,
                                           @RequestParam(required = false) String entityType) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // On garde la signature historique (List) pour compat, mais on délègue à l'API paginée
        // avec la taille max autorisée. Le caller devrait migrer vers la pagination.
        Page<AuditLog> logs = repository.findByCompanyIdWithFilters(
            companyId,
            (entityType != null && !entityType.isBlank()) ? entityType : null,
            null, null, null,
            PageRequest.of(0, MAX_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return logs.stream().map(AuditLogResponse::from).collect(Collectors.toList());
    }

    @Operation(summary = "Récupérer un log d'audit par ID")
    @GetMapping("/{logId}")
    public AuditLogResponse get(@PathVariable UUID companyId,
                                 @PathVariable UUID logId,
                                 @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        AuditLog log = repository.findById(logId)
            .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException("AuditLog", logId));
        if (log.getCompanyId() != null && !log.getCompanyId().equals(companyId)) {
            throw new jo.accountant.core.exception.NotFoundException("AuditLog", logId);
        }
        return AuditLogResponse.from(log);
    }

    /** DTO de réponse — miroir du mobile AuditLogDto. */
    public record AuditLogResponse(
        UUID id,
        UUID companyId,
        UUID actorUserId,
        String entityType,
        UUID entityId,
        String action,
        String oldValueJson,
        String newValueJson,
        Instant occurredAt,
        String correlationId
    ) {
        public static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(
                a.getId(), a.getCompanyId(), a.getActorUserId(),
                a.getEntityType(), a.getEntityId(), a.getAction(),
                a.getOldValueJson(), a.getNewValueJson(),
                a.getOccurredAt(), a.getCorrelationId()
            );
        }
    }
}
