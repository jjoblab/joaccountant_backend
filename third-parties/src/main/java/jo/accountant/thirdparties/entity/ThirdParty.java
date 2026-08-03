package jo.accountant.thirdparties.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Tiers — client, fournisseur, donateur, salarié ou autre (§13.
 *
 * <p>Chaque tiers est rattaché à un {@code compte collectif} (ex. 411000 "Clients" pour un
 * tiers de type CLIENT). Si le compte collectif a {@code isCollective = true}, un
 * {@code compte dédié} de niveau 4 est automatiquement généré sous le compte collectif
 * pour ce tiers (ex. 411000001 pour le client "Boutique Pétion-Ville").
 *
 * <p>Le compte dédié permet de suivre individuellement le solde de chaque tiers dans le
 * grand livre, sans mélanger les écritures de plusieurs tiers sur le même compte.
 *
 * <p>Les écritures de {@link jo.accountant.accountingengine.entity.JournalLine} référencent
 * le tiers via {@code thirdPartyId} — pas le compte dédié directement. Le moteur comptable
 * stocke les deux (accountId = compte dédié, thirdPartyId = ID du tiers) pour permettre
 * les requêtes par tiers ou par compte.
 *
 * <p>Entité {@link TenantAwareEntity}.
 */
@Entity
@Table(name = "third_party",
    uniqueConstraints = @UniqueConstraint(name = "uc_tp_company_dedicated_account",
        columnNames = {"company_id", "dedicated_account_id"}))
/**
 * ThirdParty.
 *
 * @author jo@Dev


 */

public class ThirdParty extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 12)
    private ThirdPartyType type;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * Compte collectif auquel le tiers est rattaché (ex. 411000 pour les clients).
     * Doit être un compte avec {@code isCollective = true}.
     */
    @Column(name = "collective_account_id", nullable = false)
    private UUID collectiveAccountId;

    /**
     * Compte dédié auto-généré sous le compte collectif. Nullable si le compte collectif
     * n'est pas {@code isCollective = true} (auquel cas les écritures sont postées directement
     * sur le compte collectif).
     */
    @Column(name = "dedicated_account_id")
    private UUID dedicatedAccountId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Email optionnel — utile pour notifications de relance. */
    @Column(name = "email", length = 255)
    private String email;

    /** Téléphone optionnel — envoyé par le mobile (V10_001 — phone VARCHAR(30)). */
    @Column(name = "phone", length = 30)
    private String phone;

    /** Adresse optionnelle. */
    @Column(name = "address", length = 500)
    private String address;

    /**
     * SIRET du tiers (14 chiffres en France).Finding HAUT — requis pour les
     * mentions légales des factures clients/fournisseurs (CGI art. 289) et le Factur-X.
     */
    @Column(name = "siret", length = 20)
    private String siret;

    /**
     * Numéro de TVA intracommunautaire du tiers (ex: FR12345678901).— requis
     * pour les factures B2B intra-UE (reverse-charge) et le Factur-X. Null si non assujetti.
     */
    @Column(name = "vat_number", length = 20)
    private String vatNumber;

    /**
     * NIF (Numéro d'Identification Fiscale) — équivalent SIRET pour les tiers hors France
     * (Haïti, OHADA).— fallback SIRET dans les mentions légales.
     */
    @Column(name = "nif", length = 30)
    private String nif;

    public ThirdPartyType getType() { return type; }
    public void setType(ThirdPartyType type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getCollectiveAccountId() { return collectiveAccountId; }
    public void setCollectiveAccountId(UUID collectiveAccountId) { this.collectiveAccountId = collectiveAccountId; }

    public UUID getDedicatedAccountId() { return dedicatedAccountId; }
    public void setDedicatedAccountId(UUID dedicatedAccountId) { this.dedicatedAccountId = dedicatedAccountId; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getSiret() { return siret; }
    public void setSiret(String siret) { this.siret = siret; }

    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }

    public String getNif() { return nif; }
    public void setNif(String nif) { this.nif = nif; }
}
