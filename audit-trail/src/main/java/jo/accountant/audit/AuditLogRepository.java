package jo.accountant.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("select count(a) from AuditLog a where a.companyId = :companyId and a.entityType = :entityType and a.entityId = :entityId")
    long countForEntity(@Param("companyId") UUID companyId,
                       @Param("entityType") String entityType,
                       @Param("entityId") UUID entityId);

    /**
     * Charge toutes les lignes d'audit d'une entreprise, triées par {@code occurredAt} desc.
     *
     * <p><b>R-09 (lot-C-perf-devops) — @Deprecated</b> : cette méthode charge TOUTES les
     * lignes d'audit en mémoire. Sur 100M+ de lignes d'audit (charge prévue en production),
     * cela provoque un OOM certain. Utiliser
     * {@link #findByCompanyIdWithFilters(UUID, String, UUID, Instant, Instant, Pageable)} à la
     * place, qui pagine et filtre côté DB.
     *
     * <p>Conservée pour compatibilité avec d'éventuels callers existants — sera supprimée en v4.9.
     */
    @Deprecated
    @org.springframework.data.jpa.repository.Query("select a from AuditLog a where a.companyId = :companyId order by a.occurredAt desc")
    java.util.List<AuditLog> findByCompanyIdOrderByOccurredAtDesc(@Param("companyId") UUID companyId);

    /**
     * R-09 (lot-C-perf-devops) — Liste paginée et filtrée des logs d'audit.
     *
     * <p>Tous les filtres sont appliqués côté DB via JPQL — l'application ne charge que la
     * page demandée. Le tri est toujours {@code occurredAt DESC}.
     *
     * <p>Les paramètres {@code entityType}, {@code actorUserId}, {@code from}, {@code to}
     * sont optionnels (null = pas de filtre). Cette approche évite Spring Data JPA
     * Specifications (qui auraient nécessité une interface JpaSpecificationExecutor) tout en
     * restant lisible et performante (Hibernate cache le plan JPQL).
     *
     * <p><b>V8.3 — Correction du pattern {@code :param is null OR col = :param}</b> :
     * ce pattern pose problème avec Hibernate 6 + PostgreSQL : quand {@code :param} est null,
     * Hibernate ne peut pas inférer le type du paramètre pour la seconde branche
     * {@code col = :param} et lève une {@code PSQLException} (« could not determine data type
     * of parameter ») → HTTP 500 côté API (cf. log fff.txt — AuditLogFragment API error 500).
     *
     * <p>La correction utilise {@code COALESCE(:param, col) = col} qui est sémantiquement
     * équivalent mais ne soumet jamais {@code null} à l'opérateur {@code =}, évitant ainsi
     * l'erreur de typage PostgreSQL. Cette forme est également supportée par H2 (tests) et
     * MySQL/MariaDB.
     *
     * @param companyId  identifiant de l'entreprise (sécurité multi-tenant — obligatoire)
     * @param entityType type d'entité filtré (ex. "SalesInvoice", "SecurityEvent"), null = tous
     * @param actorUserId utilisateur ayant déclenché l'événement, null = tous
     * @param from       date de début (inclusive), null = pas de borne basse
     * @param to         date de fin (inclusive), null = pas de borne haute
     * @param pageable   pagination + tri (le tri par occurredAt DESC est appliqué par le caller
     *                   via {@code Pageable.ofSize(...).withSort(Sort.by(...))})
     * @return page de logs d'audit
     */
    @Query("select a from AuditLog a where a.companyId = :companyId " +
           "and (COALESCE(:entityType, a.entityType) = a.entityType) " +
           "and (COALESCE(:actorUserId, a.actorUserId) = a.actorUserId) " +
           "and (COALESCE(:from, a.occurredAt) <= a.occurredAt) " +
           "and (COALESCE(:to, a.occurredAt) >= a.occurredAt) " +
           "order by a.occurredAt desc")
    Page<AuditLog> findByCompanyIdWithFilters(@Param("companyId") UUID companyId,
                                               @Param("entityType") String entityType,
                                               @Param("actorUserId") UUID actorUserId,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to,
                                               Pageable pageable);
}
