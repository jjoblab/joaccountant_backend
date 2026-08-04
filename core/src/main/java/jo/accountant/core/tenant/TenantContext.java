package jo.accountant.core.tenant;

import java.util.UUID;

/**
 * Contexte tenant thread-local, renseigné à chaque requête par {@link TenantFilter} depuis le
 * claim JWT {@code companyId} (validé contre {@code UserCompanyRole} par le filtre d'auth).
 *
 * <p>{@link TenantAwareEntityListener} lit depuis ici pour stamp {@code company_id} sur chaque
 * ligne, et le {@code @TenantId} d'Hibernate lit depuis
 * {@link CurrentTenantIdentifierResolverImpl} (également adossé à cette classe) pour borner les
 * requêtes automatiquement.
 
 *
 * @author jo@Dev


*/
public final class TenantContext {

    private static final ThreadLocal<UUID> COMPANY_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();
    // v9.4 fix — Champs pour l'audit trail forensique (alignés sur NetSuite/Sage Intacct)
    private static final ThreadLocal<String> IP_ADDRESS = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_AGENT = new ThreadLocal<>();
    private static final ThreadLocal<String> EXECUTION_CONTEXT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCompanyId(UUID companyId) { COMPANY_ID.set(companyId); }
    public static UUID getCompanyId() { return COMPANY_ID.get(); }

    public static void setUserId(UUID userId) { USER_ID.set(userId); }
    public static UUID getUserId() { return USER_ID.get(); }

    public static void setCorrelationId(String correlationId) { CORRELATION_ID.set(correlationId); }
    public static String getCorrelationId() { return CORRELATION_ID.get(); }

    // v9.4 — IP address pour forensique (Sage Intacct Advanced Audit Trail, Odoo OCA)
    public static void setIpAddress(String ipAddress) { IP_ADDRESS.set(ipAddress); }
    public static String getIpAddress() { return IP_ADDRESS.get(); }

    // v9.4 — User-Agent pour distinguer mobile/web/API
    public static void setUserAgent(String userAgent) { USER_AGENT.set(userAgent); }
    public static String getUserAgent() { return USER_AGENT.get(); }

    // v9.4 — Execution context : "ui" | "api" | "import" | "cron" | "workflow" (NetSuite)
    public static void setExecutionContext(String ctx) { EXECUTION_CONTEXT.set(ctx); }
    public static String getExecutionContext() { return EXECUTION_CONTEXT.get(); }

    public static void clear() {
        COMPANY_ID.remove();
        USER_ID.remove();
        CORRELATION_ID.remove();
        IP_ADDRESS.remove();
        USER_AGENT.remove();
        EXECUTION_CONTEXT.remove();
    }
}
