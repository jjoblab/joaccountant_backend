package jo.accountant.core.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.core.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion des taux de change (§3.5, Vague 2 item 2.5).
 *
 * <p>Permet de convertir un montant d'une devise vers une autre en utilisant le taux
 * applicable à une date donnée.
 
 *
 * @author jo@Dev


*/
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository rateRepository;
    private final CurrencyRepository currencyRepository;
    private final CurrencyRoundingService roundingService;

    public ExchangeRateService(ExchangeRateRepository rateRepository,
                               CurrencyRepository currencyRepository,
                               CurrencyRoundingService roundingService) {
        this.rateRepository = rateRepository;
        this.currencyRepository = currencyRepository;
        this.roundingService = roundingService;
    }

    @Transactional
    public ExchangeRate createRate(UUID companyId, String fromCurrency, String toCurrency,
                                    BigDecimal rate, LocalDate asOfDate, String source) {
        if (fromCurrency == null || fromCurrency.length() != 3) {
            throw new ValidationException("FROM_CURRENCY_INVALID", "fromCurrency doit être un code ISO 4217 (3 lettres)");
        }
        if (toCurrency == null || toCurrency.length() != 3) {
            throw new ValidationException("TO_CURRENCY_INVALID", "toCurrency doit être un code ISO 4217 (3 lettres)");
        }
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("RATE_INVALID", "Le taux doit être > 0");
        }
        if (asOfDate == null) asOfDate = LocalDate.now();

        ExchangeRate er = new ExchangeRate();
        er.setId(UUID.randomUUID());
        er.setCompanyId(companyId);
        er.setFromCurrency(fromCurrency.toUpperCase());
        er.setToCurrency(toCurrency.toUpperCase());
        er.setRate(rate);
        er.setAsOfDate(asOfDate);
        er.setSource(source != null ? source : "manuel");
        er.setCreatedAt(Instant.now());
        return rateRepository.save(er);
    }

    /**
     * Convertit un montant d'une devise vers une autre.
     *
     * @return le montant converti, ou le montant original si fromCurrency == toCurrency
     * @throws ValidationException si aucun taux n'est trouvé
     */
    public BigDecimal convert(UUID companyId, BigDecimal amount, String fromCurrency,
                              String toCurrency, LocalDate asOfDate) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }

        // Chercher le taux direct
        Optional<ExchangeRate> directRate = rateRepository.findApplicableRate(
            companyId, fromCurrency.toUpperCase(), toCurrency.toUpperCase(), asOfDate);

        if (directRate.isPresent()) {
            // Audit M14 : arrondi currency-aware selon la devise cible (au lieu de setScale(4)).
            return roundingService.round(toCurrency, amount.multiply(directRate.get().getRate()));
        }

        // Chercher le taux inverse
        Optional<ExchangeRate> inverseRate = rateRepository.findApplicableRate(
            companyId, toCurrency.toUpperCase(), fromCurrency.toUpperCase(), asOfDate);

        if (inverseRate.isPresent()) {
            BigDecimal inverse = BigDecimal.ONE.divide(inverseRate.get().getRate(),
                CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
            // Audit M14 : arrondi currency-aware selon la devise cible (au lieu de setScale(4)).
            return roundingService.round(toCurrency, amount.multiply(inverse));
        }

        throw new ValidationException("EXCHANGE_RATE_NOT_FOUND",
            "Aucun taux de change trouvé pour " + fromCurrency + " → " + toCurrency
            + " à la date " + asOfDate + ". Créer un taux via l'API d'abord.");
    }
}
