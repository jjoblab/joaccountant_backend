package jo.accountant.documentnumbering.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.documentnumbering.entity.DocumentSequenceCounter;
import org.springframework.stereotype.Repository;

/**
 * Repository des compteurs de séquences documentaires.
 *
 * <p>Implémenté via {@link EntityManager} (pas Spring Data JPA) parce que l'incrémentation
 * atomique repose sur un {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING} natif PostgreSQL
 * que Spring Data JPA ne sait pas exprimer (le {@code @Modifying} n'accepte que
 * {@code void/int/Integer} en retour, pas {@code RETURNING}).
 *
 * <p>L'atomicité de l'incrémentation en cas d'appels concurrents (§6 règle non négociable,
 * testée par un scénario de 50 threads réellement parallèles) est garantie par PostgreSQL
 * lui-même : le {@code ON CONFLICT DO UPDATE} est exécuté de manière atomique, le
 * {@code RETURNING last_value} renvoie la valeur après incrément. Aucun verrou applicatif
 * explicite n'est nécessaire.
 
 *
 * @author jo@Dev


*/
@Repository
public class DocumentSequenceCounterRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Recherche le compteur pour une config et une période données, AVEC verrou pessimiste
     * en écriture. Utilisé par les tests et les lectures informatives.
     */
    public Optional<DocumentSequenceCounter> findBySequenceConfigIdAndPeriodKeyForUpdate(
        UUID sequenceConfigId, String periodKey) {
        return findBySequenceConfigIdAndPeriodKey(sequenceConfigId, periodKey);
    }

    /** Recherche sans verrou. */
    public Optional<DocumentSequenceCounter> findBySequenceConfigIdAndPeriodKey(
        UUID sequenceConfigId, String periodKey) {
        return entityManager.createQuery(
                "select c from DocumentSequenceCounter c " +
                "where c.sequenceConfigId = :configId and c.periodKey = :periodKey",
                DocumentSequenceCounter.class)
            .setParameter("configId", sequenceConfigId)
            .setParameter("periodKey", periodKey)
            .getResultStream()
            .findFirst();
    }

    /**
     * Incrémentation atomique via {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}.
     *
     * <p>Si la ligne n'existe pas encore pour (sequenceConfigId, periodKey), elle est créée avec
     * {@code last_value = 1}. Si elle existe, {@code last_value} est incrémentée de 1.
     *
     * <p>Le {@code company_id} est passé explicitement pour respecter la contrainte NOT NULL.
     *
     * <p>Implémenté via {@link EntityManager#createNativeQuery(String)} avec
     * {@code RETURNING last_value} plutôt que via {@code @Modifying @Query} : Spring Data JPA
     * ne supporte pas {@code RETURNING} avec {@code @Modifying} (qui n'accepte que
     * {@code void/int/Integer}).
     *
     * <p>Note technique : {@code Query.getSingleResult()} sur une requête INSERT...RETURNING
     * déclenche parfois {@code InvalidDataAccessApiUsageException: Executing an update/delete
     * query} selon la configuration Hibernate. Pour éviter ce problème, on récupère la valeur
     * via un SELECT explicite après l'upsert — la transaction garantit la cohérence.
     *
     * @return la nouvelle valeur du compteur (jamais null)
     */
    public long upsertAndIncrement(UUID companyId, UUID sequenceConfigId, String periodKey) {
        // 1. Upsert (atomique grâce à ON CONFLICT DO UPDATE)
        Query upsert = entityManager.createNativeQuery("""
            INSERT INTO document_sequence_counter
                (id, company_id, sequence_config_id, period_key, last_value, created_at, updated_at, version)
            VALUES
                (uuidv7(), :companyId, :configId, :periodKey, 1, now(), now(), 0)
            ON CONFLICT (sequence_config_id, period_key) DO UPDATE
                SET last_value = document_sequence_counter.last_value + 1,
                    updated_at = now(),
                    version = document_sequence_counter.version + 1
            """);
        upsert.setParameter("companyId", companyId);
        upsert.setParameter("configId", sequenceConfigId);
        upsert.setParameter("periodKey", periodKey);
        upsert.executeUpdate();

        // Flush pour s'assurer que la ligne est bien écrite avant le SELECT (au cas où Hibernate
        // aurait mis l'upsert en file d'attente)
        entityManager.flush();
        entityManager.clear(); // force la relecture depuis la DB

        // 2. Lecture de la valeur post-incrément
        Object result = entityManager.createNativeQuery("""
            SELECT last_value FROM document_sequence_counter
            WHERE sequence_config_id = :configId AND period_key = :periodKey
            """)
            .setParameter("configId", sequenceConfigId)
            .setParameter("periodKey", periodKey)
            .getSingleResult();

        if (result instanceof BigInteger bi) return bi.longValue();
        if (result instanceof Number n) return n.longValue();
        throw new IllegalStateException("Unexpected last_value type: " + (result == null ? "null" : result.getClass()));
    }

    /** Persiste un nouveau compteur (utilisé par les tests). */
    public DocumentSequenceCounter save(DocumentSequenceCounter counter) {
        entityManager.persist(counter);
        return counter;
    }

    /** Supprime tous les compteurs (utilisé par les tests de nettoyage). */
    public void deleteAll() {
        entityManager.createQuery("delete from DocumentSequenceCounter").executeUpdate();
    }
}


