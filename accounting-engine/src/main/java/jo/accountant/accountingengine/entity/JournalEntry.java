package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Écriture comptable (§13.
 *
 * <p>Une écriture est composée d'au moins 2 {@link JournalLine lignes} dont la somme des
 * débits doit égaler la somme des crédits (vérifié en application ET par trigger DB).
 *
 * <p>Cycle de vie :
 * <ol>
 * <li>Création en {@link JournalEntryStatus#DRAFT} — pas encore de {@code reference}.</li>
 * <li>Transition vers {@link JournalEntryStatus#POSTED} via {@code post()} :
 * <ul>
 * <li>Si une {@code ApprovalRule} active s'applique au montant total et que ce
 * montant &gt; seuil → transition vers {@link JournalEntryStatus#PENDING_APPROVAL}.
 * Le {@code reference} n'est PAS encore attribué — il le sera au moment de la
 * transition finale vers POSTED (après approbation).</li>
 * <li>Sinon (pas de règle, ou montant ≤ seuil) → postage direct en POSTED, et
 * {@code reference} attribué via {@code :document-numbering}.</li>
 * </ul>
 * </li>
 * <li>Une fois POSTED, l'écriture est <strong>immuable</strong>. Correction uniquement
 * par contre-passation : une nouvelle écriture est créée avec
 * {@link #reversalOfEntryId} pointant vers l'originale, et l'originale passe à
 * {@link JournalEntryStatus#VOIDED} (conserve son numéro — règle de numérotation
 * sans trou, §6).</li>
 * </ol>
 *
 * <p>Idempotence : {@link #idempotencyKey} est unique par entreprise. Deux requêtes avec
 * la même clé renvoient le même résultat, jamais de doublon (§3.10).
 *
 * <p><b> — Rich aggregate methods</b> : cette entité était auparavant
 * 100 % anemic (getters/setters uniquement) avec les invariants (équilibre débit=crédit,
 * transitions d'état) éparpillés dans les services. Les méthodes {@link #addLine(JournalLine)},
 * {@link #post()} et {@link #voidEntry(String)} encapsulent désormais ces invariants au niveau
 * de l'aggregate root. Les setters existants sont conservés pour backward compat (les services
 * existants les utilisent) — les méthodes métier sont ajoutées en plus pour usage progressif.
 */
@Entity
@Table(name = "journal_entry",
 uniqueConstraints = {
 @UniqueConstraint(name = "uc_je_company_idempotency",
 columnNames = {"company_id", "idempotency_key"})
 })
/**
 * JournalEntry.
 *
 * @author jo@Dev


 */

public class JournalEntry extends TenantAwareEntity {

 @Column(name = "journal_id", nullable = false)
 private UUID journalId;

 @Column(name = "fiscal_period_id", nullable = false)
 private UUID fiscalPeriodId;

 @Column(name = "entry_date", nullable = false)
 private LocalDate entryDate;

 /**
 * Numéro de l'écriture — généré via {@code :document-numbering} au moment précis de la
 * transition vers POSTED. {@code null} tant que l'écriture est DRAFT ou PENDING_APPROVAL.
 */
 @Column(name = "reference", length = 50)
 private String reference;

 @Column(name = "description", length = 500)
 private String description;

 @Enumerated(EnumType.STRING)
 @Column(name = "status", nullable = false, length = 20)
 private JournalEntryStatus status = JournalEntryStatus.DRAFT;

 @Column(name = "posted_at")
 private Instant postedAt;

 @Column(name = "posted_by")
 private UUID postedBy;

 /** Si cette écriture est une contre-passation, pointe vers l'originale. */
 @Column(name = "reversal_of_entry_id")
 private UUID reversalOfEntryId;

 @Enumerated(EnumType.STRING)
 @Column(name = "source_module", nullable = false, length = 20)
 private JournalEntrySourceModule sourceModule = JournalEntrySourceModule.MANUAL;

 /** Idempotence (§3.10) — un rejeu de la même clé renvoie le même résultat. */
 @Column(name = "idempotency_key", nullable = false, length = 100)
 private String idempotencyKey;

 /**
 * — Raison de l'annulation (contre-passation). Null si l'écriture
 * n'est pas VOIDED. Colonne ajoutée par la migration V72.
 */
 @Column(name = "void_reason", length = 500)
 private String voidReason;

 /**
 * — Collection transiente (non persistée) des lignes de l'écriture.
 *
 * <p>Les lignes sont persistées séparément via {@link JournalLine} (table {@code journal_line}
 * avec FK {@code journal_entry_id}). Cette collection transiente permet aux méthodes métier
 * ({@link #addLine(JournalLine)}, {@link #post()}) de valider les invariants d'aggregate
 * (cohérence companyId, équilibre débit=crédit) sans avoir à recharger les lignes depuis la DB.
 *
 * <p><b>Usage</b> : un service qui veut utiliser les méthodes métier doit d'abord peupler
 * cette collection (typiquement via {@code journalLineRepository.findByJournalEntryId(id)}
 * puis {@code entry.setLines(lines)}). Si la collection reste vide, {@link #post()} lèvera
 * une {@link IllegalStateException} (invariant "pas de postage sans lignes").
 *
 * <p><b>Backward compat</b> : les services existants qui utilisent les setters directs
 * (setStatus, etc.) ne sont pas affectés — cette collection n'est lue que par les méthodes
 * métier ajoutées en .
 */
 @Transient
 private List<JournalLine> lines = new ArrayList<>();

 public UUID getJournalId() { return journalId; }
 public void setJournalId(UUID journalId) { this.journalId = journalId; }

 public UUID getFiscalPeriodId() { return fiscalPeriodId; }
 public void setFiscalPeriodId(UUID fiscalPeriodId) { this.fiscalPeriodId = fiscalPeriodId; }

 public LocalDate getEntryDate() { return entryDate; }
 public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }

 public String getReference() { return reference; }
 public void setReference(String reference) { this.reference = reference; }

 public String getDescription() { return description; }
 public void setDescription(String description) { this.description = description; }

 public JournalEntryStatus getStatus() { return status; }
 public void setStatus(JournalEntryStatus status) { this.status = status; }

 public Instant getPostedAt() { return postedAt; }
 public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }

 public UUID getPostedBy() { return postedBy; }
 public void setPostedBy(UUID postedBy) { this.postedBy = postedBy; }

 public UUID getReversalOfEntryId() { return reversalOfEntryId; }
 public void setReversalOfEntryId(UUID reversalOfEntryId) { this.reversalOfEntryId = reversalOfEntryId; }

 public JournalEntrySourceModule getSourceModule() { return sourceModule; }
 public void setSourceModule(JournalEntrySourceModule sourceModule) { this.sourceModule = sourceModule; }

 public String getIdempotencyKey() { return idempotencyKey; }
 public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

 /** Raison de l'annulation (VOIDED). Null si l'écriture n'est pas VOIDED. */
 public String getVoidReason() { return voidReason; }
 public void setVoidReason(String voidReason) { this.voidReason = voidReason; }

 /**
 * Collection transiente des lignes de l'écriture (cf. {@link #lines}).
 * Jamais null (initialisée à une liste vide). Les services qui peuplent cette collection
 * doivent passer par ce setter (typiquement avec une liste non-modifiable ou une copie
 * défensive si la mutation ultérieure est à éviter).
 */
 public List<JournalLine> getLines() { return lines; }
 public void setLines(List<JournalLine> lines) {
 this.lines = lines != null ? lines : new ArrayList<>();
 }

 // ════════════════════════════════════════════════════════════════════════
 // — Méthodes métier (rich aggregate)
 // ════════════════════════════════════════════════════════════════════════

 /**
 * Ajoute une ligne à l'écriture. Garantit les invariants d'aggregate :
 * <ul>
 * <li>La ligne hérite du {@code companyId} de l'écriture (invariant multi-tenant).</li>
 * <li>Si l'écriture est {@link JournalEntryStatus#POSTED}, lève
 * {@link IllegalStateException} (impossible de modifier une entrée postée —
 * correction uniquement par contre-passation via {@link #voidEntry(String)}).</li>
 * <li>La ligne est rattachée à l'écriture via {@code journalEntryId}.</li>
 * </ul>
 *
 * <p><b>Backward compat</b> : les services existants qui créent les {@link JournalLine}
 * séparément via {@code JournalLineRepository} ne sont pas affectés. Cette méthode est
 * destinée à un usage progressif pour centraliser la logique métier dans l'aggregate.
 *
 * @param line la ligne à ajouter (doit avoir un {@code companyId} matching)
 * @throws IllegalStateException si l'écriture est POSTED ou si le {@code companyId} de la
 * ligne ne correspond pas à celui de l'écriture
 */
 public void addLine(JournalLine line) {
 if (this.status == JournalEntryStatus.POSTED) {
 throw new IllegalStateException(
 "Cannot add line to POSTED entry: " + this.getId());
 }
 if (line == null) {
 throw new IllegalArgumentException("Line cannot be null");
 }
 if (this.getCompanyId() != null && !this.getCompanyId().equals(line.getCompanyId())) {
 throw new IllegalStateException(
 "Line companyId does not match entry companyId: entry="
 + this.getCompanyId() + " line=" + line.getCompanyId());
 }
 // Propager le companyId de l'entrée à la ligne si la ligne n'en a pas (invariant tenant)
 if (line.getCompanyId() == null && this.getCompanyId() != null) {
 line.setCompanyId(this.getCompanyId());
 }
 this.lines.add(line);
 line.setJournalEntryId(this.getId());
 }

 /**
 * Transition {@link JournalEntryStatus#DRAFT} ou {@link JournalEntryStatus#PENDING_APPROVAL}
 * → {@link JournalEntryStatus#POSTED}. Garantit l'invariant d'équilibre débit=crédit avant
 * la transition.
 *
 * <p>Vérifications (lèvent {@link IllegalStateException}) :
 * <ul>
 * <li>L'écriture doit être en statut DRAFT ou PENDING_APPROVAL (impossible de poster une
 * écriture déjà POSTED ou VOIDED).</li>
 * <li>L'écriture doit avoir au moins une ligne (cf. {@link #lines} — la collection
 * transiente doit être peuplée par le service appelant avant d'appeler cette méthode).</li>
 * <li>Total débit = total crédit (équilibre comptable obligatoire).</li>
 * </ul>
 *
 * <p><b>Effets de bord</b> : cette méthode ne peuple PAS {@code reference}, {@code postedAt}
 * ni {@code postedBy} — ces champs dépendent de services externes (document-numbering,
 * TenantContext) qui restent la responsabilité du service appelant. La méthode ne fait que
 * valider les invariants et transitionner le statut.
 *
 * @throws IllegalStateException si l'écriture n'est pas en DRAFT/PENDING_APPROVAL, n'a aucune
 * ligne, ou est déséquilibrée
 */
 public void post() {
 if (this.status != JournalEntryStatus.DRAFT
 && this.status != JournalEntryStatus.PENDING_APPROVAL) {
 throw new IllegalStateException("Cannot post entry in status: " + this.status);
 }
 if (this.lines == null || this.lines.isEmpty()) {
 throw new IllegalStateException("Cannot post entry without lines: " + this.getId());
 }
 BigDecimal totalDebit = this.lines.stream()
 .map(JournalLine::getDebit)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 BigDecimal totalCredit = this.lines.stream()
 .map(JournalLine::getCredit)
 .reduce(BigDecimal.ZERO, BigDecimal::add);
 if (totalDebit.compareTo(totalCredit) != 0) {
 throw new IllegalStateException(
 "Unbalanced entry: debit=" + totalDebit + " credit=" + totalCredit
 + " (entryId=" + this.getId() + ")");
 }
 this.status = JournalEntryStatus.POSTED;
 }

 /**
 * Transition {@link JournalEntryStatus#POSTED} → {@link JournalEntryStatus#VOIDED} avec
 * une raison obligatoire.
 *
 * <p>L'annulation d'une écriture POSTED se fait par contre-passation : une nouvelle écriture
 * est créée (avec {@link #reversalOfEntryId} pointant vers l'originale), et l'originale
 * passe à VOIDED. La raison est stockée dans {@link #voidReason} pour audit.
 *
 * <p><b>Invariants</b> :
 * <ul>
 * <li>L'écriture doit être en statut POSTED (impossible d'annuler une DRAFT ou déjà VOIDED).</li>
 * <li>La raison est obligatoire (non null, non blank) — traçabilité audit.</li>
 * </ul>
 *
 * @param reason raison de l'annulation (obligatoire, max 500 caractères)
 * @throws IllegalStateException si l'écriture n'est pas en POSTED
 * @throws IllegalArgumentException si la raison est null, blank, ou dépasse 500 caractères
 */
 public void voidEntry(String reason) {
 if (this.status != JournalEntryStatus.POSTED) {
 throw new IllegalStateException(
 "Cannot void entry in status: " + this.status + " (entryId=" + this.getId() + ")");
 }
 if (reason == null || reason.isBlank()) {
 throw new IllegalArgumentException("Void reason is required (entryId=" + this.getId() + ")");
 }
 if (reason.length() > 500) {
 throw new IllegalArgumentException(
 "Void reason exceeds 500 characters (entryId=" + this.getId() + ")");
 }
 this.status = JournalEntryStatus.VOIDED;
 this.voidReason = reason;
 }
}

