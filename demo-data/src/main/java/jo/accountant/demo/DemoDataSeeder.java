package jo.accountant.demo;

import java.util.List;
import jo.accountant.demo.seeders.CompanySeeder;
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
 * V8.1 — Orchestrateur principal du module Démos.
 *
 * <p>Au démarrage avec le profil Spring {@code demo}, seed automatiquement les 4 entreprises
 * fictives si elles n'existent pas déjà (idempotence via vérification du nom + isDemo=true).
 *
 * <p>Peut aussi être déclenché manuellement via POST /api/v1/demos/seed (rôle ADMIN).
 */
@Component
@Profile("demo")
public class DemoDataSeeder {

  private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final List<CompanySeeder> seeders;

  public DemoDataSeeder(List<CompanySeeder> seeders) {
    this.seeders = seeders;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(100)
  @Async
  @Transactional
  public void seedAllOnStartup() {
    seedAll("seed automatique (startup)");
  }

  /**
   * v2.5.2 — Déclenche le seed manuellement (depuis POST /api/v1/demos/seed).
   * Synchrone (pas @Async) pour que l'appelant reçoive le résultat.
   * Idempotent : les seeders vérifient l'existence par nom + isDemo=true.
   */
  @Transactional
  public int seedAllManually() {
    return seedAll("seed manuel (POST /api/v1/demos/seed)");
  }

  private int seedAll(String source) {
    LOG.info("═══════════════════════════════════════════════════════════");
    LOG.info("  V8.1 Module Démos — {}", source);
    LOG.info("  {} entreprises × 2 exercices fiscaux (FY2024-2025 + FY2025-2026)", seeders.size());
    LOG.info("═══════════════════════════════════════════════════════════");

    int totalRecords = 0;
    for (CompanySeeder seeder : seeders) {
      try {
        LOG.info("→ Seed {} démarré...", seeder.demoCode());
        long start = System.currentTimeMillis();
        int count = seeder.seed();
        long duration = System.currentTimeMillis() - start;
        LOG.info(
            "✓ Seed {} terminé : {} enregistrements en {} ms", seeder.demoCode(), count, duration);
        totalRecords += count;
      } catch (Exception e) {
        LOG.error("✗ Seed {} échoué : {}", seeder.demoCode(), e.getMessage(), e);
      }
    }

    LOG.info("═══════════════════════════════════════════════════════════");
    LOG.info("  V8.1 Module Démos — Seed terminé ({} enregistrements au total)", totalRecords);
    LOG.info("  Endpoints publics : /api/v1/demos/**");
    LOG.info("═══════════════════════════════════════════════════════════");
    return totalRecords;
  }
}
