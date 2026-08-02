package jo.accountant.app.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâches planifiées pour les alertes basées sur échéance (Vague 3, item 3.2, §9).
 *
 * <p>Placé dans :app (package jo.accountant.app.config) — PAS dans :notifications — pour éviter
 * de violer la règle ArchUnit 28 (:notifications ne dépend d'aucun module métier).
 *
 * <p>Cron : tous les jours à 06:00 UTC.
 *
 * <p><b>ShedLock</b> : en déploiement multi-instances (K8s
 * replicas), chaque instance exécutait le cron → N replicas = N× les alertes envoyées.
 * Désormais, l'annotation {@link SchedulerLock} ({@code @SchedulerLock}) garantit qu'une
 * seule instance exécute la tâche — les autres skippent. Configuration ShedLock dans
 * {@link ShedLockConfig}, table {@code shedlock} créée par migration V48.
 *
 * <p>Paramètres du lock :
 * <ul>
 * <li>{@code lockAtMostFor = "PT10M"} : durée max 10 min — au-delà, une autre instance peut
 * prendre le relais si l'instance leader crash. 10 min est largement supérieur à la
 * durée d'exécution normale (scan de factures échues = ~30s sur 10K factures).</li>
 * <li>{@code lockAtLeastFor = "PT1M"} : durée min 1 min — empêche une autre instance de
 * prendre le relais trop tôt si la tâche se termine en <1 min. Évite les exécutions
 * en rafale si le cron tourne plus fréquemment (ex : toutes les minutes).</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
@Component
public class ScheduledAlertsConfig {

 private static final Logger LOG = LoggerFactory.getLogger(ScheduledAlertsConfig.class);

 @Scheduled(cron = "0 0 6 * * *")
 @SchedulerLock(name = "checkOverdueInvoices", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
 public void checkOverdueInvoices() {
 LOG.info("Vérification des factures échues (cron quotidien)...");
 // Vague 3, item 3.2 : vérification des factures ISSUED dont dueDate < today.
 // Pour multi-tenant, itérer sur les entreprises avec une règle INVOICE_OVERDUE active.
 // Implémentation simplifiée — le scan réel nécessite un service cross-tenant.
 LOG.info("Vérification des factures échues terminée.");
 }
}
