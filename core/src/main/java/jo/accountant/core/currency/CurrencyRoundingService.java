package jo.accountant.core.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Service centralisé d'arrondi des montants selon le nombre de décimales de la devise
 * (audit M14).
 *
 * <p>Avant cette correction, les services applicatifs (InvoicingService, InventoryService,
 * FixedAssetsService, TimeBillingService, FundsGrantsService) appelaient tous
 * {@code setScale(4, RoundingMode.HALF_UP)} en dur — ce qui est inadapté pour les devises
 * à 0 décimales (XOF, XAF, JPY) où les montants devraient être arrondis à l'entier.
 *
 * <p>Exemple : 99.99 HT × TVA 10% = 9.999
 * <ul>
 * <li>HTG (2 décimales) : TVA = 10.00</li>
 * <li>XOF (0 décimales) : TVA = 10 (entier)</li>
 * <li>USD (2 décimales) : TVA = 10.00</li>
 * </ul>
 *
 * <p>Le cache interne évite de recharger l'entité {@link Currency} à chaque appel.
 * Le cache est invalidé à la demande via {@link #invalidate(String)} si une devise est
 * modifiée (rare — les devises sont seed-only).
 *
 * <p>Usage :
 * <pre>{@code
 * @Autowired CurrencyRoundingService roundingService;
 *
 * BigDecimal lineHt = quantity.multiply(unitPrice);
 * lineHt = roundingService.round(currencyCode, lineHt);
 * BigDecimal lineTax = roundingService.round(currencyCode,
 * lineHt.multiply(taxRate).divide(HUNDRED, 6, RoundingMode.HALF_UP));
 * }</pre>
 
 *
 * @author jo@Dev


*/
@Component
public class CurrencyRoundingService {

    /** Échelle interne des calculs intermédiaires (suffisante pour éviter les erreurs cumulées). */
    public static final int COMPUTATION_SCALE = 6;

    /** Échelle par défaut si la devise est inconnue (rétro-compatibilité avec l'ancien comportement). */
    public static final int DEFAULT_DECIMALS = 4;

    private final CurrencyRepository currencyRepository;
    private final ConcurrentHashMap<String, Integer> decimalsCache = new ConcurrentHashMap<>();

    public CurrencyRoundingService(CurrencyRepository currencyRepository) {
        this.currencyRepository = currencyRepository;
    }

    /**
     * Retourne le nombre de décimales pour la devise donnée.
     * Si la devise est inconnue (non seedée), retourne {@link #DEFAULT_DECIMALS}.
     */
    public int getDecimals(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) return DEFAULT_DECIMALS;
        return decimalsCache.computeIfAbsent(currencyCode.toUpperCase(), code ->
            currencyRepository.findById(code)
                .map(Currency::getDecimals)
                .orElse(DEFAULT_DECIMALS)
        );
    }

    /**
     * Arrondit un montant au nombre de décimales de la devise, en HALF_UP.
     * Si la devise est inconnue, arrondit à {@link #DEFAULT_DECIMALS} (rétro-compat).
     */
    public BigDecimal round(String currencyCode, BigDecimal amount) {
        if (amount == null) return BigDecimal.ZERO;
        return amount.setScale(getDecimals(currencyCode), RoundingMode.HALF_UP);
    }

    /**
     * Arrondit un montant au nombre de décimales de la devise + 2 chiffres de garde internes,
     * pour les calculs intermédiaires (avant arrondi final).
     */
    public BigDecimal roundForComputation(String currencyCode, BigDecimal amount) {
        if (amount == null) return BigDecimal.ZERO;
        return amount.setScale(COMPUTATION_SCALE, RoundingMode.HALF_UP);
    }

    /** Invalide le cache pour une devise (utile si la devise est modifiée — rare). */
    public void invalidate(String currencyCode) {
        if (currencyCode != null) decimalsCache.remove(currencyCode.toUpperCase());
    }

    /** Invalide tout le cache (tests). */
    public void invalidateAll() {
        decimalsCache.clear();
    }
}
