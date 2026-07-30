package jo.accountant.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamp {@link TenantContext#setUserId} et (quand l'URL est company-scoped)
 * {@link TenantContext#setCompanyId} depuis les claims du JWT.
 *
 * <p>Le companyId utilisé pour le scoping tenant est extrait du path de l'URL
 * {@code /api/v1/companies/{companyId}/...} et validé contre le claim JWT {@code companies} de
 * l'utilisateur — si l'utilisateur ne détient pas de rôle dans cette société, la requête est
 * rejetée avec 403 (§3.4 : vérifier rôle + appartenance company au niveau méthode, ici au niveau
 * requête).
 */
public class TenantClaimFilter extends OncePerRequestFilter {

    private static final String COMPANY_PATH_PREFIX = "/api/v1/companies/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            if (sub != null) {
                try {
                    UUID userId = UUID.fromString(sub);
                    TenantContext.setUserId(userId);
                    org.slf4j.MDC.put("userId", userId.toString());  // PR1-bis fix
                }
                catch (IllegalArgumentException ignored) { /* sub invalide — laisser le downstream rejeter */ }
            }

            UUID pathCompanyId = extractCompanyIdFromPath(request.getRequestURI());
            if (pathCompanyId != null) {
                if (!userHasAccessToCompany(jwt, pathCompanyId)) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);  // 404 pas 403 (§3.9)
                    response.setContentType("application/problem+json");
                    response.getWriter().write("""
                        {"type":"https://joaccountant.dev/errors/not_found","title":"Not Found","status":404,"detail":"Resource not found","code":"NOT_FOUND"}""");
                    return;
                }
                TenantContext.setCompanyId(pathCompanyId);
                org.slf4j.MDC.put("companyId", pathCompanyId.toString());  // PR1-bis fix
            }
        }
        filterChain.doFilter(request, response);
    }

    private UUID extractCompanyIdFromPath(String uri) {
        if (uri == null || !uri.startsWith(COMPANY_PATH_PREFIX)) return null;
        String tail = uri.substring(COMPANY_PATH_PREFIX.length());
        int slash = tail.indexOf('/');
        String candidate = slash > 0 ? tail.substring(0, slash) : tail;
        try { return UUID.fromString(candidate); }
        catch (IllegalArgumentException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private boolean userHasAccessToCompany(Jwt jwt, UUID companyId) {
        Object raw = jwt.getClaim("companies");
        if (!(raw instanceof List<?> list)) return false;
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> m) {
                Object cid = m.get("companyId");
                if (cid instanceof String s && s.equalsIgnoreCase(companyId.toString())) {
                    return true;
                }
            }
        }
        return false;
    }
}
