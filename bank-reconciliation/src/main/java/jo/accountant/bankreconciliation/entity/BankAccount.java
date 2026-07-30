package jo.accountant.bankreconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Compte bancaire rattaché à un compte de trésorerie du plan comptable (§13 Phase 13).
 *
 * <p>{@link #treasuryAccountId} référence un compte du plan comptable de classe 5
 * (trésorerie). Les lignes de relevé importées sont rapprochées avec les écritures
 * POSTED sur ce compte.
 */
@Entity
@Table(name = "bank_account")
public class BankAccount extends TenantAwareEntity {

    /** Compte de trésorerie du plan comptable (ex. 521 "Banque"). */
    @Column(name = "treasury_account_id", nullable = false)
    private UUID treasuryAccountId;

    /** Libellé du compte bancaire (ex. "Banque Nationale — Compte courant"). */
    @Column(name = "label", nullable = false, length = 200)
    private String label;

    /** Numéro de compte bancaire (IBAN ou numéro local). Optionnel. */
    @Column(name = "account_number", length = 50)
    private String accountNumber;

    public UUID getTreasuryAccountId() { return treasuryAccountId; }
    public void setTreasuryAccountId(UUID treasuryAccountId) { this.treasuryAccountId = treasuryAccountId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
}
