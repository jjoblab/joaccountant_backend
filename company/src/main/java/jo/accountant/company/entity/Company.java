package jo.accountant.company.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entité Company (§13restructurée en 2026-07-24, voir
 * {@code PROMPT_AGENT_restructuration_type_organisation}).
 *
 * <p>N'EST PAS une {@link jo.accountant.core.tenant.TenantAwareEntity} — une Company EST le
 * tenant lui-même, elle n'a pas de colonne {@code company_id}.
 *
 * <p>Restructuration : la modélisation organisationnelle s'articule désormais autour de 5 axes
 * distincts (§4.2 du prompt de restructuration) :
 * <ul>
 * <li><em>organizationNature</em> — lucratif / non-lucratif (0 : domaine réduit à 2 valeurs,
 * voir {@link OrganizationNature} + migration {@code V101__simplify_organization_nature.sql}).
 * Filtre les formes juridiques valides (ex. {@code ASSOCIATION}/{@code NGO} ⟹
 * {@code NON_PROFIT} uniquement).</li>
 * <li><em>legalForm</em> — juridique pur (SARL, SA, ASSOCIATION...), validée contre la nature.</li>
 * <li><em>sector</em> — classification large, <strong>purement descriptive</strong> — ne
 * pilote plus l'activation des modules (rôle transféré à {@code businessTypeCode}).</li>
 * <li><em>primaryActivityLabel</em> — libellé libre décrivant l'activité réelle.</li>
 * <li><em>businessTypeCode</em> — LE moteur : pointe vers une entrée {@link BusinessType}
 * qui détermine la liste de modules à activer + les champs additionnels obligatoires.</li>
 * </ul>
 *
 * <p>Les champs {@code organizationNature}, {@code legalForm}, {@code sector},
 * {@code businessTypeCode} et les valeurs de {@code extraAttributes} sont verrouillés une
 * fois {@code wizardCompleted = true} (§4.2 — extension de l'ancienne règle sur {@code sector}
 * seul).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "companies")
public class Company {

 /**
 * Nombre total d'étapes du wizard d'onboarding (refonte 4 étapes avec activation atomique).
 *
 * <p>Le wizard d'onboarding fusionne les 9 étapes historiques en 4 étapes :
 * <ol>
 * <li><b>Identité</b> — name + country + functionalCurrency (création via POST /companies)</li>
 * <li><b>Activité</b> — businessTypeCode + primaryActivityLabel + sector + extraAttributes
 * (PATCH /wizard/2 avec {@code WizardStep2Request})</li>
 * <li><b>Comptabilité</b> — accountingFrameworkId + fiscalYearStart + vatMode + numberingPrefixes
 * (PATCH /wizard/3 avec {@code WizardStep3Request})</li>
 * <li><b>Activation atomique</b> — modules + plan comptable + exercice + journaux + séquences + TVA
 * (POST /wizard/complete avec {@code CompleteWizardRequest} → {@code CompanyWizardResult})</li>
 * </ol>
 *
 * <p>Toute référence au nombre d'étapes (validation de {@code WizardStepRequest},
 * {@code CompanyService.updateWizardStep} / {@code completeWizard}) doit pointer vers
 * cette constante plutôt que vers un littéral {@code 4} en dur.
 *
 * <p><b>Historique</b> : V95__wizard_refonte_4_steps.sql a déjà clampé wizard_step à 4 en base
 * (UPDATE companies SET wizard_step = LEAST(wizard_step, 4)). Cette constante Java s'aligne
 * enfin sur la sémantique DB — corrigeant le bug 409 WIZARD_STEP_INCOMPLETE qui bloquait
 * completeWizard quand wizard_step valait 4 mais TOTAL_WIZARD_STEPS valait encore 9.
 */
 public static final int TOTAL_WIZARD_STEPS = 4;

 @Id
 @Column(name = "id", nullable = false, updatable = false)
 private UUID id;

 @Column(name = "name", nullable = false)
 private String name;

 @Enumerated(EnumType.STRING)
 @Column(name = "legal_form", nullable = false)
 private LegalForm legalForm;

 @Column(name = "country", nullable = false, length = 2)
 private String country;

 @Column(name = "functional_currency", nullable = false, length = 3)
 private String functionalCurrency;

 /**
 * SIRET de l'entreprise (14 chiffres en France).Finding HAUT — requis pour
 * les mentions légales des factures (CGI art. 289) et le Factur-X. Null si non configuré
 * (ex: entreprise haïtienne sans SIRET — utilise NIF à la place).
 */
 @Column(name = "siret", length = 20)
 private String siret;

