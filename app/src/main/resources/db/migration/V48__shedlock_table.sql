-- V37 — Audit v4.7 §9.2 Finding #5 — Table shedlock pour ShedLock (verrou distribué des tâches cron).
--
-- ShedLock utilise cette table pour élire un leader par tâche planifiée en déploiement
-- multi-instances. Sans cette table, chaque instance K8s exécute le cron → 3 replicas = 3×
-- les alertes envoyées, 3× les écritures d'audit, etc.
--
-- Schéma standard imposé par ShedLock 5.x (JdbcTemplateLockProvider) — ne pas modifier les
-- noms de colonnes sans updater la lib.
--
-- Notes :
--   - Pas d'index supplémentaire : la PK sur (name) suffit (taille de la table = nombre de
--     tâches cron = ~5-10 lignes).
--   - Pas de multi-tenant : la table shedlock est GLOBALE (pas de company_id) car les tâches
--     cron sont par instance, pas par tenant. C'est la seule exception avec audit_log.
--   - lock_until est mis à jour à chaque acquisition/relâchement du lock par ShedLock.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

COMMENT ON TABLE shedlock IS
    'Audit v4.7 §9.2 #5 — ShedLock : verrou distribué pour ScheduledAlertsConfig et autres tâches @Scheduled en multi-instances.';
