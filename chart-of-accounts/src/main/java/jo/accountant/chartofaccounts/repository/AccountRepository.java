package jo.accountant.chartofaccounts.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.core.framework.ReportingClass;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository des comptes du plan comptable.
 *
 * <p>L'isolation multi-tenant est faite explicitement par le service via {@code companyId} —
 * Hibernate {@code @TenantId} n'est pas activé globalement (refactor commun prévu, voir
 * décisiondans {@code worklog.md}).
 *
 * <p><b>Cache applicatif</b> : les méthodes de lookup par
 * {@code (companyId, code)} et par {@code (companyId, reportingClass, taxMappingCode)} sont
 * annotées {@code @Cacheable("accounts")}. Ces lookups sont appelés 32 fois dans 8 services
 * (InvoicingService, PurchasingService, ExpensesService, PayrollService, etc.) sur chaque
 * opération de facturation/écriture. Sans cache, 1000 factures/jour × 7-10 SELECT = 7000-10000
 * SELECT inutiles sur des données qui changent quelques fois par an.
 *
 * <p>La key du cache est {@code companyId.toString() + ':' + code} (ou équivalent) — un
 * namespace par tenant pour éviter toute fuite cross-tenant. Invalidation via
 * {@code @CacheEvict(value = "accounts", allEntries = true)} sur les mutations — placée dans
 * {@code ChartOfAccountsService} car {@code save()} est hérité de {@code JpaRepository} et ne
 * peut pas être annoté directement.
 *
 * <p><b>Note sur Optional</b> : Spring Cache supporte {@code Optional} depuis Spring 5.x —
 * {@code Optional.empty()} n'est pas caché (configuré via {@code setAllowNullValues=false} dans
 * {@code CacheConfig}). Évite de cacher un résultat négatif si un compte n'existe pas
 * temporairement (ex: créé 1s plus tard).
 
 *
 * @author jo@Dev


*/
public interface AccountRepository extends JpaRepository<Account, UUID> {

 /** Recherche un compte par son code, dans l'entreprise donnée. */
 @Cacheable(value = "accounts", key = "#companyId.toString() + ':' + #code")
 Optional<Account> findByCompanyIdAndCode(UUID companyId, String code);

 /**
 * Renvoie le premier compte actif de l'entreprise ayant la {@link ReportingClass} et le
 * {@code taxMappingCode} donnés. Utilisé pour résoudre un compte de ventes, un compte de
 * TVA collectée, un compte de résultat, etc. de manière **référentiel-agnostique** (au lieu
 * de chercher par code en dur comme "701", "443", "12" qui ne fonctionnent qu'en
 * SYSCOHADA/PCG/PCN).
 *
 * <p>Les comptes sont triés par code croissant pour que le résultat soit déterministe.
 */
 @Cacheable(value = "accounts",
 key = "#companyId.toString() + ':' + #reportingClass.name() + ':' + #taxMappingCode + ':active:first'")
 Optional<Account> findFirstByCompanyIdAndReportingClassAndTaxMappingCodeAndActiveTrueOrderByCodeAsc(
 UUID companyId, ReportingClass reportingClass, String taxMappingCode);

 /**
 * Renvoie le premier compte actif de l'entreprise ayant la {@link ReportingClass} et le
 * {@code level} donnés (typiquement {@code level=1} pour le compte racine d'une classe,
 * ex. le compte "1 — Capitaux propres" utilisé comme compte de résultat par défaut lors
 * de la clôture d'exercice).
 */
 @Cacheable(value = "accounts",
 key = "#companyId.toString() + ':' + #reportingClass.name() + ':level:' + #level + ':active:first'")
 Optional<Account> findFirstByCompanyIdAndReportingClassAndLevelAndActiveTrueOrderByCodeAsc(
 UUID companyId, ReportingClass reportingClass, int level);

 /**
 * Renvoie le premier compte actif de l'entreprise ayant la {@link ReportingClass} donnée,
 * quel que soit son niveau. Utilisé comme dernier fallback quand aucun compte marqué par
 * un {@code taxMappingCode} spécifique n'est trouvé.
 */
 @Cacheable(value = "accounts",
 key = "#companyId.toString() + ':' + #reportingClass.name() + ':active:first'")
 Optional<Account> findFirstByCompanyIdAndReportingClassAndActiveTrueOrderByCodeAsc(
 UUID companyId, ReportingClass reportingClass);

 /** True si un compte existe déjà avec ce code dans l'entreprise. */
 boolean existsByCompanyIdAndCode(UUID companyId, String code);

 /** Tous les comptes de l'entreprise. */
 List<Account> findByCompanyIdOrderByCode(UUID companyId);

 /** Enfants directs d'un compte parent donné. */
 List<Account> findByCompanyIdAndParentIdOrderByCode(UUID companyId, UUID parentId);

 /** Recherche full-text (case-insensitive) sur code ou libellé. */
 @Query("select a from Account a where a.companyId = :companyId " +
 "and (lower(a.code) like lower(concat('%', :search, '%')) " +
 " or lower(a.label) like lower(concat('%', :search, '%'))) " +
 "order by a.code")
 List<Account> search(@Param("companyId") UUID companyId,
 @Param("search") String search);

 /** Compte le nombre de descendants directs + indirects d'un compte. */
 @Query(value = """
 WITH RECURSIVE descendants AS (
 SELECT id FROM account WHERE company_id = :companyId AND parent_id = :accountId
 UNION ALL
 SELECT a.id FROM account a
 JOIN descendants d ON a.parent_id = d.id
 WHERE a.company_id = :companyId
 )
 SELECT count(*) FROM descendants
 """, nativeQuery = true)
 long countDescendants(@Param("companyId") UUID companyId,
 @Param("accountId") UUID accountId);
}

