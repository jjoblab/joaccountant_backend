package jo.accountant.accountingengine.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des écritures comptables.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /** Recherche par idempotence — si une écriture existe déjà pour cette clé, on retourne
     *  l'existante au lieu d'en créer une nouvelle (§3.10). */
    Optional<JournalEntry> findByCompanyIdAndIdempotencyKey(UUID companyId, String idempotencyKey);

    /** Écritures par statut dans une entreprise. */
    List<JournalEntry> findByCompanyIdAndStatusOrderByEntryDateDesc(UUID companyId, JournalEntryStatus status);

    /** Toutes les écritures de l'entreprise, triées par date d'écriture décroissante. */
    List<JournalEntry> findByCompanyIdOrderByEntryDateDesc(UUID companyId);

    /**
     * Écritures de l'entreprise paginées (audit M8).
     * Usage : {@code repository.findByCompanyIdOrderByEntryDateDesc(companyId, PageRequest.of(0, 50));}
     */
    Page<JournalEntry> findByCompanyIdOrderByEntryDateDesc(UUID companyId, Pageable pageable);

    /**
     * Recherche filtrée et paginée des écritures (audit M8 — filtres multi-dimensionnels).
     *
     * <p>Tous les filtres sont optionnels (null = pas de filtre). Combine par AND.
     * Tri par défaut : entryDate DESC.
     *
     * <p>Utilisé par {@code GET /journal-entries/search} qui expose les filtres :
     * {@code ?from=&to=&journalCode=&sourceModule=&status=&page=&size=}.
     *
     * <p>Le filtre {@code journalCode} est résolu via une jointure sur la table Journal
     * (JournalEntry ne stocke que journalId, pas journalCode).
     */
    @Query("""
        SELECT e FROM JournalEntry e, Journal j
        WHERE e.companyId = :companyId
          AND j.companyId = e.companyId
          AND j.id = e.journalId
          AND (:from IS NULL OR e.entryDate >= :from)
          AND (:to IS NULL OR e.entryDate <= :to)
          AND (:journalCode IS NULL OR j.code = :journalCode)
          AND (:sourceModule IS NULL OR e.sourceModule = :sourceModule)
          AND (:status IS NULL OR e.status = :status)
        ORDER BY e.entryDate DESC, e.createdAt DESC
        """)
    Page<JournalEntry> searchEntries(
        @Param("companyId") UUID companyId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to,
        @Param("journalCode") String journalCode,
        @Param("sourceModule") JournalEntrySourceModule sourceModule,
        @Param("status") JournalEntryStatus status,
        Pageable pageable
    );

    /**
     * Compte les écritures par statut dans un ensemble de périodes fiscales.
     *
     * <p><b>Audit v4.7 §3.1 Finding #7</b> — utilisé par {@code closeFiscalYear} pour vérifier
     * qu'il n'y a pas d'écritures DRAFT ou PENDING_APPROVAL avant de clôturer l'exercice.
     * Sans ce check, ces écritures restent bloquées à vie (la période sera LOCKED après clôture).
     */
    long countByCompanyIdAndFiscalPeriodIdInAndStatus(
        @Param("companyId") UUID companyId,
        @Param("fiscalPeriodIds") List<UUID> fiscalPeriodIds,
        @Param("status") JournalEntryStatus status
    );

    /**
     * Keyset pagination (R-41 — lot-F1-code-arch) — page suivante d'écritures après un curseur
     * (afterEntryDate, afterId).
     *
     * <p><b>Problème offset-based</b> : la pagination Spring Data classique utilise OFFSET, ce qui
     * impose à PostgreSQL de scanner séquentiellement toutes les lignes précédentes avant de
     * retourner les {@code size} lignes demandées. Sur une entreprise avec 10M d'écritures,
     * {@code OFFSET 900000 LIMIT 50} = seq scan des 900 000 premières lignes → latence > 5s
     * et charge I/O élevée.
     *
     * <p><b>Keyset pagination</b> : on utilise un curseur stable (entryDate, id) — l'index
     * B-tree {@code idx_je_company_date} couvre déjà (company_id, entry_date), et l'ajout de
     * l'UUID v7 (ordonné dans le temps) en secondaire du ORDER BY fournit un ordre total stable.
     * La clause WHERE utilise une comparaison "row value" {@code (entryDate, id) < (?, ?)} qui
     * peut utiliser l'index directement → latence constante ~1-5ms quelle que soit la profondeur
     * de la page. Sur 10M lignes, c'est 10× à 100× plus rapide que OFFSET sur les pages profondes.
     *
     * <p><b>Contrat</b> :
     * <ul>
     *   <li>Si {@code afterEntryDate} est {@code null} → retourne les premières {@code size}
     *       écritures (page 1).</li>
     *   <li>Sinon → retourne les {@code size} écritures strictement avant le curseur, triées
     *       par (entryDate DESC, id DESC).</li>
     *   <li>{@code Pageable} est utilisé uniquement pour sa propriété {@code pageSize} — l'offset
     *       est ignoré (le keyset le remplace).</li>
     * </ul>
     *
     * <p><b>Backward compat</b> : la méthode OFFSET {@link #findByCompanyIdOrderByEntryDateDesc(UUID, Pageable)}
     * et l'endpoint {@code /journal-entries/paged} sont conservés pour les clients qui ne
     * supportent pas encore le keyset.
     *
     * @see jo.accountant.accountingengine.dto.KeysetPage
     */
    @Query("""
        SELECT e FROM JournalEntry e
        WHERE e.companyId = :companyId
          AND (:afterEntryDate IS NULL OR (e.entryDate, e.id) < (:afterEntryDate, :afterId))
        ORDER BY e.entryDate DESC, e.id DESC
        """)
    List<JournalEntry> findKeysetAfter(
        @Param("companyId") UUID companyId,
        @Param("afterEntryDate") LocalDate afterEntryDate,
        @Param("afterId") UUID afterId,
        Pageable pageable
    );
}
