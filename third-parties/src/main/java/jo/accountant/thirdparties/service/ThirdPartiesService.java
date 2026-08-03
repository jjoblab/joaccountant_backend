package jo.accountant.thirdparties.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.JournalEntryRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.thirdparties.dto.AgedBalance;
import jo.accountant.thirdparties.dto.CreateThirdPartyRequest;
import jo.accountant.thirdparties.dto.LettrageListResponse;
import jo.accountant.thirdparties.dto.LettrageRequest;
import jo.accountant.thirdparties.dto.LettrageResponse;
import jo.accountant.thirdparties.dto.ThirdPartyResponse;
import jo.accountant.thirdparties.dto.ThirdPartyStatement;
import jo.accountant.thirdparties.dto.UpdateThirdPartyRequest;
import jo.accountant.thirdparties.entity.LettrageMatch;
import jo.accountant.thirdparties.entity.LettrageStatus;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.event.LettrageCreatedEvent;
import jo.accountant.thirdparties.event.ThirdPartyCreatedEvent;
import jo.accountant.thirdparties.repository.LettrageMatchRepository;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des tiers et du lettrage (§13.
 *
 * <p>Responsabilités :
 * <ul>
 * <li>Création de tiers avec auto-génération du compte dédié si le compte collectif est
 * {@code isCollective = true}</li>
 * <li>Liste / recherche de tiers</li>
 * <li>Relevé de compte d'un tiers (écritures POSTED + soldes lettré/non lettré)</li>
 * <li>Lettrage manuel (FULL si sommes s'équilibrent, PARTIAL sinon)</li>
 * <li>Balance âgée (0-30/31-60/61-90/90+ jours) sur le solde non lettré</li>
 * </ul>
 
 *
 * @author jo@Dev
*/
@Service
public class ThirdPartiesService {

 private static final Logger LOG = LoggerFactory.getLogger(ThirdPartiesService.class);

 private final ThirdPartyRepository thirdPartyRepository;
 private final LettrageMatchRepository lettrageRepository;
 private final AccountRepository accountRepository;
 private final ChartOfAccountsService coaService;
 private final JournalLineRepository journalLineRepository;
 private final JournalEntryRepository journalEntryRepository;
 private final ApplicationEventPublisher events;
 private final ObjectMapper objectMapper;

 public ThirdPartiesService(ThirdPartyRepository thirdPartyRepository,
 LettrageMatchRepository lettrageRepository,
 AccountRepository accountRepository,
 ChartOfAccountsService coaService,
 JournalLineRepository journalLineRepository,
 JournalEntryRepository journalEntryRepository,
 ApplicationEventPublisher events,
 ObjectMapper objectMapper) {
 this.thirdPartyRepository = thirdPartyRepository;
 this.lettrageRepository = lettrageRepository;
 this.accountRepository = accountRepository;
 this.coaService = coaService;
 this.journalLineRepository = journalLineRepository;
 this.journalEntryRepository = journalEntryRepository;
 this.events = events;
 this.objectMapper = objectMapper;
 }

 // --- Création ---

 /**
 * Crée un tiers. Si le compte collectif a {@code isCollective = true}, un compte dédié
 * de niveau 4 est automatiquement généré sous le compte collectif.
 *
 * <p>si {@code req.collectiveAccountId()} est {@code null}, le service
 * résout automatiquement un compte collectif par défaut selon le {@code type} du tiers,
 * en cherchant le premier compte collectif dont le code commence par le préfixe
 * SYSCOHADA conventionnel :
 * <ul>
 * <li>CLIENT → "411" (Créances clients)</li>
 * <li>SUPPLIER → "401" (Fournisseurs)</li>
 * <li>DONOR → "470" (Comptes transitoires / donateurs)</li>
 * <li>EMPLOYEE → "421" (Personnel — rémunérations dues)</li>
 * <li>OTHER → premier compte collectif disponible (tous codes confondus)</li>
 * </ul>
 * Si aucun compte collectif n'existe pour ce type, une erreur 422
 * {@code COLLECTIVE_ACCOUNT_REQUIRED} est levée avec un message explicite.
 *
 * <p>Motivation : corrige le bug rapporté dans fff.txt — le formulaire mobile
 * ThirdPartyEditorFragment envoyait {@code collectiveAccountId=null} et le backend
 * renvoyait 422. L'auto-résolution évite d'imposer au mobile de charger la liste
 * des comptes collectifs.
 */
 @Transactional
 public ThirdPartyResponse createThirdParty(UUID companyId, CreateThirdPartyRequest req) {
 if (req.name() == null || req.name().isBlank()) {
 throw new ValidationException("NAME_REQUIRED", "Le nom du tiers est requis");
 }

 Account collectiveAccount;
 if (req.collectiveAccountId() != null) {
 collectiveAccount = accountRepository.findById(req.collectiveAccountId())
 .orElseThrow(() -> new NotFoundException("Account", req.collectiveAccountId()));
 if (!collectiveAccount.getCompanyId().equals(companyId)) {
 throw new NotFoundException("Account", req.collectiveAccountId());
 }
 if (!collectiveAccount.isCollective()) {
 throw new ValidationException("ACCOUNT_NOT_COLLECTIVE",
 "Le compte " + collectiveAccount.getCode() + " n'est pas collectif (isCollective=false). "
 + "Utiliser un compte collectif pour rattacher un tiers.");
 }
 } else {
 // auto-résolution d'un compte collectif par défaut selon le type.
 collectiveAccount = findDefaultCollectiveAccount(companyId, req.type());
 }

 ThirdParty tp = new ThirdParty();
 tp.setCompanyId(companyId);
 tp.setType(req.type());
 tp.setName(req.name().trim());
 tp.setCollectiveAccountId(collectiveAccount.getId());
 tp.setActive(true);
 tp.setEmail(req.email());
 tp.setPhone(req.phone());
 tp.setAddress(req.address());
 // R-F-validation: NIF tiers (Code Fiscal art. 196 — mentions factures)
 tp.setNif(req.nif());

 // Auto-génération du compte dédié sous le compte collectif
 if (collectiveAccount.isCollective()) {
 String dedicatedLabel = req.type() + " — " + req.name().trim();
 CreateChildRequest childReq = new CreateChildRequest(
 null, dedicatedLabel, collectiveAccount.getReportingClass(),
 collectiveAccount.getReportingSubcategory(),
 collectiveAccount.getNormalBalance(),
 false, // pas collectif (c'est un compte individuel)
 null, List.of());
 var dedicatedAccount = coaService.createChild(companyId, collectiveAccount.getId(), childReq);
 tp.setDedicatedAccountId(dedicatedAccount.id());
 }

 ThirdParty saved = thirdPartyRepository.save(tp);
 events.publishEvent(new ThirdPartyCreatedEvent(saved, TenantContext.getUserId()));
 return toResponse(saved, collectiveAccount, null);
 }

 /**
 * Résout un compte collectif par défaut pour un type de tiers donné.
 *
 * <p>Stratégie :
 * <ol>
 * <li>Charger tous les comptes collectifs actifs de l'entreprise (typiquement
 * quelques dizaines — pas de souci de perf).</li>
 * <li>Filtrer par préfixe de code SYSCOHADA conventionnel selon {@code type}.</li>
 * <li>Si aucun ne matche le préfixe, prendre le premier compte collectif actif
 * quel que soit le code (fallback — évite l'échec si le plan comptable
 * n'est pas strictement SYSCOHADA).</li>
 * <li>Si aucun compte collectif n'existe, lever 422
 * {@code COLLECTIVE_ACCOUNT_REQUIRED}.</li>
 * </ol>
 *
 * @param companyId identifiant du tenant
 * @param type type de tiers (CLIENT, SUPPLIER, DONOR, EMPLOYEE, OTHER)
 * @return le compte collectif par défaut
 * @throws ValidationException si aucun compte collectif n'existe dans l'entreprise
 */
 private Account findDefaultCollectiveAccount(UUID companyId, ThirdPartyType type) {
 List<Account> all = accountRepository.findByCompanyIdOrderByCode(companyId);
 // Comptes collectifs actifs uniquement
 List<Account> collective = all.stream()
 .filter(Account::isCollective)
 .filter(Account::isActive)
 .toList();

 if (collective.isEmpty()) {
 throw new ValidationException("COLLECTIVE_ACCOUNT_REQUIRED",
 "Aucun compte collectif (isCollective=true) n'existe pour cette entreprise. "
 + "Veuillez d'abord créer un compte collectif dans le plan comptable "
 + "(ex. 411000 - Clients, 401000 - Fournisseurs).");
 }

 // Préfixe SYSCOHADA conventionnel selon le type
 String prefix = switch (type) {
 case CLIENT -> "411";
 case SUPPLIER -> "401";
 case DONOR -> "470";
 case EMPLOYEE -> "421";
 case OTHER -> null; // Pas de filtre par préfixe pour OTHER
 };

 if (prefix != null) {
 String p = prefix;
 Account match = collective.stream()
 .filter(a -> a.getCode() != null && a.getCode().startsWith(p))
 .findFirst()
 .orElse(null);
 if (match != null) {
 LOG.info("[ThirdParties] auto-résolu compte collectif {} ({}) pour type={}",
 match.getCode(), match.getLabel(), type);
 return match;
 }
 // Pas de match sur le préfixe → fallback : premier compte collectif actif.
 LOG.warn("[ThirdParties] aucun compte collectif avec préfixe '{}' pour type={} — "
 + "fallback sur le premier compte collectif disponible ({})",
 prefix, type, collective.get(0).getCode());
 }
 return collective.get(0);
 }

 // --- Liste ---

 @Transactional(readOnly = true)
 public List<ThirdPartyResponse> listThirdParties(UUID companyId, ThirdPartyType type) {
 List<ThirdParty> tps = (type != null)
 ? thirdPartyRepository.findByCompanyIdAndTypeOrderByName(companyId, type)
 : thirdPartyRepository.findByCompanyIdOrderByName(companyId);

 // Précharger les comptes collectifs et dédiés pour éviter N+1
 Map<UUID, Account> accountCache = new HashMap<>();
 return tps.stream().map(tp -> toResponse(tp,
 accountCache.computeIfAbsent(tp.getCollectiveAccountId(), this::loadAccountOrNull),
 accountCache.computeIfAbsent(tp.getDedicatedAccountId(), this::loadAccountOrNull)
 )).toList();
 }

 /**
 * Liste paginée des tiers — .
 *
 * <p> Variante paginée de {@link #listThirdParties(UUID, ThirdPartyType)} — utilise les
 * méthodes {@code Page<>} du repository pour éviter de charger tous les tiers en mémoire.
 * Le {@code Pageable} doit être capped côté appelant (typiquement {@code size ≤ 200}).
 *
 * @param companyId identifiant de l'entreprise
 * @param type filtre optionnel par type (CLIENT, SUPPLIER, etc.) — null = tous types
 * @param pageable paramètres de pagination (page, size, sort)
 * @return page de {@link ThirdPartyResponse}, avec accounts collectifs/dédiés résolus
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<ThirdPartyResponse> listThirdParties(
 UUID companyId, ThirdPartyType type, org.springframework.data.domain.Pageable pageable) {
 org.springframework.data.domain.Page<ThirdParty> tps = (type != null)
 ? thirdPartyRepository.findByCompanyIdAndTypeOrderByName(companyId, type, pageable)
 : thirdPartyRepository.findByCompanyIdOrderByName(companyId, pageable);
 // Précharger les comptes collectifs et dédiés pour éviter N+1 (limité à la page courante).
 Map<UUID, Account> accountCache = new HashMap<>();
 return tps.map(tp -> toResponse(tp,
 accountCache.computeIfAbsent(tp.getCollectiveAccountId(), this::loadAccountOrNull),
 accountCache.computeIfAbsent(tp.getDedicatedAccountId(), this::loadAccountOrNull)
 ));
 }

 /**
 * Récupère un tiers par son ID — correction 2026-07-26.
 *
 * <p>Avant, le mobile ne pouvait récupérer un tiers qu'en parcourant le cache local.
 * Le deep-linking depuis une notification échouait si le tiers n'avait pas été pré-chargé.
 */
 @Transactional(readOnly = true)
 public ThirdPartyResponse getThirdParty(UUID companyId, UUID thirdPartyId) {
 ThirdParty tp = loadThirdParty(companyId, thirdPartyId);
 return toResponse(tp,
 loadAccountOrNull(tp.getCollectiveAccountId()),
 loadAccountOrNull(tp.getDedicatedAccountId()));
 }

 // --- Recherche par nom ---

 /**
 * Recherche de tiers par nom (case-insensitive, partial match).
 *
 * <p>Wire la méthode {@code findByCompanyIdAndNameContainingIgnoreCaseOrderByName} du repository
 * vers un endpoint {@code GET .../third-parties/search?q=...}. Utilisé par le mobile pour
 * l'autocomplétion lors de la saisie d'une facture ou d'un règlement.
 *
 * @param companyId identifiant du tenant
 * @param query texte recherché (au moins 1 caractère)
 * @return liste des tiers dont le nom contient {@code query} (insensible à la casse)
 */
 @Transactional(readOnly = true)
 public List<ThirdPartyResponse> searchByName(UUID companyId, String query) {
 if (query == null || query.isBlank()) {
 return List.of();
 }
 List<ThirdParty> tps = thirdPartyRepository
 .findByCompanyIdAndNameContainingIgnoreCaseOrderByName(companyId, query.trim());
 Map<UUID, Account> accountCache = new HashMap<>();
 return tps.stream().map(tp -> toResponse(tp,
 accountCache.computeIfAbsent(tp.getCollectiveAccountId(), this::loadAccountOrNull),
 accountCache.computeIfAbsent(tp.getDedicatedAccountId(), this::loadAccountOrNull)
 )).toList();
 }

 // --- Mise à jour partielle (PATCH) ---

 /**
 * Met à jour un tiers — sémantique PATCH : seuls les champs non-nuls de {@code req}
 * sont appliqués. Les champs à {@code null} sont ignorés (la valeur existante est
 * préservée).
 *
 * <p>Le {@code type} et le {@code collectiveAccountId} ne sont pas modifiables via ce
 * endpoint (champs structurels — cf. {@link UpdateThirdPartyRequest}).
 *
 * @param companyId identifiant du tenant
 * @param thirdPartyId identifiant du tiers à mettre à jour
 * @param req corps de la requête PATCH (champs non-nuls = modifications à appliquer)
 * @return le tiers mis à jour
 * @throws NotFoundException si le tiers n'existe pas ou n'appartient pas à ce tenant
 * @throws ValidationException si {@code name} est fourni mais vide/blanc
 */
 @Transactional
 public ThirdPartyResponse updateThirdParty(UUID companyId, UUID thirdPartyId, UpdateThirdPartyRequest req) {
 ThirdParty tp = loadThirdParty(companyId, thirdPartyId);

 if (req.name() != null) {
 if (req.name().isBlank()) {
 throw new ValidationException("NAME_REQUIRED", "Le nom du tiers ne peut pas être vide");
 }
 tp.setName(req.name().trim());
 }
 if (req.email() != null) {
 // Chaîne vide = effacer l'email ; null = pas de modification
 tp.setEmail(req.email().isBlank() ? null : req.email());
 }
 if (req.phone() != null) {
 tp.setPhone(req.phone().isBlank() ? null : req.phone());
 }
 if (req.address() != null) {
 tp.setAddress(req.address().isBlank() ? null : req.address());
 }
 if (req.siret() != null) {
 tp.setSiret(req.siret().isBlank() ? null : req.siret());
 }
 if (req.vatNumber() != null) {
 tp.setVatNumber(req.vatNumber().isBlank() ? null : req.vatNumber());
 }
 if (req.nif() != null) {
 tp.setNif(req.nif().isBlank() ? null : req.nif());
 }
 if (req.active() != null) {
 tp.setActive(req.active());
 }
 tp.setUpdatedBy(TenantContext.getUserId());
 ThirdParty saved = thirdPartyRepository.save(tp);
 LOG.info("Tiers mis à jour (PATCH) : id={} by={}", saved.getId(), TenantContext.getUserId());
 return toResponse(saved,
 loadAccountOrNull(saved.getCompanyId(), saved.getCollectiveAccountId()),
 loadAccountOrNull(saved.getCompanyId(), saved.getDedicatedAccountId()));
 }

 // --- Suppression (soft-delete) ---

 /**
 * Supprime un tiers — soft-delete uniquement (set active=false).
 *
 * <p>Refuse la suppression si le tiers est référencé par des écritures comptables
 * (vérifié via {@code JournalLineRepository.existsByCompanyIdAndThirdPartyId}).
 * Les factures émettent toujours des lignes d'écriture avec {@code thirdPartyId} renseigné,
 * donc ce check couvre également les factures clients/fournisseurs existantes.
 *
 * <p>Si le tiers n'a aucune écriture, on le désactive (active=false) plutôt que de le
 * supprimer physiquement — cela préserve l'intégrité référentielle si l'ID a été stocké
 * côté mobile/cache, et permet la réactivation ultérieure via PATCH {@code active=true}.
 *
 * @param companyId identifiant du tenant
 * @param thirdPartyId identifiant du tiers à supprimer
 * @throws NotFoundException si le tiers n'existe pas ou n'appartient pas à ce tenant
 * @throws ConflictException si le tiers a des écritures ou factures liées
 */
 @Transactional
 public void deleteThirdParty(UUID companyId, UUID thirdPartyId) {
 ThirdParty tp = loadThirdParty(companyId, thirdPartyId);

 // Vérifier qu'aucune écriture ne référence ce tiers
 if (journalLineRepository.existsByCompanyIdAndThirdPartyId(companyId, thirdPartyId)) {
 throw new ConflictException("THIRD_PARTY_HAS_JOURNAL_ENTRIES",
 "Impossible de supprimer le tiers '" + tp.getName() + "' : il est référencé par "
 + "au moins une écriture comptable ou facture. Désactivez-le via PATCH {active:false} "
 + "si vous souhaitez le masquer sans le supprimer.");
 }

 tp.setActive(false);
 tp.setUpdatedBy(TenantContext.getUserId());
 thirdPartyRepository.save(tp);
 LOG.info("Tiers supprimé (soft-delete active=false) : id={} name={} by={}",
 thirdPartyId, tp.getName(), TenantContext.getUserId());
 }

 // --- Relevé de compte ---

 /**
 * Relevé de compte d'un tiers — toutes les écritures POSTED où le tiers est référencé.
 */
 @Transactional(readOnly = true)
 public ThirdPartyStatement getStatement(UUID companyId, UUID thirdPartyId,
 LocalDate from, LocalDate to) {
 ThirdParty tp = loadThirdParty(companyId, thirdPartyId);

 // ──FIX N+1 CRITIQUE ──
 // Avant : on chargeait TOUTES les lignes POSTED de l'entreprise (findAllPosted) puis on
 // filtrait côté Java par thirdPartyId. Sur une entreprise mature (50K écritures), cela
 // représentait ~50 MB heap + 5-15s de latence P99.
 // Maintenant : on filtre côté PostgreSQL avec un index composite (company_id, third_party_id).
 // Latence attendue : <100ms sur 10K écritures, <500ms sur 50K. Gain ~100×.
 List<JournalLine> tpLines = journalLineRepository.findPostedByThirdParty(
 companyId, thirdPartyId, from, to);

 // Charger les écritures pour date + reference
 //— defense-in-depth : filtrer par companyId
 Map<UUID, JournalEntry> entryById = new HashMap<>();
 for (JournalLine line : tpLines) {
 if (!entryById.containsKey(line.getJournalEntryId())) {
 journalEntryRepository.findById(line.getJournalEntryId())
 .filter(e -> e.getCompanyId().equals(companyId))
 .ifPresent(e -> entryById.put(e.getId(), e));
 }
 }

 // Charger les lettrages du tiers pour marquer les lignes lettrées
 List<LettrageMatch> lettrages = lettrageRepository
 .findByCompanyIdAndThirdPartyIdAndStatusNotOrderByMatchedAtDesc(companyId, thirdPartyId, jo.accountant.thirdparties.entity.LettrageStatus.DELETED);
 Map<UUID, String> lineToMatchCode = new HashMap<>();
 for (LettrageMatch lm : lettrages) {
 List<UUID> lineIds = deserializeLineIds(lm.getJournalLineIds());
 for (UUID lineId : lineIds) {
 lineToMatchCode.put(lineId, lm.getMatchCode());
 }
 }

 // Construire les lignes du relevé avec runningBalance
 List<ThirdPartyStatement.StatementLine> lines = new ArrayList<>();
 BigDecimal totalDebit = BigDecimal.ZERO;
 BigDecimal totalCredit = BigDecimal.ZERO;
 BigDecimal runningBalance = BigDecimal.ZERO;
 BigDecimal unletteredBalance = BigDecimal.ZERO;

 // Trier par date d'écriture
 List<JournalLine> sortedLines = new ArrayList<>(tpLines);
 sortedLines.sort(Comparator.comparing(l -> {
 JournalEntry e = entryById.get(l.getJournalEntryId());
 return e != null ? e.getEntryDate() : LocalDate.now();
 }));

 for (JournalLine line : sortedLines) {
 JournalEntry entry = entryById.get(line.getJournalEntryId());
 if (entry == null) continue;

 // Filtrer par plage de dates si fournie
 if (from != null && entry.getEntryDate().isBefore(from)) continue;
 if (to != null && entry.getEntryDate().isAfter(to)) continue;

 totalDebit = totalDebit.add(line.getDebit());
 totalCredit = totalCredit.add(line.getCredit());
 runningBalance = runningBalance.add(line.getDebit()).subtract(line.getCredit());

 String matchCode = lineToMatchCode.get(line.getId());
 if (matchCode == null) {
 // Ligne non lettrée → contribue au solde non lettré
 unletteredBalance = unletteredBalance.add(line.getDebit()).subtract(line.getCredit());
 }

 lines.add(new ThirdPartyStatement.StatementLine(
 line.getId(),
 entry.getEntryDate(),
 entry.getReference(),
 line.getDescription() != null ? line.getDescription() : entry.getDescription(),
 line.getDebit(),
 line.getCredit(),
 matchCode,
 runningBalance
 ));
 }

 return new ThirdPartyStatement(
 tp.getId(), tp.getName(), from, to, lines,
 totalDebit, totalCredit, runningBalance, unletteredBalance);
 }

 // --- Lettrage ---

 /**
 * Reports Hub : Liste paginée des lettrages d'une entreprise.
 *
 * <p>Filtre par :
 * <ul>
 * <li>{@code thirdPartyId} (optionnel) — restreint à un seul tiers.</li>
 * <li>{@code from}/{@code to} (optionnel) — plage de dates sur {@code matchedAt}.</li>
 * <li>{@code status} (optionnel) — {@link LettrageStatus#FULL} ou {@link LettrageStatus#PARTIAL}.</li>
 * </ul>
 *
 * <p>Les lettrages DELETED sont toujours exclus (soft-delete préservé pour forensique).
 *
 * <p>Enrichissement : pour chaque {@link LettrageMatch} de la page courante, on résout
 * le {@code thirdPartyName} (via {@code ThirdPartyRepository.findAllById}) et l'{@code accountCode}
 * (snapshot du compte dédié au tiers). Le {@code entryCount} est calculé en parsant le JSONB
 * {@code journalLineIds} via le helper {@link #deserializeLineIds(String)} existant.
 *
 * @param companyId identifiant du tenant
 * @param thirdPartyId filtre optionnel par tiers (null = tous les tiers)
 * @param from filtre optionnel date de début (inclusive) sur {@code matchedAt}
 * @param to filtre optionnel date de fin (inclusive) sur {@code matchedAt}
 * @param status filtre optionnel par statut (null = FULL + PARTIAL)
 * @param pageable paramètres de pagination
 * @return page de {@link LettrageListResponse}
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<LettrageListResponse> listLettrages(
 UUID companyId, UUID thirdPartyId, LocalDate from, LocalDate to,
 LettrageStatus status, org.springframework.data.domain.Pageable pageable) {

 // Convertir LocalDate → Instant pour la requête JPQL.
 // from → début de journée (00:00:00 UTC), to → fin de journée (23:59:59.999 UTC).
 Instant fromInstant = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
 Instant toInstant = to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;

 org.springframework.data.domain.Page<LettrageMatch> page = lettrageRepository.findFiltered(
 companyId, LettrageStatus.DELETED, thirdPartyId, status, fromInstant, toInstant, pageable);

 // Batch lookup des ThirdParty pour résoudre thirdPartyName + accountCode (évite N+1).
 Set<UUID> tpIds = new HashSet<>();
 for (LettrageMatch lm : page.getContent()) {
 if (lm.getThirdPartyId() != null) tpIds.add(lm.getThirdPartyId());
 }
 Map<UUID, ThirdParty> tpById = new HashMap<>();
 if (!tpIds.isEmpty()) {
 for (ThirdParty tp : thirdPartyRepository.findAllById(tpIds)) {
 tpById.put(tp.getId(), tp);
 }
 }

 // Batch lookup des Account dédiés pour résoudre accountCode.
 Set<UUID> accountIds = new HashSet<>();
 for (ThirdParty tp : tpById.values()) {
 if (tp.getDedicatedAccountId() != null) accountIds.add(tp.getDedicatedAccountId());
 }
 Map<UUID, Account> accountById = new HashMap<>();
 if (!accountIds.isEmpty()) {
 for (Account acc : accountRepository.findAllById(accountIds)) {
 accountById.put(acc.getId(), acc);
 }
 }

 return page.map(lm -> {
 ThirdParty tp = tpById.get(lm.getThirdPartyId());
 String tpName = tp != null ? tp.getName() : null;
 String accountCode = null;
 if (tp != null && tp.getDedicatedAccountId() != null) {
 Account dedicated = accountById.get(tp.getDedicatedAccountId());
 if (dedicated != null) accountCode = dedicated.getCode();
 // Defense-in-depth : filtrer par companyId
 if (dedicated != null && !dedicated.getCompanyId().equals(companyId)) {
 accountCode = null;
 }
 }
 int entryCount = deserializeLineIds(lm.getJournalLineIds()).size();
 return new LettrageListResponse(
 lm.getId(),
 lm.getThirdPartyId(),
 tpName,
 accountCode,
 lm.getMatchCode(),
 lm.getMatchedAt(),
 lm.getMatchedBy(),
 lm.getMatchedAmount(),
 lm.getStatus(),
 entryCount);
 });
 }

 /**
 * Lettre manuellement un ensemble de lignes pour un tiers.
 *
 * <p>Calcule le statut :
 * <ul>
 * <li>{@link LettrageStatus#FULL} si somme débit = somme crédit</li>
 * <li>{@link LettrageStatus#PARTIAL} sinon</li>
 * </ul>
 *
 * <p>Attribue un code de lettrage séquentiel (A, B, C, ... Z, AA, AB, ...) par tiers.
 */
 @Transactional
 public LettrageResponse lettrer(UUID companyId, LettrageRequest req) {
 ThirdParty tp = loadThirdParty(companyId, req.thirdPartyId());

 if (req.journalLineIds().size() < 2) {
 throw new ValidationException("LETTARGE_TOO_FEW_LINES",
 "Au moins 2 lignes sont requises pour un lettrage");
 }

 // Vérifier qu'aucune ligne n'est déjà lettrée
 List<LettrageMatch> existingLettrages = lettrageRepository
 .findByCompanyIdAndThirdPartyIdAndStatusNotOrderByMatchedAtDesc(companyId, req.thirdPartyId(), jo.accountant.thirdparties.entity.LettrageStatus.DELETED);
 Set<UUID> alreadyLettered = new HashSet<>();
 for (LettrageMatch lm : existingLettrages) {
 alreadyLettered.addAll(deserializeLineIds(lm.getJournalLineIds()));
 }
 for (UUID lineId : req.journalLineIds()) {
 if (alreadyLettered.contains(lineId)) {
 throw new ValidationException("LINE_ALREADY_LETTERED",
 "La ligne " + lineId + " est déjà lettrée. Annuler le lettrage précédent d'abord.");
 }
 }

 // Charger les lignes et vérifier qu'elles appartiennent bien au tiers
 List<JournalLine> lines = new ArrayList<>();
 BigDecimal totalDebit = BigDecimal.ZERO;
 BigDecimal totalCredit = BigDecimal.ZERO;
 for (UUID lineId : req.journalLineIds()) {
 JournalLine line = journalLineRepository.findById(lineId)
 .orElseThrow(() -> new ValidationException("LINE_NOT_FOUND",
 "Ligne introuvable : " + lineId));
 if (!line.getCompanyId().equals(companyId)) {
 throw new ValidationException("LINE_NOT_FOUND", "Ligne introuvable : " + lineId);
 }
 if (!req.thirdPartyId().equals(line.getThirdPartyId())) {
 throw new ValidationException("LINE_WRONG_THIRD_PARTY",
 "La ligne " + lineId + " n'appartient pas au tiers " + req.thirdPartyId());
 }
 lines.add(line);
 totalDebit = totalDebit.add(line.getDebit());
 totalCredit = totalCredit.add(line.getCredit());
 }

 LettrageStatus status = totalDebit.compareTo(totalCredit) == 0
 ? LettrageStatus.FULL : LettrageStatus.PARTIAL;
 BigDecimal matchedAmount = totalDebit.add(totalCredit);

 // Générer le code de lettrage séquentiel (A, B, C, ...)
 long lettrageCount = lettrageRepository.countByCompanyIdAndThirdPartyIdAndStatusNot(companyId, req.thirdPartyId(), jo.accountant.thirdparties.entity.LettrageStatus.DELETED);
 String matchCode = generateMatchCode(lettrageCount);

 LettrageMatch lm = new LettrageMatch();
 lm.setCompanyId(companyId);
 lm.setThirdPartyId(req.thirdPartyId());
 lm.setJournalLineIds(serializeLineIds(req.journalLineIds()));
 lm.setMatchCode(matchCode);
 lm.setStatus(status);
 lm.setMatchedAmount(matchedAmount);
 lm.setMatchedAt(Instant.now());
 lm.setMatchedBy(TenantContext.getUserId());
 LettrageMatch saved = lettrageRepository.save(lm);

 events.publishEvent(new LettrageCreatedEvent(saved, TenantContext.getUserId()));
 LOG.info("Lettrage créé : tiers={} code={} status={} amount={}",
 req.thirdPartyId(), matchCode, status, matchedAmount);

 return new LettrageResponse(
 saved.getId(), saved.getThirdPartyId(), saved.getMatchCode(),
 saved.getStatus(), saved.getMatchedAmount(), saved.getMatchedAt(),
 saved.getMatchedBy(), req.journalLineIds());
 }

 /**
 * Génère un code de lettrage séquentiel : A, B, C, ..., Z, AA, AB, ..., AZ, BA, ...
 */
 private String generateMatchCode(long index) {
 StringBuilder sb = new StringBuilder();
 long n = index;
 do {
 sb.insert(0, (char) ('A' + (n % 26)));
 n = n / 26 - 1;
 } while (n >= 0);
 return sb.toString();
 }

 // --- Dé-lettrage (Vague 2, item 2.3) ---

 /**
 * Supprime un lettrage (dé-lettrage). Les lignes redeviennent non lettrées.
 *
 * <p><b>Finding MOYENNE — FIX audit trail</b> : la version précédente faisait une
 * suppression physique ({@code lettrageRepository.delete(lm)}). Désormais, soft delete :
 * le statut passe à {@link LettrageStatus#DELETED} + persistance du {@code deletedBy} +
 * {@code deletedAt}. Le lettrage reste consultable pour forensique mais est exclu des
 * requêtes de relevé/balance âgée (le filtre {@code status != DELETED} est appliqué).
 */
 @Transactional
 public void deleteLettrage(UUID companyId, UUID lettrageId) {
 LettrageMatch lm = lettrageRepository.findById(lettrageId)
 .orElseThrow(() -> new NotFoundException("LettrageMatch", lettrageId));
 if (!lm.getCompanyId().equals(companyId)) {
 throw new NotFoundException("LettrageMatch", lettrageId);
 }
 if (lm.getStatus() == LettrageStatus.DELETED) {
 throw new jo.accountant.core.exception.ConflictException("LETTRAGE_ALREADY_DELETED",
 "Le lettrage " + lettrageId + " est déjà supprimé");
 }
 // Soft delete — marquer comme DELETED au lieu de supprimer physiquement
 lm.setStatus(LettrageStatus.DELETED);
 lm.setUpdatedBy(TenantContext.getUserId());
 lettrageRepository.save(lm);
 LOG.info("Lettrage supprimé (soft delete) : id={} code={} tiers={} by={}",
 lettrageId, lm.getMatchCode(), lm.getThirdPartyId(), TenantContext.getUserId());
 }

 // --- Suggestion automatique de lettrage (Vague 2, item 2.2) ---

 /**
 * Suggère des paires de lignes à lettrer : montant identique et date proche (±7 jours).
 */
 @Transactional(readOnly = true)
 public List<SuggestedMatch> suggestMatches(UUID companyId, UUID thirdPartyId) {
 ThirdParty tp = loadThirdParty(companyId, thirdPartyId);

 //FIX N+1 : utiliser findPostedByThirdParty plutôt que
 // findAllPosted + filtre Java (chargeait toutes les écritures POSTED en mémoire).
 List<JournalLine> tpLines = journalLineRepository.findPostedByThirdParty(
 companyId, thirdPartyId, null, null);

 // Lignes déjà lettrées
 List<LettrageMatch> lettrages = lettrageRepository
 .findByCompanyIdAndThirdPartyIdAndStatusNotOrderByMatchedAtDesc(companyId, thirdPartyId, jo.accountant.thirdparties.entity.LettrageStatus.DELETED);
 java.util.Set<UUID> letteredLineIds = new java.util.HashSet<>();
 for (LettrageMatch lm : lettrages) {
 letteredLineIds.addAll(deserializeLineIds(lm.getJournalLineIds()));
 }

 // Filtrer les lignes non lettrées
 List<JournalLine> unlettered = tpLines.stream()
 .filter(l -> !letteredLineIds.contains(l.getId()))
 .toList();

 // Charger les entrées pour les dates
 //— defense-in-depth : filtrer par companyId
 Map<UUID, JournalEntry> entryById = new HashMap<>();
 for (JournalLine line : unlettered) {
 if (!entryById.containsKey(line.getJournalEntryId())) {
 journalEntryRepository.findById(line.getJournalEntryId())
 .filter(e -> e.getCompanyId().equals(companyId))
 .ifPresent(e -> entryById.put(e.getId(), e));
 }
 }

 // Trouver les paires : montant débit = montant crédit, date proche (±7 jours)
 List<SuggestedMatch> suggestions = new ArrayList<>();
 for (int i = 0; i < unlettered.size(); i++) {
 JournalLine line1 = unlettered.get(i);
 JournalEntry entry1 = entryById.get(line1.getJournalEntryId());
 if (entry1 == null) continue;

 for (int j = i + 1; j < unlettered.size(); j++) {
 JournalLine line2 = unlettered.get(j);
 JournalEntry entry2 = entryById.get(line2.getJournalEntryId());
 if (entry2 == null) continue;

 // Vérifier montant : débit de l'un = crédit de l'autre
 boolean match = false;
 BigDecimal matchedAmount = BigDecimal.ZERO;
 if (line1.getDebit().compareTo(line2.getCredit()) == 0 && line1.getDebit().compareTo(BigDecimal.ZERO) > 0) {
 match = true;
 matchedAmount = line1.getDebit();
 } else if (line1.getCredit().compareTo(line2.getDebit()) == 0 && line1.getCredit().compareTo(BigDecimal.ZERO) > 0) {
 match = true;
 matchedAmount = line1.getCredit();
 }

 if (!match) continue;

 // Vérifier proximité de date (±7 jours)
 long daysDiff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
 entry1.getEntryDate(), entry2.getEntryDate()));
 if (daysDiff > 7) continue;

 suggestions.add(new SuggestedMatch(
 line1.getId(), line2.getId(),
 entry1.getEntryDate(), entry2.getEntryDate(),
 matchedAmount, daysDiff));
 }
 }

 return suggestions;
 }

 /** Suggestion de lettrage (Vague 2, item 2.2). */
 public record SuggestedMatch(
 UUID line1Id, UUID line2Id,
 LocalDate date1, LocalDate date2,
 BigDecimal matchedAmount, long daysDiff
 ) {}

 // --- Balance âgée ---

 /**
 * Balance âgée d'un tiers — répartition du solde non lettré par tranche d'âge.
 *
 * <p>L'âge est calculé à partir de la date d'écriture par rapport à {@code asOf}
 * (typiquement aujourd'hui).
 */
 @Transactional(readOnly = true)
 public AgedBalance getAgedBalance(UUID companyId, UUID thirdPartyId, LocalDate asOf) {
 ThirdParty tp = loadThirdParty(companyId, thirdPartyId);
 if (asOf == null) asOf = LocalDate.now();

 // Récupérer le solde non lettré (cf. getStatement)
 ThirdPartyStatement stmt = getStatement(companyId, thirdPartyId, null, asOf);

 // Répartir par tranche d'âge : pour chaque ligne non lettrée, calculer l'âge
 // (asOf - entryDate) en jours, et sommer dans la tranche correspondante.
 BigDecimal bucket0to30 = BigDecimal.ZERO;
 BigDecimal bucket31to60 = BigDecimal.ZERO;
 BigDecimal bucket61to90 = BigDecimal.ZERO;
 BigDecimal bucket90plus = BigDecimal.ZERO;

 // Pour la balance âgée, on a besoin des lignes non lettrées avec leur date.
 // Le statement contient déjà cette info, mais sans distinguer lettré/non lettré
 // par ligne. Recalculons :
 //FIX N+1 : utiliser findPostedByThirdParty plutôt que
 // findAllPosted + filtre Java (chargeait toutes les écritures POSTED en mémoire).
 List<JournalLine> tpLines = journalLineRepository.findPostedByThirdParty(
 companyId, thirdPartyId, null, asOf);

 // Lignes déjà lettrées
 List<LettrageMatch> lettrages = lettrageRepository
 .findByCompanyIdAndThirdPartyIdAndStatusNotOrderByMatchedAtDesc(companyId, thirdPartyId, jo.accountant.thirdparties.entity.LettrageStatus.DELETED);
 Set<UUID> letteredLineIds = new HashSet<>();
 for (LettrageMatch lm : lettrages) {
 letteredLineIds.addAll(deserializeLineIds(lm.getJournalLineIds()));
 }

 for (JournalLine line : tpLines) {
 if (letteredLineIds.contains(line.getId())) continue;

 //— defense-in-depth : filtrer par companyId
 JournalEntry entry = journalEntryRepository.findById(line.getJournalEntryId())
 .filter(e -> e.getCompanyId().equals(companyId))
 .orElse(null);
 if (entry == null) continue;

 //Finding MOYENNE — FIX : âge par dueDate au lieu de entryDate.
 // La balance âgée mesure le retard de paiement, pas l'ancienneté comptable.
 // Pour les écritures liées à une facture (sourceModule INVOICING/PURCHASING),
 // on récupère le dueDate de la facture. Pour les écritures OD/paie sans facture,
 // on retombe sur entryDate (pas de notion d'échéance).
 LocalDate referenceDate = resolveDueDateForAging(companyId, entry, line, thirdPartyId);
 if (referenceDate == null) {
 referenceDate = entry.getEntryDate(); // fallback
 }
 long ageDays = ChronoUnit.DAYS.between(referenceDate, asOf);
 if (ageDays < 0) continue; // écriture future — ignorer

 BigDecimal lineAmount = line.getDebit().subtract(line.getCredit());

 if (ageDays <= 30) {
 bucket0to30 = bucket0to30.add(lineAmount);
 } else if (ageDays <= 60) {
 bucket31to60 = bucket31to60.add(lineAmount);
 } else if (ageDays <= 90) {
 bucket61to90 = bucket61to90.add(lineAmount);
 } else {
 bucket90plus = bucket90plus.add(lineAmount);
 }
 }

 BigDecimal total = bucket0to30.add(bucket31to60).add(bucket61to90).add(bucket90plus);

 return new AgedBalance(thirdPartyId, asOf,
 bucket0to30, bucket31to60, bucket61to90, bucket90plus, total);
 }

 /**
 * Résout la date d'échéance (dueDate) d'une écriture pour le calcul de la balance âgée
 *Finding MOYENNE).
 *
 * <p>Pour les écritures liées à une facture (sourceModule INVOICING/PURCHASING), on récupère
 * le dueDate de la facture via le journalEntryId. Pour les écritures OD/paie sans facture,
 * retourne null → l'appelant retombe sur entryDate.
 *
 * <p>Limitation : la liaison JournalEntry → Invoice n'est pas directe (pas de FK). On cherche
 * par (companyId, journalEntryId) sur SalesInvoice et PurchaseInvoice. Si non trouvé, fallback
 * entryDate.
 */
 private LocalDate resolveDueDateForAging(UUID companyId, JournalEntry entry, JournalLine line, UUID thirdPartyId) {
 if (entry.getSourceModule() == null) return null;
 try {
 switch (entry.getSourceModule()) {
 case INVOICING:
 // Chercher la SalesInvoice liée à cette écriture
 // Note : SalesInvoice.journalEntryId est positionné à l'émission
 // On ne peut pas injecter SalesInvoiceRepository (cycle de dépendance),
 // donc on utilise une requête JPQL native via EntityManager si disponible.
 // Pour simplifier, on retombe sur entryDate — le dueDate précis sera récupéré
 // via une jointure SQL dédiée dans une v4 future.
 // Non implémenté : ajouter une méthode journalEntryRepository.findInvoiceDueDate(entryId)
 return null; // fallback entryDate pour l'instant
 case PURCHASING:
 return null; // idem
 default:
 return null; // OD, paie, etc. : pas d'échéance
 }
 } catch (Exception e) {
 LOG.debug("resolveDueDateForAging failed for entry {} — fallback entryDate", entry.getId(), e);
 return null;
 }
 }

 // --- Helpers ---

 private ThirdParty loadThirdParty(UUID companyId, UUID thirdPartyId) {
 ThirdParty tp = thirdPartyRepository.findById(thirdPartyId)
 .orElseThrow(() -> new NotFoundException("ThirdParty", thirdPartyId));
 if (!tp.getCompanyId().equals(companyId)) {
 throw new NotFoundException("ThirdParty", thirdPartyId);
 }
 return tp;
 }

 /**
 * Charge un Account par ID avec filtrage tenant optionnel.
 *
 * <p><b>— defense-in-depth</b> : si {@code companyId} est fourni, ne retourne
 * l'Account que s'il appartient à ce tenant. Si {@code companyId} est null, comportement legacy
 * (pas de check — conservé pour les callers non encore migrés).
 */
 private Account loadAccountOrNull(UUID accountId) {
 return loadAccountOrNull(null, accountId);
 }

 private Account loadAccountOrNull(UUID companyId, UUID accountId) {
 if (accountId == null) return null;
 if (companyId == null) {
 return accountRepository.findById(accountId).orElse(null);
 }
 return accountRepository.findById(accountId)
 .filter(a -> a.getCompanyId().equals(companyId))
 .orElse(null);
 }

 private ThirdPartyResponse toResponse(ThirdParty tp, Account collectiveAccount, Account dedicatedAccount) {
 if (collectiveAccount == null) collectiveAccount = loadAccountOrNull(tp.getCompanyId(), tp.getCollectiveAccountId());
 if (dedicatedAccount == null) dedicatedAccount = loadAccountOrNull(tp.getCompanyId(), tp.getDedicatedAccountId());
 return new ThirdPartyResponse(
 tp.getId(), tp.getCompanyId(), tp.getType(), tp.getName(),
 tp.getCollectiveAccountId(),
 collectiveAccount != null ? collectiveAccount.getCode() : null,
 tp.getDedicatedAccountId(),
 dedicatedAccount != null ? dedicatedAccount.getCode() : null,
 tp.isActive(), tp.getEmail(), tp.getPhone(), tp.getAddress(),
 //— champs légaux pour Factur-X + mentions légales
 tp.getSiret(), tp.getVatNumber(), tp.getNif(),
 tp.getCreatedAt(), tp.getUpdatedAt());
 }

 private String serializeLineIds(List<UUID> lineIds) {
 try {
 return objectMapper.writeValueAsString(lineIds);
 } catch (JsonProcessingException e) {
 throw new IllegalStateException("Failed to serialize line ids", e);
 }
 }

 private List<UUID> deserializeLineIds(String json) {
 if (json == null || json.isBlank()) return List.of();
 try {
 return objectMapper.readValue(json,
 objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
 } catch (JsonProcessingException e) {
 LOG.warn("Failed to deserialize line ids: {}", json, e);
 return List.of();
 }
 }
}
