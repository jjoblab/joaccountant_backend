package jo.accountant.core.fiscal;

import java.util.UUID;

/**
 * Fix Dim 5 C3 (audit v9.4) — Contexte ThreadLocal portant l'exercice fiscal sélectionné
 * par l'utilisateur pour la requête courante.
 *
 * <p>Avant ce fix, aucun mécanisme global de bascule d'exercice n'existait : le frontend
 * devait propager {@code ?fiscalYearId=} manuellement sur chaque appel HTTP. Si un écran
 * oubliait de le faire, il montrait l'exercice actif par défaut ou toutes les données.
 *
 * <p>Avec ce contexte, un filtre Spring ({@link FiscalYearContextFilter}) lit le header
 * HTTP {@code X-Fiscal-Year} (UUID optionnel), le valide, et le pose dans le ThreadLocal.
 * Les services peuvent ensuite appeler {@link #getFiscalYearId()} pour obtenir l'exercice
 * sélectionné, indépendamment des paramètres de requête.
 *
 * <p>Usage côté service :
 * <pre>
 * UUID selectedFy = FiscalYearContext.getFiscalYearId();
 * if (selectedFy != null) {
 *     // Utiliser l'exercice sélectionné par l'utilisateur
 * } else {
 *     // Fallback sur l'exercice actif
 * }
 * </pre>
 *
 * <p><b>Nettoyage</b> : le ThreadLocal est nettoyé par {@link FiscalYearContextFilter}
 * dans {@code finally} après la requête. Ne JAMAIS poser de valeur sans la nettoyer.
 *
 * @author jo@Dev
 */
public final class FiscalYearContext {

    private static final ThreadLocal<UUID> CONTEXT = new ThreadLocal<>();

    private FiscalYearContext() {
        // Utility class — pas d'instanciation
    }

    /**
     * Pose l'exercice fiscal sélectionné pour la requête courante.
     *
     * @param fiscalYearId identifiant de l'exercice (null pour réinitialiser)
     */
    public static void setFiscalYearId(UUID fiscalYearId) {
        CONTEXT.set(fiscalYearId);
    }

    /**
     * Récupère l'exercice fiscal sélectionné pour la requête courante.
     *
     * @return l'UUID de l'exercice ou {@code null} si aucun n'a été sélectionné
     */
    public static UUID getFiscalYearId() {
        return CONTEXT.get();
    }

    /**
     * Réinitialise le contexte. À appeler impérativement dans {@code finally} du filtre
     * pour éviter les fuites ThreadLocal (les threads sont recyclés par le pool Tomcat).
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
