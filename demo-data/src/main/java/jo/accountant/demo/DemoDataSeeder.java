package jo.accountant.demo;

import java.util.List;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.demo.seeders.CompanySeeder;
import jo.accountant.demo.seeders.DemoUserSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.1/V8.2 — Orchestrateur principal du module Démos.
 *
 * <p>Au démarrage avec le profil Spring {@code demo}, seed automatiquement les 4 entreprises
 * fictives + leurs utilisateurs démo OWNER (V8.2).
 */
@Component
@Profile("demo")
public class DemoDataSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final List<CompanySeeder> seeders;
    private final CompanyRepository companyRepository;
    private final DemoUserSeeder demoUserSeeder;

    public DemoDataSeeder(List<CompanySeeder> seeders,
                           CompanyRepository companyRepository,
                           DemoUserSeeder demoUserSeeder) {
        this.seeders = seeders;
        this.companyRepository = companyRepository;
        this.demoUserSeeder = demoUserSeeder;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    @Async
    
    public void seedAllOnStartup() {
        LOG.info("═══════════════════════════════════════════════════════════");
        LOG.info("  V8.2 Module Démos — Démarrage du seed (entreprises + utilisateurs)");
        LOG.info("  {} entreprises × 2 exercices fiscaux + 4 utilisateurs démo", seeders.size());
        LOG.info("═══════════════════════════════════════════════════════════");

        int totalRecords = 0;
        for (CompanySeeder seeder : seeders) {
            try {
                LOG.info("→ Seed {} démarré...", seeder.demoCode());
                long start = System.currentTimeMillis();
                int count = seeder.seed();
                long duration = System.currentTimeMillis() - start;
                LOG.info("✓ Seed {} terminé : {} enregistrements en {} ms",
                    seeder.demoCode(), count, duration);
                totalRecords += count;

                // V8.2 — Créer l'utilisateur démo OWNER pour cette entreprise
                Company company = companyRepository.findAll().stream()
                    .filter(c -> seeder.companyName().equals(c.getName()))
                    .filter(c -> Boolean.TRUE.equals(c.getIsDemo()))
                    .findFirst()
                    .orElse(null);
                if (company != null) {
                    String email = DemoUserSeeder.demoEmail(seeder.demoCode());
                    String fullName = DemoUserSeeder.demoUserName(seeder.demoCode());
                    demoUserSeeder.seedDemoUser(company, email, fullName);
                }
            } catch (Exception e) {
                LOG.error("✗ Seed {} échoué : {}", seeder.demoCode(), e.getMessage(), e);
            }
        }

        LOG.info("═══════════════════════════════════════════════════════════");
        LOG.info("  V8.1 Module Démos — Seed terminé ({} enregistrements au total)", totalRecords);
        LOG.info("  Endpoints publics : /api/v1/demos/**");
        LOG.info("═══════════════════════════════════════════════════════════");
    }
}
