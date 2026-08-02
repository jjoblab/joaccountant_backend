package jo.accountant.core.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extrait l'en-tête {@code X-Correlation-Id} (ou en génère un) et nettoie {@link TenantContext} à
 * la fin de chaque requête.
 *
 * <p>PR1-bis (fix) : peuple aussi la MDC (Mapped Diagnostic Context) de SLF4J pour que les logs
 * structurés incluent automatiquement {@code correlationId}, {@code userId}, {@code companyId}.
 
 *
 * @author jo@Dev


*/
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        TenantContext.setCorrelationId(correlationId);
        MDC.put("correlationId", correlationId); // PR1-bis fix
        response.setHeader(CORRELATION_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // PR1-bis fix : nettoyer la MDC aussi
            MDC.clear();
            TenantContext.clear();
        }
    }
}
