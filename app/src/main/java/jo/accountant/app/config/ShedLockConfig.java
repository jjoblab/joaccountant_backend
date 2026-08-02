package jo.accountant.app.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration ShedLock — verrou distribué pour les tâches planifiées (audit v4.7 §9.2 #5).
 *
 * <p><b>Problème</b> : la v4.7 utilise {@link org.springframework.scheduling.annotation.Scheduled @Scheduled}
 * pour {@code ScheduledAlertsConfig.checkOverdueInvoices}. En déploiement multi-instances
 * (K8s replicas, ECS tasks), chaque instance exécute le cron → 3 replicas = 3 exécutions de
 * la même tâche = 3× les alertes envoyées aux clients, 3× les écritures d'audit, etc.
 *
 * <p><b>Solution</b> : ShedLock utilise une table DB partagée ({@code shedlock}) pour élire un
 * leader par tâche cron. Seule l'instance qui acquiert le lock exécute la tâche — les autres
 * skippent. Le lock est relâché automatiquement à la fin de la tâche ou après un timeout
 * configurable (au cas où l'instance leader crash).
 *
 * <p><b>Backend</b> : JdbcTemplateLockProvider utilise la DataSource principale (PostgreSQL).
 * La table {@code shedlock} est créée par la migration Flyway V48 — ShedLock ne crée PAS la
 * table automatiquement en production (sécurité : on ne laisse pas une lib créer des tables).
 *
 * <p><b>Usage</b> : annoter chaque méthode {@code @Scheduled} avec
 * {@code @SchedulerLock(name = "...", lockAtMostFor = "...", lockAtLeastFor = "...")}.
 * <ul>
 *   <li>{@code lockAtMostFor} : durée max du lock — au-delà, une autre instance peut prendre
 *       le relais (au cas où l'instance leader crash). Doit être supérieure à la durée
 *       d'exécution normale.</li>
 *   <li>{@code lockAtLeastFor} : durée min du lock — empêche une autre instance de prendre
 *       le relais trop tôt si la tâche se termine très vite (évite les exécutions en rafale
 *       si le cron tourne toutes les minutes).</li>
 * </ul>
 *
 * <p><b>Limitation</b> : ShedLock ne garantit pas l'unicité absolue — il y a une fenêtre
 * théorique de quelques ms où deux instances pourraient exécuter la tâche simultanément
 * (race condition sur l'acquisition du lock). Pour les tâches critiques (écritures
 * comptables), utiliser une transaction DB avec SELECT FOR UPDATE en complément.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")  // défaut 5 min — override par @SchedulerLock
public class ShedLockConfig {

    /**
     * Provider ShedLock basé sur JdbcTemplate + DataSource principale (PostgreSQL).
     *
     * <p>Utilise la table {@code shedlock} (créée par migration V48). Le schéma est :
     * <pre>
     * CREATE TABLE shedlock (
     *     name VARCHAR(64) NOT NULL PRIMARY KEY,
     *     lock_until TIMESTAMP NOT NULL,
     *     locked_at TIMESTAMP NOT NULL,
     *     locked_by VARCHAR(255) NOT NULL
     * );
     * </pre>
     *
     * <p>{@code useDbTime()} : utilise {@code CURRENT_TIMESTAMP} côté DB plutôt que
     * {@code Instant.now()} côté Java — évite les drifts d'horloge entre instances.
     * Critique pour un déploiement multi-AZ où les instances peuvent avoir quelques ms de décalage.
     */
    @Bean
    public JdbcTemplateLockProvider lockProvider(DataSource dataSource) {
        // ShedLock 5.10.0 API : Configuration.builder() → build() → new JdbcTemplateLockProvider(config)
        // usingDbTime() : utilise CURRENT_TIMESTAMP côté DB plutôt que Instant.now() côté Java —
        // évite les drifts d'horloge entre instances (critique en multi-AZ).
        JdbcTemplateLockProvider.Configuration config = JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
            .usingDbTime()
            .build();
        return new JdbcTemplateLockProvider(config);
    }
}
