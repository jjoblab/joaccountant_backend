package jo.accountant.demo.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.demo.DemoDataSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIX v9.4.1 (audit T3.1) — Scheduler de reset automatique des données démo.
 *
 * <p>Modèle inspiré de Xero (TTL 28 jours) et QuickBooks Online (TTL 30 jours).
 * Sans ce scheduler, les 4 entreprises démo accumulent indéfiniment les écritures
 * créées par les utilisateurs en test (création de factures, écritures comptables,
 * notes de frais, etc.) — la BD démo grossit et les performances se dégradent
 * (index RLS à scanner, agrégations Dashboard plus lentes).
 *
 * <p>Stratégie :
 * <ul>
 *   <li>Toutes les 24h à 03:00 (heure serveur), vérifie chaque entreprise
 *       {@code is_demo=true}.</li>
 *   <li>Si {@code Company.createdAt} date de plus de 28 jours, déclenche un
 *       re-seed complet via {@link DemoDataSeeder#seedAll()}.</li>
 *   <li>Le re-seed est idempotent : il supprime d'abord les données business
 *       (écritures, factures, paie) puis les recrée. La company + le user + le
 *       COA sont conservés.</li>
 * </ul>
 *
 * <p><strong>ShedLock</strong> : en multi-instances, positionner
 * {@code @SchedulerLock} sur cette méthode via le lock provider configuré dans
 * {@code jo.accountant.app.config.ShedLockConfig}. Sans ShedLock, chaque
 * instance déclencherait son propre re-seed en parallèle → race condition.
 *
 * <p>Profil : activé uniquement en profil {@code demo} (le bean n'est instancié
 * que si {@code -Dspring.profiles.active=...demo...} est positionné). En
 * production réelle (sans le profil demo), ce scheduler n'est pas chargé.
 *
 * @author jo@Dev
 */
@Component
@Profile("demo")
public class DemoResetScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DemoResetScheduler.class);

    private final CompanyRepository companyRepository;
    private final DemoDataSeeder demoDataSeeder;
    private final long ttlDays;

    public DemoResetScheduler(CompanyRepository companyRepository,
                              DemoDataSeeder demoDataSeeder,
                              @Value("${app.demo.ttl-days:28}") long ttlDays) {
        this.companyRepository = companyRepository;
        this.demoDataSeeder = demoDataSeeder;
        this.ttlDays = ttlDays;
        LOG.info("DemoResetScheduler initialisé — TTL={} jours (cron quotidien à 03:00)", ttlDays);
    }

    /**
     * Vérification quotidienne à 03:00 du serveur.
     *
     * <p>Cron {@code 0 0 3 * * *} = toutes les 24h à 03:00:00. Pour tester en dev,
     * positionner {@code app.demo.reset-cron} à une expression plus fréquente
     * (ex: {@code 0 0/5 * * * *} pour toutes les 5 min — utile pour valider le
     * scheduler sans attendre 28 jours).
     */
    @Scheduled(cron = "${app.demo.reset-cron:0 0 3 * * *}")
    @Transactional(readOnly = true)
    public void checkAndResetDemoData() {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        LOG.info("DemoResetScheduler — vérification des entreprises démo créées avant {}", cutoff);

        List<Company> demoCompanies = companyRepository.findByIsDemoTrue();
        if (demoCompanies.isEmpty()) {
            LOG.info("DemoResetScheduler — aucune entreprise démo en DB, skip");
            return;
        }

        int resetCount = 0;
        for (Company company : demoCompanies) {
            Instant createdAt = company.getCreatedAt();
            if (createdAt == null) {
                LOG.warn("DemoResetScheduler — Company {} (isDemo=true) a createdAt=null, skip", company.getId());
                continue;
            }
            if (createdAt.isBefore(cutoff)) {
                LOG.info("DemoResetScheduler — Company '{}' (id={}, createdAt={}) dépasse le TTL de {} jours → reset",
                        company.getName(), company.getId(), createdAt, ttlDays);
                resetDemoCompany(company);
                resetCount++;
            } else {
                LOG.debug("DemoResetScheduler — Company '{}' (createdAt={}) encore dans le TTL, skip",
                        company.getName(), createdAt);
            }
        }

        if (resetCount > 0) {
            LOG.info("DemoResetScheduler — {} entreprise(s) démo reset (sur {} total)", resetCount, demoCompanies.size());
        } else {
            LOG.info("DemoResetScheduler — aucune entreprise démo à reset ({} vérifiées)", demoCompanies.size());
        }
    }

    /**
     * Effectue le reset d'une entreprise démo spécifique.
     *
     * <p>Le reset délègue à {@link DemoDataSeeder#seedAll()} qui est idempotent :
     * il supprime d'abord les données business existantes (écritures, factures,
     * paie, notes de frais) puis les recrée via le CompanySeeder correspondant.
     * La company + le user + le COA sont conservés (créés en dehors de la
     * transaction de seed business data).
     *
     * <p>Si le re-seed échoue (ex: contrainte SQL violation), l'erreur est loggée
     * mais ne propagation pas — le scheduler continuera à vérifier les autres
     * entreprises démo. Le prochain run (24h plus tard) réessayera.
     *
     * @param company l'entreprise démo à reset
     */
    private void resetDemoCompany(Company company) {
        try {
            // DemoDataSeeder.seedAllManually() est idempotent et gère lui-même les
            // transactions. On appelle juste seedAllManually() qui va vérifier si la
            // company existe déjà (oui), puis re-seed les données business.
            int count = demoDataSeeder.seedAllManually();
            LOG.info("DemoResetScheduler — reset de '{}' terminé : {} seeders exécutés",
                    company.getName(), count);
        } catch (Exception e) {
            LOG.error("DemoResetScheduler — reset échoué pour '{}' (id={}) : {}",
                    company.getName(), company.getId(), e.getMessage(), e);
            // Ne pas rethrow — le scheduler doit continuer à traiter les autres entreprises.
        }
    }
}
