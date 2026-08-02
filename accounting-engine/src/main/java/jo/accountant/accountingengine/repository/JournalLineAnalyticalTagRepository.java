package jo.accountant.accountingengine.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalLineAnalyticalTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des tags analytiques des lignes d'écriture (déjà existant, étendu pour 2.6).
 *
 * <p>Ajout de méthodes pour filtrer les lignes POSTED par tag analytique —
 * utilisé par :funds-grants pour le calcul exact des fonds dédiés (Vague 2, item 2.6).
 
 *
 * @author jo@Dev


*/
public interface JournalLineAnalyticalTagRepository
    extends JpaRepository<JournalLineAnalyticalTag, UUID> {

    /** Tous les tags d'une ligne d'écriture. */
    List<JournalLineAnalyticalTag> findByJournalLineId(UUID journalLineId);

    /** Supprime tous les tags d'une ligne (utilisé en cas de re-tag). */
    void deleteByJournalLineId(UUID journalLineId);

    /**
     * Trouve tous les IDs de JournalLine qui portent un tag analytique pour une valeur donnée
     * (Vague 2, item 2.6 — calcul exact des fonds dédiés).
     *
     * <p>Permet à :funds-grants de calculer les charges réellement consommées par un fonds :
     * on récupère les JournalLine taguées avec l'analyticalValueId du grant, puis on somme
     * leurs débits (charges) et crédits (produits).
     *
     * @param companyId identifiant du tenant
     * @param analyticalValueId ID de la valeur analytique du fonds
     * @return liste des IDs de JournalLine taguées avec cette valeur
     */
    @Query("select t.journalLineId from JournalLineAnalyticalTag t " +
           "where t.companyId = :companyId and t.valueId = :analyticalValueId")
    List<UUID> findJournalLineIdsByAnalyticalValueId(
        @Param("companyId") UUID companyId,
        @Param("analyticalValueId") UUID analyticalValueId);
}
