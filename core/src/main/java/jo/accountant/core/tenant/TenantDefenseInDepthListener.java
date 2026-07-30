package jo.accountant.core.tenant;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Listener JPA de défense en profondeur pour l'isolation multi-tenant
 * (audit v4.7 §5.1 Finding #1 — moyen terme, alternative à @TenantId Hibernate).
 *
 * <p><b>Problème</b> : la v4.7 repose sur la discipline manuelle pour l'isolation multi-tenant.
 * Chaque méthode de repository doit explicitement prendre un {@code companyId} en paramètre et
 * l'inclure dans la clause WHERE. Un seul développeur qui oublie ce paramètre expose les données
 * de tous les tenants (IDOR). L'audit a identifié 50+ occurrences de {@code findById} non scopés.
 *
 * <p><b>Approche @TenantId Hibernate</b> (moyen terme idéal) : annoter {@code TenantAwareEntity.companyId}
 * avec {@code @TenantId} pour qu'Hibernate filtre automatiquement toutes les requêtes. Problème :
 * nécessite une migration complète car les entités cross-tenant (AuditLog, UserCompanyRole,
 * RefreshToken, PasswordResetToken, Notification, BusinessType*) ne doivent PAS avoir
 * {@code @TenantId}. Toutes les requêtes JPQL écrites à la main avec un filtre {@code companyId}
 * explicite entreraient en conflit avec le filtre automatique. Risque de régression élevé.
 *
 * <p><b>Approche alternative (ce listener)</b> : valider au moment du flush que toute entité
 * {@link TenantAwareEntity} a un {@code companyId} non null ET correspondant au
 * {@link TenantContext#getCompanyId()} courant. Empêche :
 * <ul>
 *   <li><b>Insert cross-tenant</b> : un service qui crée une entité sans positionner
 *       {@code companyId} (oubli) — l'entité aurait un companyId null ou celui d'un autre tenant.</li>
 *   <li><b>Update cross-tenant</b> : un service qui modifie le {@code companyId} d'une entité
 *       ( tentative d'exfiltration ou bug) — refusé.</li>
 * </ul>
 *
 * <p><b>Limitations</b> :
 * <ul>
 *   <li>Ne protège PAS contre les requêtes SELECT non scopées (un {@code findById} sans guard
 *       retourne toujours l'entité — il faut le guard applicatif ou ArchUnit Rule 42).</li>
 *   <li>Ne protège PAS contre les requêtes SQL directes (JdbcTemplate native queries).</li>
 *   <li>Pour les batchs hors-requête (crons, migrations), le {@code TenantContext} est vide —
 *       le listener accepte alors un {@code companyId} null (best-effort).</li>
 * </ul>
 *
 * <p><b>Combinaison recommandée</b> : ce listener (défense INSERT/UPDATE) +
 * ArchUnit Rule 42 (défense SELECT via repositories) + guards applicatifs {@code companyId.equals()}
 * après chaque {@code findById} (pattern déjà appliqué dans 50+ services).
 *
 * <p><b>Activation</b> : automatique via {@code @EntityListeners} sur {@link TenantAwareEntity}.
 * Spring détecte le bean et l'injecte dans le cycle de vie JPA.
 */
@Component
public class TenantDefenseInDepthListener {

    private static final Logger LOG = LoggerFactory.getLogger(TenantDefenseInDepthListener.class);

    /**
     * Valide le {@code companyId} avant INSERT.
     *
     * <p>Règles :
     * <ul>
     *   <li>Si {@code TenantContext.getCompanyId()} est positionné (contexte requête HTTP) :
     *       l'entité DOIT avoir un {@code companyId} égal au tenant courant. Sinon :
     *       {@code IllegalStateException} → rollback transaction.</li>
     *   <li>Si {@code TenantContext.getCompanyId()} est null (contexte batch/migration/CI) :
     *       l'entité DOIT avoir un {@code companyId} non null (le service batch doit le positionner
     *       explicitement). Sinon : {@code IllegalStateException}.</li>
     * </ul>
     */
    @PrePersist
    void onPrePersist(Object entity) {
        if (!(entity instanceof TenantAwareEntity tae)) return;
        validateTenantConsistency(tae, "INSERT");
    }

    /**
     * Valide le {@code companyId} avant UPDATE — empêche la modification du {@code companyId}
     * (qui devrait être {@code updatable = false} mais la défense en profondeur vaut mieux).
     */
    @PreUpdate
    void onPreUpdate(Object entity) {
        if (!(entity instanceof TenantAwareEntity tae)) return;
        validateTenantConsistency(tae, "UPDATE");
    }

    /**
     * Validation centrale — appelée par PrePersist et PreUpdate.
     */
    private void validateTenantConsistency(TenantAwareEntity entity, String operation) {
        UUID entityCompanyId = entity.getCompanyId();
        UUID contextCompanyId = TenantContext.getCompanyId();

        if (entityCompanyId == null) {
            // Refus systématique : un TenantAwareEntity sans companyId est un bug applicatif
            // (le service a oublié de positionner le companyId avant save).
            throw new IllegalStateException(
                "TenantDefenseInDepth: " + operation + " refusé — entité " + entity.getClass().getSimpleName()
                + " (id=" + entity.getId() + ") sans companyId. Le service appelant doit positionner "
                + "entity.setCompanyId(TenantContext.getCompanyId()) avant save().");
        }

        if (contextCompanyId != null) {
            // Contexte requête HTTP : le companyId de l'entité DOIT correspondre au tenant courant
            if (!entityCompanyId.equals(contextCompanyId)) {
                throw new IllegalStateException(
                    "TenantDefenseInDepth: " + operation + " refusé — entité " + entity.getClass().getSimpleName()
                    + " (id=" + entity.getId() + ") a companyId=" + entityCompanyId
                    + " mais le TenantContext courant est " + contextCompanyId
                    + ". Tentative d'insert/update cross-tenant bloquée. Si légitime (batch cross-tenant), "
                    + "ne pas positionner TenantContext.setCompanyId() avant l'opération.");
            }
        }
        // Si contextCompanyId == null (batch/migration), on accepte — le batch est responsable de
        // positionner le bon companyId sur chaque entité.

        if (LOG.isDebugEnabled()) {
            LOG.debug("TenantDefenseInDepth: {} OK pour {} (id={}, companyId={})",
                operation, entity.getClass().getSimpleName(), entity.getId(), entityCompanyId);
        }
    }
}
