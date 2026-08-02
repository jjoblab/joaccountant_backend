package jo.accountant.accountingengine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;

/**
 * Corps de requête pour {@code POST .../journal-entries}.
 *
 * <p>L'en-tête HTTP {@code Idempotency-Key} est obligatoire (§3.10). Rejouer la même clé
 * renvoie le même résultat, jamais de doublon.
 *
 * @param journalCode code du journal (ex. "VT", "AC", "BQ", "OD")
 * @param entryDate date de l'écriture — détermine la période fiscale
 * @param description description libre
 * @param lines lignes de l'écriture — somme des débits doit égaler somme des crédits
 * @param sourceModule module d'origine (MANUAL par défaut)
 
 *
 * @author jo@Dev


*/
public record CreateJournalEntryRequest(
    @NotBlank String journalCode,
    @NotNull LocalDate entryDate,
    String description,
    @NotEmpty List<LineDto> lines,
    JournalEntrySourceModule sourceModule
) {

    /** Une ligne de l'écriture. */
    public record LineDto(
        @NotBlank String accountCode,
        UUID thirdPartyId,
        java.math.BigDecimal debit,
        java.math.BigDecimal credit,
        String description,
        /** Tags analytiques optionnels — obligatoires si le compte porte
         * {@code requiresAnalyticalTagPlanIds} non vide. */
        List<AnalyticalTagDto> analyticalTags,
        /**Finding HAUT — multi-devises effective.
         * Montant dans la devise de transaction (ex: 100 USD). Si null, la devise fonctionnelle
         * de l'entreprise est utilisée (mono-devise, comportement historique). */
        java.math.BigDecimal amountTransactionCurrency,
        /** Devise de transaction (ISO 4217 : USD, EUR, HTG...). Si null, la devise fonctionnelle
         * de l'entreprise est utilisée. */
        String transactionCurrency,
        /** Taux de change utilisé (1 unité de devise fonctionnelle = X unités de devise de transaction).
         * Si null, {@code BigDecimal.ONE} est utilisé (mono-devise). */
        java.math.BigDecimal exchangeRateUsed
    ) {
        public LineDto {
            if (debit == null) debit = java.math.BigDecimal.ZERO;
            if (credit == null) credit = java.math.BigDecimal.ZERO;
            if (analyticalTags == null) analyticalTags = List.of();
            //— si transactionCurrency est fournie mais exchangeRateUsed est null,
            // on suppose un taux de 1 (mono-devise, rétro-compatibilité). Si seul le montant est
            // fourni sans devise, on ignore (mono-devise).
            if (exchangeRateUsed == null) exchangeRateUsed = java.math.BigDecimal.ONE;
        }

        /**
         * Constructeur de rétro-compatibilité — pour les appelants qui ne fournissent pas
         * les champs multi-devises (mono-devise, comportement historique).
         */
        public LineDto(String accountCode, UUID thirdPartyId, java.math.BigDecimal debit,
                       java.math.BigDecimal credit, String description,
                       List<AnalyticalTagDto> analyticalTags) {
            this(accountCode, thirdPartyId, debit, credit, description, analyticalTags,
                null, null, java.math.BigDecimal.ONE);
        }
    }

    /** Tag analytique d'une ligne. */
    public record AnalyticalTagDto(
        @NotNull UUID planId,
        @NotNull UUID valueId,
        @NotNull java.math.BigDecimal allocationPercentage
    ) {}
}
