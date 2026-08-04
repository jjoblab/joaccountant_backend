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

        // v9.4 fix — Capturer l'IP et le User-Agent pour l'audit trail forensique.
        // X-Forwarded-For : proxy/load-balancer → IP réelle du client (premier élément).
        // X-Real-IP : alternative nginx. Fallback : request.getRemoteAddr().
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress != null && !ipAddress.isBlank()) {
            // X-Forwarded-For peut contenir une liste "client, proxy1, proxy2" — on prend le premier.
            ipAddress = ipAddress.split(",")[0].trim();
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            ipAddress = request.getHeader("X-Real-IP");
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            ipAddress = request.getRemoteAddr();
        }
        TenantContext.setIpAddress(ipAddress);

        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 500) {
            userAgent = userAgent.substring(0, 500); // tronquer pour tenir dans la colonne VARCHAR(500)
        }
        TenantContext.setUserAgent(userAgent);

        // v9.4 — Execution context : "api" pour toutes les requêtes REST.
        // Les jobs cron / imports CSV / workflows peuvent override via setExecutionContext().
        TenantContext.setExecutionContext("api");

        try {
            filterChain.doFilter(request, response);
        } finally {
            // PR1-bis fix : nettoyer la MDC aussi
            MDC.clear();
            TenantContext.clear();
        }
    }
}