 /**
 * Numéro de TVA intracommunautaire (ex: FR12345678901 pour la France).—
 * requis pour les factures B2B intra-UE (reverse-charge) et le Factur-X. Null si non assujetti.
 */
 @Column(name = "vat_number", length = 20)
 private String vatNumber;

 /**
 * NIF (Numéro d'Identification Fiscale) — équivalent SIRET pour les entreprises hors France
 * (Haïti, OHADA).— utilisé comme fallback SIRET dans les mentions légales.
 */
 @Column(name = "nif", length = 30)
 private String nif;

 /**
 * Adresse postale de l'entreprise (une ligne).— requise pour les mentions
 * légales des factures (CGI art. 289). Format libre : "123 rue de la Paix, 75001 Paris".
 */
 @Column(name = "address", length = 500)
 private String address;

 /**
 * Secteur d'activité — <strong>purement descriptif</strong> depuis la restructuration.
 * Ne pilote plus l'activation des modules (rôle transféré à {@code businessTypeCode}).
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "sector", nullable = false)
 private Sector sector;

 /**
 * Nature de l'organisation — filtre les formes juridiques valides et oriente le régime
 * fiscal (§4.2 du prompt de restructuration).
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "organization_nature", nullable = false)
 private OrganizationNature organizationNature;

 /**
 * Type métier — LE moteur d'activation des modules. Référence vers
 * {@link BusinessType#getCode()}. Le type {@code CUSTOM} remplace l'ancien secteur
 * {@code MIXTE} et permet la sélection manuelle des modules à l'du wizard.
 */
 @Column(name = "business_type_code", nullable = false, length = 60)
 private String businessTypeCode;

 /** Libellé libre décrivant l'activité réelle (« École primaire privée »...). */
 @Column(name = "primary_activity_label", nullable = false, length = 300)
 private String primaryActivityLabel;

 /**
 * Valeurs des champs additionnels définis par {@code BusinessTypeRequiredField} pour le
 * {@code businessTypeCode} choisi. Stockage JSONB (PostgreSQL natif).
 */
 @JdbcTypeCode(SqlTypes.JSON)
 @Column(name = "extra_attributes", columnDefinition = "jsonb")
 private Map<String, Object> extraAttributes;

 /**
 * Référentiel comptable — positionné à l'du wizard.
 * Nullable tant que l'utilisateur n'a pas atteint cette étape.
 */
 @Column(name = "accounting_framework_id")
 private UUID accountingFrameworkId;

 @Column(name = "fiscal_year_start_month", nullable = false)
 private int fiscalYearStartMonth;

 /**
 * V77 (R-F-validationTRUE si la société est agréée zone franche
 * (CODEVI / SONAPI — Code Fiscal Haïti art. 195). IS réduit 15% au lieu de 30%.
 *
 * <p>Colonne créée en migration V77 ; champ JPA exposé en v8-1 pour permettre à
 * {@code TaxService.resolveCorporateTaxRule()} de router vers la règle ZF.
 */
 @Column(name = "is_free_zone", nullable = false)
 private boolean freeZone = false;

 /**
 * v8-1 — Statut d'exonération fiscale (Code Fiscal Haïti art. 195).
 *
 * <p>Valeurs : {@link TaxExemptionStatus#STANDARD STANDARD} (IS 30% Haïti / 25% France),
 * {@link TaxExemptionStatus#FREE_ZONE FREE_ZONE} (IS 15% zone franche),
 * {@link TaxExemptionStatus#NGO_EXEMPT NGO_EXEMPT} (IS 0% ONG).
 *
 * <p>Colonne créée en migration V91. Valeur par défaut {@code STANDARD}.
 */
 @Enumerated(EnumType.STRING)
 @Column(name = "tax_exemption_status", nullable = false, length = 20)
 private TaxExemptionStatus taxExemptionStatus = TaxExemptionStatus.STANDARD;

 /**
 * Module Démos : TRUE si entreprise fictive (mode démo, lecture seule publique).
 * Colonne créée en migration V94 (module :demo-data).
 */
 @Column(name = "is_demo", nullable = false)
 private Boolean isDemo = false;

 public Boolean getIsDemo() { return isDemo; }
 public void setIsDemo(Boolean isDemo) { this.isDemo = isDemo != null ? isDemo : false; }
 public boolean isDemo() { return Boolean.TRUE.equals(isDemo); }

