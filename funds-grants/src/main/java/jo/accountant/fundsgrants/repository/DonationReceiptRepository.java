package jo.accountant.fundsgrants.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.DonationReceipt;
import jo.accountant.fundsgrants.entity.DonationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository JPA DonationReceipt.
 *
 * @author jo@Dev

*/
public interface DonationReceiptRepository extends JpaRepository<DonationReceipt, UUID> {
    List<DonationReceipt> findByCompanyId(UUID companyId);
    List<DonationReceipt> findByGrantId(UUID grantId);

    /**
     * v9.4 fix — Met à jour le type de don (CASH → IN_KIND) sans re-sauvegarder l'entité complète.
     *
     * <p>Évite le bug "Row was updated or deleted by another transaction" qui survenait
     * quand le seeder démo faisait {@code receipt.setDonationType(IN_KIND); save(receipt);}
     * après que {@code FundsGrantsService.createDonationReceipt} ait déjà persisté + flushé
     * l'entité dans sa propre transaction. L'entité retournée était stale (version Hibernate
     * désynchronisée) → le save() lançait {@code StaleObjectStateException}.
     */
    @Modifying
    @Transactional
    @Query("UPDATE DonationReceipt d SET d.donationType = :donationType WHERE d.id = :id")
    void updateDonationType(UUID id, DonationType donationType);
}
