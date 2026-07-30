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
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> COMPANY_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCompanyId(UUID companyId) { COMPANY_ID.set(companyId); }
    public static UUID getCompanyId() { return COMPANY_ID.get(); }

    public static void setUserId(UUID userId) { USER_ID.set(userId); }
    public static UUID getUserId() { return USER_ID.get(); }

    public static void setCorrelationId(String correlationId) { CORRELATION_ID.set(correlationId); }
    public static String getCorrelationId() { return CORRELATION_ID.get(); }

    public static void clear() {
        COMPANY_ID.remove();
        USER_ID.remove();
        CORRELATION_ID.remove();
    }
}
