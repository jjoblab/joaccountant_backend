package jo.accountant.demo.support;

import java.util.UUID;
import jo.accountant.core.tenant.TenantContext;

/**
 * V9 — Wrapper {@link AutoCloseable} pour {@link TenantContext}.
 *
 * <p>Permet d'utiliser le pattern <em>try-with-resources</em> pour poser puis nettoyer proprement
 * le contexte tenant thread-local autour d'une opération de seed :
 *
 * <pre>{@code
 * try (var ctx = DemoTenantContext.of(companyId, userId)) {
 * chartOfAccountsBootstrap.bootstrap(companyId, frameworkId, accounts);
 * documentNumberingBootstrap.bootstrap(companyId);
 * fiscalYearBootstrap.bootstrap(companyId);
 * }
 * // TenantContext.clear() a été appelé automatiquement à la sortie du try
 * }</pre>
 *
 * <p><b>Pourquoi pas TenantContext directement ?</b> Les seeders démo s'exécutent en dehors du
 * cycle de vie d'une requête HTTP — il n'y a donc pas de {@code TenantFilter} pour renseigner puis
 * nettoyer le thread-local. Sans ce wrapper, un seed qui échoue au milieu laisserait le
 * thread-local pollué, et le prochain traitement sur ce thread (ex. requête HTTP réutilisée depuis
 * le pool) hériterait d'un companyId démo — fuite multi-tenant grave.
 *
 * <p>Le wrapper garantit que {@link TenantContext#clear()} est <strong>toujours</strong> appelé,
 * même en cas d'exception runtime, ce qui élimine ce risque de fuite.
 *
 * @see TenantContext
 
 *
 * @author jo@Dev


*/
public final class DemoTenantContext implements AutoCloseable {

  private final UUID previousCompanyId;
  private final UUID previousUserId;

  private DemoTenantContext(UUID companyId, UUID userId) {
    // Sauvegarde l'état précédent pour le restaurer à la fermeture (imbrication possible).
    this.previousCompanyId = TenantContext.getCompanyId();
    this.previousUserId = TenantContext.getUserId();
    TenantContext.setCompanyId(companyId);
    TenantContext.setUserId(userId);
  }

  /**
   * Crée un contexte tenant démo pour la paire (companyId, userId).
   *
   * @param companyId identifiant de l'entreprise démo (tenant)
   * @param userId identifiant de l'utilisateur système exécutant le seed (peut être {@code null}
   * pour un seed anonyme — l'audit-trail marquera alors {@code userId=null})
   * @return une instance AutoCloseable à utiliser dans un try-with-resources
   */
  public static DemoTenantContext of(UUID companyId, UUID userId) {
    return new DemoTenantContext(companyId, userId);
  }

  /**
   * Restaure l'état précédent du thread-local (ou nettoie si aucun état antérieur).
   *
   * <p>On préfère restaurer l'état précédent plutôt que d'appeler brutalement {@link
   * TenantContext#clear()} pour supporter l'imbrication de contextes (un seeder qui appelle un
   * autre seeder dans un try-with-resources interne). En pratique, les seeders démo ne s'imbriquent
   * pas, mais cette défensive est peu coûteuse.
   */
  @Override
  public void close() {
    if (previousCompanyId != null) {
      TenantContext.setCompanyId(previousCompanyId);
    } else {
      TenantContext.setCompanyId(null);
    }
    if (previousUserId != null) {
      TenantContext.setUserId(previousUserId);
    } else {
      TenantContext.setUserId(null);
    }
  }
}
