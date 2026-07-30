package jo.accountant.chartofaccounts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Gabarit de numérotation d'un plan comptable libre (§4, §13 Phase 3).
 *
 * <p>Une seule ligne par entreprise (relation 1-1). Utilisée UNIQUEMENT pour les référentiels
 * à numérotation libre ({@link jo.accountant.core.framework.NumberingMode#FREE}) — IFRS full
 * et IFRS SMEs. Les référentiels à numérotation imposée (SYSCOHADA, PCG, PCN, PCGR) ignorent
 * ce gabarit : leurs classes sont définies par le texte réglementaire, dans
 * {@link jo.accountant.core.framework.AccountingFramework#getMandatedClassSeedJson()}.
 *
 * <p><strong>À ne pas confondre</strong> avec {@code DocumentSequenceConfig} (Phase 2,
 * {@code document-numbering}) qui génère des numéros de documents séquentiels (ex.
 * {@code FAC-2026-000142}). {@code AccountNumberingTemplate} génère des codes de comptes
 * hiérarchiques (ex. {@code 411000}). Les deux mécanismes ne partagent ni entité ni service,
 * conformément au §6 du prompt maître.
 *
 * <p>Champs :
 * <ul>
 *   <li>{@link #codeLengthLevel1} à {@link #codeLengthLevel4} : longueur (en caractères) du
 *       code à chaque niveau. Ex. SYSCOHADA-like : 1, 2, 3, 6. Le code d'un compte de niveau
 *       4 est donc de longueur {@code codeLengthLevel4}.</li>
 *   <li>{@link #spacingStep} : pas d'espacement pour l'affichage (typiquement 3 — un espace
 *       tous les 3 caractères). Purement cosmétique, ne change pas le code stocké.</li>
 * </ul>
 */
@Entity
@Table(name = "account_numbering_template",
    uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uc_account_numbering_template_company",
        columnNames = "company_id"))
public class AccountNumberingTemplate extends TenantAwareEntity {

    @Column(name = "accounting_framework_id", nullable = false)
    private UUID accountingFrameworkId;

    @Column(name = "code_length_level_1", nullable = false)
    private int codeLengthLevel1 = 1;

    @Column(name = "code_length_level_2", nullable = false)
    private int codeLengthLevel2 = 2;

    @Column(name = "code_length_level_3", nullable = false)
    private int codeLengthLevel3 = 3;

    @Column(name = "code_length_level_4", nullable = false)
    private int codeLengthLevel4 = 6;

    /** Pas d'espacement pour l'affichage (typiquement 3). 0 = pas d'espacement. */
    @Column(name = "spacing_step", nullable = false)
    private int spacingStep = 3;

    public UUID getAccountingFrameworkId() { return accountingFrameworkId; }
    public void setAccountingFrameworkId(UUID accountingFrameworkId) {
        this.accountingFrameworkId = accountingFrameworkId;
    }

    public int getCodeLengthLevel1() { return codeLengthLevel1; }
    public void setCodeLengthLevel1(int codeLengthLevel1) { this.codeLengthLevel1 = codeLengthLevel1; }

    public int getCodeLengthLevel2() { return codeLengthLevel2; }
    public void setCodeLengthLevel2(int codeLengthLevel2) { this.codeLengthLevel2 = codeLengthLevel2; }

    public int getCodeLengthLevel3() { return codeLengthLevel3; }
    public void setCodeLengthLevel3(int codeLengthLevel3) { this.codeLengthLevel3 = codeLengthLevel3; }

    public int getCodeLengthLevel4() { return codeLengthLevel4; }
    public void setCodeLengthLevel4(int codeLengthLevel4) { this.codeLengthLevel4 = codeLengthLevel4; }

    public int getSpacingStep() { return spacingStep; }
    public void setSpacingStep(int spacingStep) { this.spacingStep = spacingStep; }

    /** Longueur attendue du code selon le niveau. */
    public int codeLengthForLevel(int level) {
        return switch (level) {
            case 1 -> codeLengthLevel1;
            case 2 -> codeLengthLevel2;
            case 3 -> codeLengthLevel3;
            case 4 -> codeLengthLevel4;
            default -> throw new IllegalArgumentException("Niveau hors plage : " + level);
        };
    }
}
