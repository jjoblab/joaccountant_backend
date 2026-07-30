package jo.accountant.documentnumbering.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.dto.NextNumberPreview;
import jo.accountant.documentnumbering.entity.DocumentSequenceConfig;
import jo.accountant.documentnumbering.entity.DocumentSequenceCounter;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.event.NumberIssuedEvent;
import jo.accountant.documentnumbering.event.SequenceConfigCreatedEvent;
import jo.accountant.documentnumbering.repository.DocumentSequenceConfigRepository;
import jo.accountant.documentnumbering.repository.DocumentSequenceCounterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de numérotation documentaire (§6).
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Création / mise à jour des configurations de séquence (une par
 *       (companyId, documentType, scopeKey))</li>
 *   <li>Génération atomique de numéros via verrou pessimiste
 *       ({@link DocumentSequenceCounterRepository#findBySequenceConfigIdAndPeriodKeyForUpdate})</li>
 *   <li>Aperçu non consommateur pour les UI ({@link #previewNextNumber})</li>
 * </ul>
 *
 * <p>Règles métier (§6, chacune testée par un test qui échouerait si la règle était retirée) :
 * <ol>
 *   <li><strong>Atomicité</strong> — deux créations concurrentes ne produisent jamais le même
 *       numéro. Testée par 50 threads réellement parallèles, pas simulés séquentiellement.</li>
 *   <li><strong>Aucune réutilisation</strong> — un document annulé conserve son numéro. Cette
 *       règle s'applique côté consommateurs (Phase 5, 12, 14), pas ici ; ce service ne fait
 *       qu'émettre des numéros strictement croissants.</li>
 *   <li><strong>Format configurable</strong> — prefix + année optionnelle + numéro paddé.</li>
 *   <li><strong>Aperçu non consommateur</strong> — {@link #previewNextNumber} ne touche jamais
 *       au compteur, ne pose aucun verrou.</li>
 * </ol>
 *
 * <p>Rappel (§6) : ce service est appelé par les modules Phase 5 ({@code JournalEntry.post}),
 * 12 ({@code SalesInvoice.issue}), 14 ({@code DonationReceipt.create}) au moment précis de la
 * transition qui rend le document définitif — JAMAIS à l'état brouillon.
 */
@Service
public class DocumentNumberingService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentNumberingService.class);

    private final DocumentSequenceConfigRepository configRepository;
    private final DocumentSequenceCounterRepository counterRepository;
    private final ApplicationEventPublisher events;

    public DocumentNumberingService(DocumentSequenceConfigRepository configRepository,
                                    DocumentSequenceCounterRepository counterRepository,
                                    ApplicationEventPublisher events) {
        this.configRepository = configRepository;
        this.counterRepository = counterRepository;
        this.events = events;
    }

    /**
     * Crée une nouvelle configuration de séquence.
     *
     * @throws ConflictException si une config existe déjà pour le même (documentType, scopeKey)
     *         dans le tenant courant — pas d'édition soft (cf. javadoc de
     *         {@link jo.accountant.documentnumbering.dto.CreateSequenceRequest})
     */
    @Transactional
    public DocumentSequenceConfig createSequence(UUID companyId,
                                                 DocumentType documentType,
                                                 String scopeKey,
                                                 String prefix,
                                                 boolean includeYear,
                                                 int padding,
                                                 ResetPolicy resetPolicy) {
        validateCreateInputs(documentType, scopeKey, prefix, padding, resetPolicy);
        String normalizedScope = normalizeScope(scopeKey);

        if (configRepository.findByCompanyIdAndDocumentTypeAndScopeKey(
                companyId, documentType, normalizedScope).isPresent()) {
            throw new ConflictException("SEQUENCE_CONFIG_ALREADY_EXISTS",
                "A sequence config already exists for documentType=" + documentType
                + " scopeKey='" + normalizedScope + "' in this company");
        }

        DocumentSequenceConfig config = new DocumentSequenceConfig();
        config.setCompanyId(companyId);   // explicite —TenantAwareEntityListener ne fait que compléter si null
        config.setDocumentType(documentType);
        config.setScopeKey(normalizedScope);
        config.setPrefix(prefix.trim().toUpperCase());
        config.setIncludeYear(includeYear);
        config.setPadding(padding);
        config.setResetPolicy(resetPolicy);
        DocumentSequenceConfig saved = configRepository.save(config);

        events.publishEvent(new SequenceConfigCreatedEvent(saved, TenantContext.getUserId()));
        return saved;
    }

    /** Liste toutes les configurations du tenant courant. */
    @Transactional(readOnly = true)
    public List<DocumentSequenceConfig> listSequences(UUID companyId) {
        return configRepository.findByCompanyIdOrderByDocumentTypeAscScopeKeyAsc(companyId);
    }

    /**
     * Récupère une config par (documentType, scopeKey). 404 si introuvable.
     */
    @Transactional(readOnly = true)
    public DocumentSequenceConfig getConfig(UUID companyId, DocumentType documentType, String scopeKey) {
        return configRepository
            .findByCompanyIdAndDocumentTypeAndScopeKey(companyId, documentType, normalizeScope(scopeKey))
            .orElseThrow(() -> new NotFoundException("SEQUENCE_CONFIG_NOT_FOUND",
                "No sequence config for documentType=" + documentType
                + " scopeKey='" + normalizeScope(scopeKey) + "'"));
    }

    /**
     * <strong>Aperçu non consommateur</strong> (§6) : calcule le prochain numéro SANS incrémenter
     * le compteur et SANS poser de verrou.
     *
     * <p>Utilisable par l'UI pour afficher "Prochain numéro : FAC-2026-000143" avant validation.
     * L'utilisateur n'a aucune garantie que ce sera le numéro réellement attribué : si une
     * autre émission se produit entre l'aperçu et la validation, le numéro réel sera différent.
     * C'est acceptable : l'aperçu est purement informatif.
     */
    @Transactional(readOnly = true)
    public NextNumberPreview previewNextNumber(UUID companyId, DocumentType documentType,
                                               String scopeKey, Instant asOfDate) {
        DocumentSequenceConfig config = getConfig(companyId, documentType, scopeKey);
        String periodKey = periodKeyFor(config.getResetPolicy(), asOfDate);

        Optional<DocumentSequenceCounter> counter = counterRepository
            .findBySequenceConfigIdAndPeriodKey(config.getId(), periodKey);
        long nextValue = counter.map(c -> c.getLastValue() + 1).orElse(1L);
        String nextNumber = formatNumber(config, periodKey, nextValue, asOfDate);

        return new NextNumberPreview(companyId, documentType, config.getScopeKey(),
            periodKey, nextNumber, nextValue);
    }

    /**
     * <strong>Émission effective</strong> d'un numéro documentaire (§6).
     *
     * <p>Incémente atomiquement le compteur via un verrou pessimiste
     * {@code SELECT ... FOR UPDATE}. Deux appels concurrents ne peuvent JAMAIS produire le même
     * numéro ni sauter un numéro — testé par 50 threads réellement parallèles.
     *
     * <p>Si la {@code resetPolicy} implique un changement de période depuis le dernier appel
     * (par exemple : on passe du periodKey {@code "2025"} au periodKey {@code "2026"}), une
     * NOUVELLE ligne de compteur est créée avec {@code lastValue = 1}. L'ancienne ligne reste
     * pour audit (règle "aucune réutilisation de numéro").
     *
     * <p>Doit être appelé par les modules Phase 5/12/14 au moment exact de la transition qui
     * rend le document définitif — JAMAIS pour un brouillon.
     *
     * @param companyId   identifiant du tenant (depuis TenantContext, mais passé explicitement
     *                    pour rendre l'API auto-documentée)
     * @param documentType type de document
     * @param scopeKey    clé de portée (ex. code journal)
     * @param asOfDate    date de référence pour le calcul du periodKey (typiquement la date du
     *                    document, pas {@code now()} — un écriture datée du 31/12 doit prendre
     *                    son numéro dans la séquence de cette date, pas dans celle du jour de saisie)
     * @return le numéro émis et sa valeur numérique
     */
    @Transactional
    public IssuedNumber nextNumber(UUID companyId, DocumentType documentType,
                                   String scopeKey, Instant asOfDate) {
        DocumentSequenceConfig config = getConfig(companyId, documentType, scopeKey);
        String periodKey = periodKeyFor(config.getResetPolicy(), asOfDate);

        // Incrémentation atomique via INSERT ... ON CONFLICT DO UPDATE ... RETURNING.
        // PostgreSQL garantit que cette opération est atomique et sans conflit même sous
        // charge concurrentielle (50 threads testés). Le verrou pessimiste seul ne suffirait
        // pas car la ligne n'existe pas encore à la première émission de la période.
        Long nextValue = counterRepository.upsertAndIncrement(
            companyId, config.getId(), periodKey);
        if (nextValue == null) {
            // Ne devrait jamais arriver — RETURNING last_value renvoie toujours une valeur
            throw new IllegalStateException("upsertAndIncrement returned null");
        }

        String number = formatNumber(config, periodKey, nextValue, asOfDate);
        Instant issuedAt = Instant.now();

        events.publishEvent(new NumberIssuedEvent(
            companyId,
            TenantContext.getUserId(),
            config.getId(),
            documentType,
            config.getScopeKey(),
            periodKey,
            number,
            nextValue,
            issuedAt
        ));

        LOG.debug("Issued document number: type={} scope={} period={} value={} number={}",
            documentType, config.getScopeKey(), periodKey, nextValue, number);

        return new IssuedNumber(companyId, documentType, config.getScopeKey(),
            periodKey, number, nextValue, issuedAt);
    }

    // --- Utilitaires ---

    private void validateCreateInputs(DocumentType documentType, String scopeKey,
                                      String prefix, int padding, ResetPolicy resetPolicy) {
        if (documentType == null) {
            throw new ValidationException("DOCUMENT_TYPE_REQUIRED", "documentType is required");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new ValidationException("PREFIX_REQUIRED", "prefix is required");
        }
        if (prefix.length() > 20) {
            throw new ValidationException("PREFIX_TOO_LONG", "prefix must be at most 20 characters");
        }
        if (!prefix.matches("^[A-Za-z0-9_-]+$")) {
            throw new ValidationException("PREFIX_INVALID",
                "prefix must contain only letters, digits, underscores or hyphens");
        }
        if (padding < 1 || padding > 12) {
            throw new ValidationException("PADDING_INVALID",
                "padding must be between 1 and 12");
        }
        if (resetPolicy == null) {
            throw new ValidationException("RESET_POLICY_REQUIRED", "resetPolicy is required");
        }
        if (scopeKey != null && scopeKey.length() > 30) {
            throw new ValidationException("SCOPE_KEY_TOO_LONG",
                "scopeKey must be at most 30 characters");
        }
    }

    private String normalizeScope(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) return "";
        return scopeKey.trim();
    }

    /**
     * Calcule la clé de période en fonction de la {@code resetPolicy} et de la date de référence.
     *
     * <ul>
     *   <li>{@link ResetPolicy#NEVER} → {@code ""} (chaîne vide)</li>
     *   <li>{@link ResetPolicy#YEARLY} → {@code "2026"}</li>
     *   <li>{@link ResetPolicy#MONTHLY} → {@code "2026-07"}</li>
     * </ul>
     */
    String periodKeyFor(ResetPolicy policy, Instant asOfDate) {
        if (policy == ResetPolicy.NEVER) return "";
        LocalDate date = asOfDate.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (policy) {
            case NEVER -> "";
            case YEARLY -> String.valueOf(date.getYear());
            case MONTHLY -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
        };
    }

    /**
     * Formate le numéro final selon la config.
     *
     * <p>Format : {@code {prefix}[-{year}]-{number padded}} où {@code year} n'apparaît que si
     * {@link DocumentSequenceConfig#isIncludeYear()} est vrai, et {@code number} est complété
     * par des zéros à gauche pour atteindre {@link DocumentSequenceConfig#getPadding()} chiffres.
     */
    String formatNumber(DocumentSequenceConfig config, String periodKey, long value, Instant asOfDate) {
        StringBuilder sb = new StringBuilder(config.getPrefix());
        if (config.isIncludeYear()) {
            int year = asOfDate.atZone(ZoneOffset.UTC).toLocalDate().getYear();
            sb.append('-').append(year);
        }
        sb.append('-').append(String.format("%0" + config.getPadding() + "d", value));
        return sb.toString();
    }
}
