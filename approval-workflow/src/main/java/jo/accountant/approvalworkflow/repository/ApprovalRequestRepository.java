package jo.accountant.approvalworkflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalRequest;
import jo.accountant.approvalworkflow.entity.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des demandes d'approbation.
 
 *
 * @author jo@Dev


*/
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    /** Demande PENDING pour une ressource donnée — utilisé par le consommateur pour vérifier
     * qu'une action n'est pas déjà en attente d'approbation. */
    Optional<ApprovalRequest> findByCompanyIdAndResourceTypeAndResourceIdAndStatus(
        UUID companyId, String resourceType, UUID resourceId, ApprovalStatus status);

    /** Toutes les demandes de l'entreprise, filtrées par statut (null = toutes). */
    List<ApprovalRequest> findByCompanyIdOrderByRequestedAtDesc(UUID companyId);

    /** Demandes filtrées par statut. */
    List<ApprovalRequest> findByCompanyIdAndStatusOrderByRequestedAtDesc(UUID companyId, ApprovalStatus status);

    /**
     * Compte les demandes d'approbation d'une entreprise filtrées par statut.
     *
     * <p>Utilisé par {@code :reporting.ReportingService.getDashboard} pour le KPI
     * {@code pendingApprovals} — équivalent SQL de
     * {@code findByCompanyIdAndStatusOrderByRequestedAtDesc(...).size()} mais sans
     * matérialiser toute la liste côté Java (Part C3 — count optimisé).
     */
    long countByCompanyIdAndStatus(UUID companyId, ApprovalStatus status);
}
