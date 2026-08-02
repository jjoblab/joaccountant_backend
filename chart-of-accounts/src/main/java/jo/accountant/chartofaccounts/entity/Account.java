package jo.accountant.chartofaccounts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tenant.TenantAwareEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Compte du plan comptable d'une entreprise (§4, §13.
 *
 * <p>Hiérarchie auto-référentielle sur 4 niveaux maximum :
 * <ul>
 * <li>Niveau 1 — <strong>classe</strong> (ex. "1 - Ressources durables" en SYSCOHADA).
 * Verrouillé, généré automatiquement à l'initialisation du plan.</li>
 * <li>Niveau 2 — <strong>rubrique</strong> (ex. "10 - Capital"). Verrouillé, généré
 * automatiquement à l'initialisation.</li>
 * <li>Niveau 3 — <strong>compte principal</strong> (ex. "101 - Capital social").
 * Généré par défaut mais éditable.</li>
 * <li>Niveau 4 — <strong>compte divisionnaire / sous-compte</strong> (ex. "101100 -
 * Capital souscrit - non appelé). Entièrement libre, jamais généré par défaut.</li>
 * </ul>
 *
 * <p>La génération automatique des niveaux 1 et 2 dépend du référentiel
 * ({@link jo.accountant.core.framework.AccountingFramework#getNumberingMode()}) :
 * <ul>
 * <li>{@code MANDATED} (SYSCOHADA, PCG, PCN, PCGR) — depuis le {@code mandatedClassSeed}
 * du référentiel, qui liste les classes imposées par le texte réglementaire.</li>
 * <li>{@code FREE} (IFRS full, IFRS SMEs) — selon le gabarit
 * {@link AccountNumberingTemplate} configuré par l'entreprise.</li>
 * </ul>
 *
 * <p>Règles métier (toutes testées, §13* <ul>
 * <li>{@code code} unique par {@code companyId} (contrainte DB + validation applicative).</li>
 * <li>Renommage/suppression d'un compte {@code locked = true} → 409.</li>
 * <li>Pas de niveau &gt; 4 dans cette itération.</li>
 * <li>Génération de code enfant sans collision, même en création concurrente
 * (contrainte unique DB en filet de sécurité).</li>
 * <li>Suppression physique <strong>toujours interdite</strong> ; seule la désactivation
 * ({@code active = false}) est permise, et uniquement si le solde est nul
 * (vérifié via {@link jo.accountant.chartofaccounts.guard.AccountBalanceGuard},
 * implémenté en.</li>
 * </ul>
 *
 * <p>{@code path} est calculé à la création/modification — concaténation des codes depuis la
 * racine jusqu'au compte courant, séparés par des points (ex. {@code "1.10.101.101100"}).
 * Utile pour la recherche textuelle et l'affichage en arbre.
 *
 * <p>{@code requiresAnalyticalTagPlanIds} : liste d'IDs de plans analytiquespour
 * lesquels une ligne d'écriture postée sur ce compte DOIT porter une valeur analytique.
 * Mécanisme générique qui permet par exemple d'imposer qu'aucune charge ne soit postée sur un
 * compte de subvention sans indiquer le fonds concerné (cas ONG). Stocké en JSONB — pas de
 * table de jointure dédiée pour cette itération.
 *
 * <p>Entité {@link TenantAwareEntity} : le {@code companyId} est injecté depuis
 * {@link jo.accountant.core.tenant.TenantContext}, jamais accepté dans le corps d'une requête.
 */
@Entity
@Table(name = "account",
    uniqueConstraints = @UniqueConstraint(name = "uc_account_company_code",
        columnNames = {"company_id", "code"}))
/**
 * Account.
 *
 * @author jo@Dev


 */

public class Account extends TenantAwareEntity {

    /** Parent direct dans la hiérarchie. {@code null} pour un compte de niveau 1 (la classe). */
    @Column(name = "parent_id")
    private UUID parentId;

    /** Code du compte, unique par entreprise (ex. "411000", "101100"). */
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    /** Libellé du compte (ex. "Clients - Ventes de marchandises"). */
    @Column(name = "label", nullable = false, length = 200)
    private String label;

    /** Niveau hiérarchique (1 à 4). */
    @Column(name = "level", nullable = false)
    private int level;

    /** Classification universelle — ce que consomme {@code financial-statements}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reporting_class", nullable = false, length = 25)
    private ReportingClass reportingClass;

    /** Sous-catégorie universelle (nullable pour les comptes de regroupement). */
    @Enumerated(EnumType.STRING)
    @Column(name = "reporting_subcategory", length = 15)
    private ReportingSubcategory reportingSubcategory;

    /** Sens normal du solde. */
    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 10)
    private NormalBalance normalBalance;

    /**
     * Si {@code true}, le compte ne peut être ni renommé ni supprimé — typiquement les
     * classes et rubriques générées automatiquement.
     */
    @Column(name = "locked", nullable = false)
    private boolean locked = false;

    /**
     * Si {@code false}, le compte est désactivé et ne peut plus recevoir d'écritures.
     * La désactivation n'est permise QUE si le solde est nul
     * (cf. {@link jo.accountant.chartofaccounts.guard.AccountBalanceGuard}).
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Si {@code true}, le compte est "collectif" — un compte de regroupement (ex. 411000
     * "Clients"). Les écritures sont postées sur des comptes de tiers individuels,
     * pas directement sur le compte collectif.
     */
    @Column(name = "is_collective", nullable = false)
    private boolean isCollective = false;

    /**
     * Chemin complet depuis la racine, codes séparés par des points
     * (ex. {@code "1.10.101.101100"}). Calculé à la création/modification.
     */
    @Column(name = "path", nullable = false, length = 200)
    private String path;

    /**
     * Code de règle fiscale. Référence opaque vers {@code TaxRule.code} — pas de
     * FK dure pour permettre la suppression d'une règle fiscale sans casser le plan comptable.
     */
    @Column(name = "tax_mapping_code", length = 30)
    private String taxMappingCode;

    /**
     * Liste des IDs de plans analytiquespour lesquels une ligne d'écriture postée
     * sur ce compte DOIT porter une valeur analytique. Stocké en JSONB — pas de table de
     * jointure dédiée pour cette itération.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requires_analytical_tag_plan_ids", columnDefinition = "jsonb")
    private String requiresAnalyticalTagPlanIds;

    // --- Getters / setters ---

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public ReportingClass getReportingClass() { return reportingClass; }
    public void setReportingClass(ReportingClass reportingClass) { this.reportingClass = reportingClass; }

    public ReportingSubcategory getReportingSubcategory() { return reportingSubcategory; }
    public void setReportingSubcategory(ReportingSubcategory reportingSubcategory) { this.reportingSubcategory = reportingSubcategory; }

    public NormalBalance getNormalBalance() { return normalBalance; }
    public void setNormalBalance(NormalBalance normalBalance) { this.normalBalance = normalBalance; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isCollective() { return isCollective; }
    public void setCollective(boolean collective) { isCollective = collective; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getTaxMappingCode() { return taxMappingCode; }
    public void setTaxMappingCode(String taxMappingCode) { this.taxMappingCode = taxMappingCode; }

    public String getRequiresAnalyticalTagPlanIds() { return requiresAnalyticalTagPlanIds; }
    public void setRequiresAnalyticalTagPlanIds(String requiresAnalyticalTagPlanIds) {
        this.requiresAnalyticalTagPlanIds = requiresAnalyticalTagPlanIds;
    }
}
