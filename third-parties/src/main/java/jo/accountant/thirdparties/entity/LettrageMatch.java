package jo.accountant.thirdparties.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Lettrage — rapprochement d'écritures d'un compte de tiers entre elles (§13.
 *
 * <p>Le lettrage consiste à associer une facture avec le règlement correspondant (ou plusieurs
 * factures avec un règlement groupé) pour faire apparaître le solde réellement dû.
 *
 * <p>Une fois lettrées, les lignes d'écriture sont marquées avec un code de lettrage (typiquement
 * une lettre alphabétique séquentielle : A, B, C, ...). Le solde non lettré représente les
 * factures impayées ou les règlements non identifiés.
 *
 * <p>Statuts :
 * <ul>
 * <li>{@link LettrageStatus#FULL} — les lignes lettrées s'équilibrent exactement
 * (somme débits = somme crédits).</li>
 * <li>{@link LettrageStatus#PARTIAL} — lettrage partiel : les lignes ne s'équilibrent pas
 * (ex. facture 1000 lettrée avec règlement 800 — il reste 200 non lettrés).</li>
 * </ul>
 *
 * <p>Le lettrage est typiquement utilisé sur les comptes de tiers (clients, fournisseurs),
 * mais peut aussi s'appliquer à d'autres comptes de régularisation.
 
 *
 * @author jo@Dev


*/
@Entity
@Table(name = "lettrage_match")
public class LettrageMatch extends TenantAwareEntity {

    @Column(name = "third_party_id", nullable = false)
    private UUID thirdPartyId;

    /**
     * IDs des {@link jo.accountant.accountingengine.entity.JournalLine} lettrées ensemble.
     * Stocké en JSONB. Au moins 2 lignes par lettrage.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "journal_line_ids", nullable = false, columnDefinition = "jsonb")
    private String journalLineIds;

    /**
     * Code de lettrage — typiquement une lettre alphabétique séquentielle (A, B, C...).
     * Attribué automatiquement par le service. Permet de retrouver visuellement quelles
     * lignes sont lettrées ensemble dans le grand livre.
     */
    @Column(name = "match_code", nullable = false, length = 10)
    private String matchCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private LettrageStatus status;

    /** Somme totale des lignes lettrées (débit + crédit). Utilisé pour audit. */
    @Column(name = "matched_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal matchedAmount;

    @Column(name = "matched_at", nullable = false)
    private Instant matchedAt;

    @Column(name = "matched_by", nullable = false)
    private UUID matchedBy;

    public UUID getThirdPartyId() { return thirdPartyId; }
    public void setThirdPartyId(UUID thirdPartyId) { this.thirdPartyId = thirdPartyId; }

    public String getJournalLineIds() { return journalLineIds; }
    public void setJournalLineIds(String journalLineIds) { this.journalLineIds = journalLineIds; }

    public String getMatchCode() { return matchCode; }
    public void setMatchCode(String matchCode) { this.matchCode = matchCode; }

    public LettrageStatus getStatus() { return status; }
    public void setStatus(LettrageStatus status) { this.status = status; }

    public BigDecimal getMatchedAmount() { return matchedAmount; }
    public void setMatchedAmount(BigDecimal matchedAmount) { this.matchedAmount = matchedAmount; }

    public Instant getMatchedAt() { return matchedAt; }
    public void setMatchedAt(Instant matchedAt) { this.matchedAt = matchedAt; }

    public UUID getMatchedBy() { return matchedBy; }
    public void setMatchedBy(UUID matchedBy) { this.matchedBy = matchedBy; }
}
