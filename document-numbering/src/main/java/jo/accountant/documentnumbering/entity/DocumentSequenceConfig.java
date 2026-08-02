package jo.accountant.documentnumbering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Configuration d'une séquence de numérotation documentaire (§6).
 *
 * <p>Un enregistrement par couple (companyId, documentType, scopeKey). Le {@code scopeKey}
 * permet de définir des séquences indépendantes pour un même type de document — par exemple,
 * un journal comptable {@code VT} (ventes) et un journal {@code AC} (achats) ont chacun leur
 * propre séquence d'écritures, même si les deux sont de type {@link DocumentType#JOURNAL_ENTRY}.
 *
 * <p>Le format du numéro généré est : {@code {prefix}[-{year}]-{number padded}} où :
 * <ul>
 * <li>{@code prefix} = {@link #prefix} (ex. {@code "FAC"}, {@code "VT"}, {@code "DON"})</li>
 * <li>{@code year} = année courante sur 4 chiffres si {@link #includeYear} = vrai</li>
 * <li>{@code number} = valeur du compteur, complétée par des zéros à gauche pour atteindre
 * {@link #padding} chiffres</li>
 * </ul>
 *
 * <p>Exemple : prefix=FAC, includeYear=true, padding=6 → {@code "FAC-2026-000142"}.
 *
 * <p>Entité {@link TenantAwareEntity} : le {@code companyId} est injecté depuis
 * {@link jo.accountant.core.tenant.TenantContext}, jamais accepté dans le corps d'une requête.
 */
@Entity
@Table(name = "document_sequence_config",
    uniqueConstraints = @UniqueConstraint(name = "uc_doc_seq_config",
        columnNames = {"company_id", "document_type", "scope_key"}))
/**
 * Configuration Spring DocumentSequence.
 *
 * @author jo@Dev


 */

public class DocumentSequenceConfig extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    /**
     * Clé de portée permettant des séquences indépendantes pour un même type de document.
     * Exemple : code journal ({@code "VT"}, {@code "AC"}, {@code "BQ"}, {@code "OD"}) pour
     * les écritures comptables. Vide ({@code ""}) si la séquence est unique par type.
     */
    @Column(name = "scope_key", nullable = false, length = 30)
    private String scopeKey = "";

    @Column(name = "prefix", nullable = false, length = 20)
    private String prefix;

    @Column(name = "include_year", nullable = false)
    private boolean includeYear = true;

    /** Nombre de chiffres du numéro, complété par des zéros à gauche. Typiquement 4 à 8. */
    @Column(name = "padding", nullable = false)
    private int padding = 6;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_policy", nullable = false, length = 10)
    private ResetPolicy resetPolicy = ResetPolicy.YEARLY;

    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) {
        this.scopeKey = scopeKey == null ? "" : scopeKey;
    }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public boolean isIncludeYear() { return includeYear; }
    public void setIncludeYear(boolean includeYear) { this.includeYear = includeYear; }

    public int getPadding() { return padding; }
    public void setPadding(int padding) { this.padding = padding; }

    public ResetPolicy getResetPolicy() { return resetPolicy; }
    public void setResetPolicy(ResetPolicy resetPolicy) { this.resetPolicy = resetPolicy; }
}
