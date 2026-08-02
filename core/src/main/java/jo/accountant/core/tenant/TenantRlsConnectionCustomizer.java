package jo.accountant.core.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * (lot-A-securite) — Wrappe un {@link DataSource} (typiquement HikariCP) pour appliquer
 * automatiquement {@code SET LOCAL app.current_tenant = ?} sur chaque connexion avant son
 * utilisation par Hibernate/JdbcTemplate.
 *
 * <p><b>Contexte</b> — La migration V62 active Row-Level Security sur 6 tables
 * (journal_line, journal_entry, sales_invoice, purchase_invoice, third_party, expense_report)
 * avec la policy {@code USING (company_id = current_setting('app.current_tenant', true)::uuid)}.
 * Le second argument {@code true} de {@code current_setting} active le mode "missing OK" :
 * si la GUC n'est pas posée, retourne NULL → policy évaluée à FALSE → aucune ligne retournée
 * (fail-closed). Pour que les requêtes puissent accéder aux lignes du bon tenant, la couche
 * Java doit exécuter {@code SET LOCAL app.current_tenant = <uuid>} au début de chaque
 * transaction.
 *
 * <p><b>Mécanisme</b> — Au lieu de modifier le code métier de chaque repository (50+ services),
 * on wrappe le {@link DataSource} au niveau infrastructure. Le wrapper intercepte
 * {@link Connection#setAutoCommit(boolean)} : quand Spring/Hibernate appelle
 * {@code setAutoCommit(false)} pour débuter une transaction, PostgreSQL émet un BEGIN implicite.
 * On en profite pour exécuter {@code SET LOCAL app.current_tenant = ?} juste après — la GUC
 * est alors positionnée pour la durée de la transaction et automatiquement reset au COMMIT/ROLLBACK.
 *
 * <p><b>Avantages de SET LOCAL vs SET</b> :
 * <ul>
 * <li>{@code SET LOCAL} est scopé à la transaction courante → pas de fuite vers la prochaine
 * requête si la connexion est retournée au pool.</li>
 * <li>{@code SET} (session-level) persisterait sur la connexion poolée et pourrait être
 * visible par une requête suivante sans tenant context — faille de sécurité.</li>
 * <li>Avec SET LOCAL, le RESET au COMMIT/ROLLBACK est automatique — pas besoin de hooker
 * {@code close()} ou {@code HikariConfig.connectionInitSql}.</li>
 * </ul>
 *
 * <p><b>Cas où SET LOCAL n'est pas appliqué</b> :
 * <ul>
 * <li><b>Pas de tenant context</b> ({@link TenantContext#getCompanyId()} retourne null) :
 * batchs, migrations Flyway, startup. La policy RLS échoue en fail-closed — sécurisé
 * par défaut (aucune ligne retournée, pas de fuite cross-tenant).</li>
 * <li><b>Hors transaction</b> : si du code exécute des requêtes SQL sans {@code @Transactional}
 * (ex: JdbcTemplate direct), {@code setAutoCommit(false)} n'est pas appelé → SET LOCAL
 * n'est pas exécuté → RLS bloque la requête (fail-closed). Pour accéder aux tables
 * RLS-protégées, TOUJOURS utiliser {@code @Transactional} (déjà le cas pour tous les
 * services métier via Spring Data JPA).</li>
 * </ul>
 *
 * <p><b>Installation</b> — Le wrapper est installé via {@link TenantRlsDataSourcePostProcessor}
 * (BeanPostProcessor) qui remplace le bean "dataSource" (auto-configuré par Spring Boot avec
 * Hikari) par une instance de cette classe. Cette approche évite les dépendances circulaires
 * qu'une déclaration {@code @Bean @Primary DataSource} entraînerait.
 *
 * <p><b>Performance</b> — Le wrapping par JDK dynamic Proxy ajoute ~50ns par appel de méthode
 * (negligible devant le temps d'un roundtrip JDBC). Le SET LOCAL lui-même est un ordre de
 * magnitude plus rapide qu'un SELECT (pas d'I/O, juste positionner une GUC en mémoire serveur).
 *
 * @see TenantRlsDataSourcePostProcessor
 * @see TenantContext
 */
public class TenantRlsConnectionCustomizer extends AbstractDataSource {

 private static final Logger LOG = LoggerFactory.getLogger(TenantRlsConnectionCustomizer.class);

 private final DataSource delegate;

 public TenantRlsConnectionCustomizer(DataSource delegate) {
 this.delegate = delegate;
 }

 /** Expose le DataSource sous-jacent (pour tests ou debug). */
 public DataSource getDelegate() {
 return delegate;
 }

 @Override
 public Connection getConnection() throws SQLException {
 return wrap(delegate.getConnection());
 }

 @Override
 public Connection getConnection(String username, String password) throws SQLException {
 return wrap(delegate.getConnection(username, password));
 }

 /**
 * Wrappe la connexion JDBC dans un proxy dynamique JDK qui intercepte
 * {@code setAutoCommit(false)} pour appliquer SET LOCAL après le BEGIN implicite.
 */
 private Connection wrap(Connection conn) {
 if (conn == null) return null;
 return (Connection) Proxy.newProxyInstance(
 Connection.class.getClassLoader(),
 new Class<?>[]{Connection.class},
 new TenantAwareConnectionHandler(conn));
 }

 /**
 * InvocationHandler qui intercepte {@code setAutoCommit(false)} (appelé par Spring/Hibernate
 * pour débuter une transaction) et en profite pour exécuter SET LOCAL app.current_tenant = ?.
 *
 * <p>Le flag {@code tenantApplied} empêche la ré-application au cours de la même transaction
 * (par ex. si Hibernate appelle setAutoCommit plusieurs fois pour des sous-transactions).
 */
 static final class TenantAwareConnectionHandler implements InvocationHandler {

 private final Connection delegate;
 private boolean tenantApplied = false;

 TenantAwareConnectionHandler(Connection delegate) {
 this.delegate = delegate;
 }

 @Override
 public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
 String methodName = method.getName();

 // Optimisations pour les méthodes Object standard — évite de reflecter dessus.
 if ("toString".equals(methodName) && args == null) {
 return "TenantRlsConnectionProxy[" + delegate + ", tenantApplied=" + tenantApplied + "]";
 }
 if ("hashCode".equals(methodName) && args == null) {
 return System.identityHashCode(proxy);
 }
 if ("equals".equals(methodName) && args != null && args.length == 1) {
 return proxy == args[0];
 }

 // Hook central : intercepter setAutoCommit(false) — c'est Spring/Hibernate qui
 // appelle cette méthode pour débuter une transaction (PostgreSQL émet un BEGIN
 // implicite). On en profite pour positionner la GUC RLS avant toute requête métier.
 if ("setAutoCommit".equals(methodName) && args != null && args.length == 1
 && Boolean.FALSE.equals(args[0]) && !tenantApplied) {
 Object result = method.invoke(delegate, args);
 applyTenantContext();
 tenantApplied = true;
 return result;
 }

 // unwrap / isWrapperFor — déléguer au DataSource sous-jacent (Hikari supporte unwrap).
 // Sans cette délégation, certaines librairies (Flyway, Spring Batch) qui appellent
 // conn.unwrap(Connection.class) obtiendraient le proxy au lieu de la vraie connexion.
 if ("unwrap".equals(methodName) && args != null && args.length == 1) {
 Class<?> iface = (Class<?>) args[0];
 if (iface.isInstance(delegate)) {
 return iface.cast(delegate);
 }
 return method.invoke(delegate, args);
 }
 if ("isWrapperFor".equals(methodName) && args != null && args.length == 1) {
 Class<?> iface = (Class<?>) args[0];
 if (iface.isInstance(delegate)) {
 return true;
 }
 return method.invoke(delegate, args);
 }

 // Délégation par défaut — toutes les autres méthodes (prepareStatement, executeQuery,
 // commit, rollback, close, isClosed, getMetaData, ...) sont forwardées telles quelles.
 return method.invoke(delegate, args);
 }

 /**
 * Applique SET LOCAL app.current_tenant = ? sur la connexion sous-jacente.
 * Appelée une fois par transaction (au moment du setAutoCommit(false)).
 *
 * <p>Si {@link TenantContext#getCompanyId()} est null (batch/migration/startup), ne fait
 * rien — la policy RLS échouera en fail-closed (aucune ligne retournée), ce qui est
 * le comportement attendu pour empêcher un accès cross-tenant accidentel hors contexte
 * de requête HTTP.
 */
 private void applyTenantContext() {
 UUID tenantId = TenantContext.getCompanyId();
 if (tenantId == null) {
 // Pas de tenant context — ne rien faire. La policy RLS retourne NULL → FALSE,
 // aucune ligne n'est visible. Fail-closed.
 if (LOG.isTraceEnabled()) {
 LOG.trace("RLS : pas de tenant context — SET LOCAL non appliqué (fail-closed).");
 }
 return;
 }
 try (java.sql.Statement stmt = delegate.createStatement()) {
 // V8.2 — PostgreSQL n'accepte pas les paramètres bind (?) dans SET LOCAL.
 // L'UUID est déjà validé (toString() d'un java.util.UUID), pas d'injection SQL possible.
 // On utilise un Statement simple avec la valeur inline.
 stmt.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
 if (LOG.isDebugEnabled()) {
 LOG.debug("RLS : SET LOCAL app.current_tenant = {} appliqué à la transaction courante", tenantId);
 }
 } catch (SQLException ex) {
 // Ne pas rethrow — laisser la requête échouer naturellement avec une erreur RLS.
 // Fail-closed : si on ne peut pas positionner la GUC, la policy retourne FALSE →
 // aucune ligne retournée. C'est plus sûr que de permettre la requête sans scoping.
 LOG.warn("RLS : échec SET LOCAL app.current_tenant = {} — la policy RLS échouera " +
 "en fail-closed (aucune ligne retournée). Cause : {}",
 tenantId, ex.getMessage());
 }
 }
 }
}
