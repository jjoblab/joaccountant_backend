package jo.accountant.fundsgrants.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Reçu de don (§13+ V8-5 donationType).
 *
 * <p>{@link #receiptNumber} est généré via {@code document-numbering}
 * (DocumentType.DONATION_RECEIPT) au moment de la création — jamais calculé localement.
 *
 * <p><b>V8-5</b> — Le champ {@link #donationType} (CASH ou IN_KIND) distingue les dons en
 * espèces (D 521/C 70x) des dons en nature (D 3x Stocks ou D 215 Immobilisations / C 70x).
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "fg_donation_receipt")
public class DonationReceipt extends TenantAwareEntity {

    /** Subvention rattachée (optionnel — un don peut être non affecté). */
    @Column(name = "grant_id")
    private UUID grantId;

    /** Bailleur (ThirdParty de type DONOR). */
    @Column(name = "donor_third_party_id", nullable = false)
    private UUID donorThirdPartyId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Numéro généré via document-numbering (DocumentType.DONATION_RECEIPT). */
    @Column(name = "receipt_number", nullable = false, length = 50)
    private String receiptNumber;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    /** Description optionnelle (ex. "Don en espèces", "Don en nature — équipement médical"). */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * V8-5 — Type de donation (CASH ou IN_KIND). Défaut CASH pour rétro-compatibilité
     * (les reçus créés avant V8-5 sont considérés comme cash).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "donation_type", nullable = false, length = 10)
    private DonationType donationType = DonationType.CASH;

    /**
     * ID de l'écriture de JournalEntry générée à la création du reçu (audit M7).
     */
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    public UUID getGrantId() { return grantId; }
    public void setGrantId(UUID grantId) { this.grantId = grantId; }

    public UUID getDonorThirdPartyId() { return donorThirdPartyId; }
    public void setDonorThirdPartyId(UUID donorThirdPartyId) { this.donorThirdPartyId = donorThirdPartyId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReceiptNumber() { return receiptNumber; }
    public void setReceiptNumber(String receiptNumber) { this.receiptNumber = receiptNumber; }

    public LocalDate getReceiptDate() { return receiptDate; }
    public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public DonationType getDonationType() { return donationType; }
    public void setDonationType(DonationType donationType) {
        this.donationType = donationType != null ? donationType : DonationType.CASH;
    }

    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }
}
