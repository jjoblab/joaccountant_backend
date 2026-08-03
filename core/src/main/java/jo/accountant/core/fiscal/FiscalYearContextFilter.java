package jo.accountant.core.fiscal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fix Dim 5 C3 (audit v9.4) — Filtre Spring qui lit le header HTTP {@code X-Fiscal-Year}
 * (UUID optionnel) et le pose dans {@link FiscalYearContext} pour la durée de la requête.
 *
 * <p>Permet au frontend de "poser" l'exercice sélectionné une fois pour toutes dans le
 * header, sans avoir à propager {@code ?fiscalYearId=} sur chaque appel HTTP. Les services
 * backend peuvent ensuite appeler {@link FiscalYearContext#getFiscalYearId()} pour obtenir
 * l'exercice sélectionné.
 *
 * <p><b>Validation</b> : le filtre parse l'UUID et ignore silencieusement les valeurs
 * invalides (avec un log WARN). La validation de l'appartenance au tenant est laissée aux
 * services (qui ont accès au {@code companyId} via {@link jo.accountant.core.tenant.TenantContext}).
 *
 * <p><b>Ordre</b> : le filtre s'exécute APRÈS {@code TenantContextFilter} (HIGHEST_PRECEDENCE)
 * pour pouvoir bénéficier du {@code companyId} si besoin, mais AVANT les contrôleurs.
 * On utilise {@code HIGHEST_PRECEDENCE + 10} pour être juste après.
 *
 * <p><b>Nettoyage</b> : le ThreadLocal est nettoyé dans {@code finally} pour éviter les
 * fuites (les threads sont recyclés par le pool Tomcat).
 *
 * @author jo@Dev
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiscalYearContextFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(FiscalYearContextFilter.class);

    /** Header HTTP standard pour sélectionner l'exercice fiscal. */
    public static final String FISCAL_YEAR_HEADER = "X-Fiscal-Year";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String headerValue = request.getHeader(FISCAL_YEAR_HEADER);
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                UUID fiscalYearId = UUID.fromString(headerValue.trim());
                FiscalYearContext.setFiscalYearId(fiscalYearId);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Header X-Fiscal-Year={} posé dans le contexte pour la requête {}",
                        fiscalYearId, request.getRequestURI());
                }
            } catch (IllegalArgumentException e) {
                // UUID invalide — on log en WARN mais on ne fait pas échouer la requête
                LOG.warn("Header X-Fiscal-Year invalide '{}' ignoré pour la requête {}",
                    headerValue, request.getRequestURI());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            FiscalYearContext.clear();
        }
    }
}
