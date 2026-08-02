package jo.accountant.approvalworkflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.entity.ApprovalRule;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository des règles d'approbation.
 *
 * <p>L'isolation multi-tenant est faite explicitement par le service via {@code companyId}.
 
 *
 * @author jo@Dev


*/
public interface ApprovalRuleRepository extends JpaRepository<ApprovalRule, UUID> {

    /** Règle active pour un actionType donné, dans l'entreprise donnée. */
    Optional<ApprovalRule> findByCompanyIdAndActionTypeAndActiveTrue(UUID companyId, ApprovalActionType actionType);

    /** Toutes les règles de l'entreprise (actives et inactives). */
    List<ApprovalRule> findByCompanyIdOrderByActionTypeAsc(UUID companyId);

    /** True s'il existe déjà une règle active pour ce (companyId, actionType). */
    boolean existsByCompanyIdAndActionTypeAndActiveTrue(UUID companyId, ApprovalActionType actionType);
}
