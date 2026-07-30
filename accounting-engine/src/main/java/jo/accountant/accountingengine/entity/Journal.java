package jo.accountant.accountingengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Journal comptable (§13 Phase 5).
 *
 * <p>Exemples : VT (ventes), AC (achats), BQ (banque), OD (opérations diverses).
 *
 * <p>Ne porte plus de {@code sequenceFormat} depuis la v2.1 — la configuration du format de
 * numérotation vit désormais uniquement dans {@code :document-numbering}
 * ({@code DocumentSequenceConfig.scopeKey} = code journal).
 *
 * <p><b>V8.2 (audit Z.ai 2026-07-31, Phase 3)</b> — ajout de deux champs :
 * <ul>
 *   <li>{@code type} ({@link JournalType}) — remplace la convention implicite sur le code.
 *       Permet à {@code AccountingEngineService.getOrCreateJournal(companyId, JournalType)}
 *       de résoudre un journal par type plutôt que par code.</li>
 *   <li>{@code active} (boolean) — permet de désactiver un journal sans le supprimer
 *       (préserve l'intégrité référentielle des écritures passées). Avant V8.2, la seule
 *       option était la suppression physique, impossible si des écritures référencent le journal.</li>
 * </ul>
 *
 * <p>La contrainte unique {@code uc_journal_company_code} (company_id, code) est conservée —
 * deux journaux d'une même société ne peuvent pas avoir le même code. Le type n'a pas de
 * contrainte unique (un tenant peut théoriquement avoir plusieurs journaux BANQUE pour
 * différents comptes bancaires, tous de type BANQUE mais avec des codes BQ1, BQ2, etc.).
 */
@Entity
@Table(name = "journal",
    uniqueConstraints = @UniqueConstraint(name = "uc_journal_company_code",
        columnNames = {"company_id", "code"}))
public class Journal extends TenantAwareEntity {

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    /**
     * Type de journal (V8.2 Phase 3). Null pour les journaux personnalisés (créés manuellement
     * par l'admin avec un code non-standard). Renseigné automatiquement par
     * {@code getOrCreateJournal} et par l'activation atomique du wizard V8.2.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 15)
    private JournalType type;

    /**
     * Indique si le journal est actif (V8.2 Phase 3). Un journal inactif n'accepte plus de
     * nouvelles écritures mais conserve son historique. Defaults to true.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public JournalType getType() { return type; }
    public void setType(JournalType type) { this.type = type; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