 /**
 * Lot B Durée légale mensuelle de travail en heures.
 * <ul>
 * <li>France (35h/sem × 52/12) : 173.33h (défaut — rétro-compatibilité).</li>
 * <li>Haïti (48h/sem × 52/12) : 208h.</li>
 * <li>Canada (40h/sem × 52/12) : 173.33h.</li>
 * </ul>
 * Utilisée par {@code PayrollCalculator} pour calculer le taux horaire de référence
 * ({@code hourlyRate = baseSalary / monthlyLegalHours}). Avant la , cette valeur était
 * hardcodée à 173.33 dans PayrollCalculator — une entreprise haïtienne voyait donc son taux
 * horaire sur-estimé (baseSalary / 173.33 au lieu de baseSalary / 208), ce qui sous-évaluait
 * les majorations HS et le salaire brut.
 */
 @Column(name = "monthly_legal_hours", precision = 5, scale = 2)
 private BigDecimal monthlyLegalHours;

 @Column(name = "wizard_step", nullable = false)
 private int wizardStep = 1;

 @Column(name = "wizard_completed", nullable = false)
 private boolean wizardCompleted = false;

 @Column(name = "created_at", nullable = false, updatable = false)
 private Instant createdAt;

 @Column(name = "updated_at", nullable = false)
 private Instant updatedAt;

 @Column(name = "created_by")
 private UUID createdBy;

 @Column(name = "updated_by")
 private UUID updatedBy;

 @Version
 @Column(name = "version", nullable = false)
 private long version;

 public UUID getId() { return id; }
 public void setId(UUID id) { this.id = id; }
 public String getName() { return name; }
 public void setName(String name) { this.name = name; }
 public LegalForm getLegalForm() { return legalForm; }
 public void setLegalForm(LegalForm legalForm) { this.legalForm = legalForm; }
 public String getCountry() { return country; }
 public void setCountry(String country) { this.country = country; }
 public String getFunctionalCurrency() { return functionalCurrency; }
 public void setFunctionalCurrency(String functionalCurrency) { this.functionalCurrency = functionalCurrency; }
 public String getSiret() { return siret; }
 public void setSiret(String siret) { this.siret = siret; }
 public String getVatNumber() { return vatNumber; }
 public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }
 public String getNif() { return nif; }
 public void setNif(String nif) { this.nif = nif; }
 public String getAddress() { return address; }
 public void setAddress(String address) { this.address = address; }
 public Sector getSector() { return sector; }
 public void setSector(Sector sector) { this.sector = sector; }
 public OrganizationNature getOrganizationNature() { return organizationNature; }
 public void setOrganizationNature(OrganizationNature n) { this.organizationNature = n; }
 public String getBusinessTypeCode() { return businessTypeCode; }
 public void setBusinessTypeCode(String code) { this.businessTypeCode = code; }
 public String getPrimaryActivityLabel() { return primaryActivityLabel; }
 public void setPrimaryActivityLabel(String label) { this.primaryActivityLabel = label; }
 public Map<String, Object> getExtraAttributes() { return extraAttributes; }
 public void setExtraAttributes(Map<String, Object> attrs) { this.extraAttributes = attrs; }
 public UUID getAccountingFrameworkId() { return accountingFrameworkId; }
 public void setAccountingFrameworkId(UUID accountingFrameworkId) { this.accountingFrameworkId = accountingFrameworkId; }
 public int getFiscalYearStartMonth() { return fiscalYearStartMonth; }
 public void setFiscalYearStartMonth(int fiscalYearStartMonth) { this.fiscalYearStartMonth = fiscalYearStartMonth; }

 /** V77 — TRUE si société agréée zone franche (Code Fiscal art. 195). */
 public boolean isFreeZone() { return freeZone; }
 public void setFreeZone(boolean freeZone) { this.freeZone = freeZone; }

 /** v8-1 — Statut d'exonération fiscale (STANDARD / FREE_ZONE / NGO_EXEMPT). */
 public TaxExemptionStatus getTaxExemptionStatus() { return taxExemptionStatus; }
 public void setTaxExemptionStatus(TaxExemptionStatus taxExemptionStatus) {
 this.taxExemptionStatus = taxExemptionStatus;
 }

 public BigDecimal getMonthlyLegalHours() { return monthlyLegalHours; }
 public void setMonthlyLegalHours(BigDecimal monthlyLegalHours) { this.monthlyLegalHours = monthlyLegalHours; }
 public int getWizardStep() { return wizardStep; }
 public void setWizardStep(int wizardStep) { this.wizardStep = wizardStep; }
 public boolean isWizardCompleted() { return wizardCompleted; }
 public void setWizardCompleted(boolean wizardCompleted) { this.wizardCompleted = wizardCompleted; }
 public Instant getCreatedAt() { return createdAt; }
 public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
 public Instant getUpdatedAt() { return updatedAt; }
 public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
 public UUID getCreatedBy() { return createdBy; }
 public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
 public UUID getUpdatedBy() { return updatedBy; }
 public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
 public long getVersion() { return version; }
 public void setVersion(long version) { this.version = version; }
}
