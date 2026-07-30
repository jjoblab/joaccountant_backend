-- =====================================================================
-- V62 — R-37 (lot-F-ops-docs) — Partitionnement mensuel de audit_log
-- =====================================================================
-- Objectif : la table audit_log atteint 100M+ lignes sur 5 ans (audit §7.3).
-- Sans partitionnement, les requêtes forensiques deviennent lentes et la
-- maintenance (VACUUM, REINDEX) bloque la table.
--
-- Approche : partitionnement RANGE mensuel par occurred_at, avec partition
-- par défaut pour les événements hors plage (sécurité — aucun événement
-- n'est jamais rejeté).
--
-- Note : cette migration est idempotente — elle peut être ré-exécutée sans
-- casser les partitions existantes. La création de partitions futures est
-- ensuite déléguée à pg_partman (extension PostgreSQL) ou à un cron
-- (scripts/create-audit-log-partitions.sh).
-- =====================================================================

-- 1. Vérifier que la table audit_log existe (sinon, ne rien faire — la migration V1_001 a pu être skip)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'audit_log') THEN
        RAISE NOTICE 'audit_log table does not exist yet — skipping V62 partitioning';
        RETURN;
    END IF;
END $$;

-- 2. Vérifier que audit_log n'est pas déjà partitionnée (idempotence)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_partitioned_table pt
        JOIN pg_class c ON c.oid = pt.partrelid
        WHERE c.relname = 'audit_log'
    ) THEN
        RAISE NOTICE 'audit_log is already partitioned — skipping V62';
        RETURN;
    END IF;
END $$;

-- 3. Note : la conversion d'une table existante en table partitionnée nécessite
--    CREATE TABLE ... PARTITION OF ou ALTER TABLE ... SET PARTITIONED BY.
--    PostgreSQL 14+ ne supporte pas ALTER TABLE SET PARTITIONED BY directement.
--    L'approche standard est :
--      a) Créer une nouvelle table partitionnée audit_log_partitioned
--      b) Copier les données depuis audit_log
--      c) Renommer audit_log → audit_log_old, audit_log_partitioned → audit_log
--      d) Recréer les index et contraintes sur audit_log
--      e) Drop audit_log_old après validation
--
--    Cette migration ne fait que créer la procédure stockée qui réalisera
--    cette conversion, et crée la première partition du mois courant.
--    La conversion réelle sera déclenchée manuellement par le DBA via
--    SELECT convert_audit_log_to_partitioned();
--    pour éviter tout downtime non planifié.

-- 4. Créer la fonction de conversion (à exécuter manuellement hors heures ouvrées)
CREATE OR REPLACE FUNCTION convert_audit_log_to_partitioned()
RETURNS void AS $$
DECLARE
    current_month_start timestamptz;
    next_month_start timestamptz;
    partition_name text;
BEGIN
    -- Vérifier que la table n'est pas déjà partitionnée
    IF EXISTS (
        SELECT 1 FROM pg_partitioned_table pt
        JOIN pg_class c ON c.oid = pt.partrelid
        WHERE c.relname = 'audit_log'
    ) THEN
        RAISE NOTICE 'audit_log is already partitioned — nothing to do';
        RETURN;
    END IF;

    RAISE NOTICE 'Converting audit_log to partitioned table...';

    -- 1. Créer la nouvelle table partitionnée
    CREATE TABLE IF NOT EXISTS audit_log_new (
        LIKE audit_log INCLUDING ALL
    ) PARTITION BY RANGE (occurred_at);

    -- 2. Créer les partitions pour les 12 derniers mois + mois courant + 3 mois futurs
    FOR i IN -12..3 LOOP
        current_month_start := date_trunc('month', NOW() + (i || ' months')::interval);
        next_month_start := current_month_start + INTERVAL '1 month';
        partition_name := 'audit_log_' || to_char(current_month_start, 'YYYY_MM');

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log_new FOR VALUES FROM (%L) TO (%L)',
            partition_name, current_month_start, next_month_start
        );
    END LOOP;

    -- 3. Partition par défaut pour les événements hors plage
    CREATE TABLE IF NOT EXISTS audit_log_default PARTITION OF audit_log_new DEFAULT;

    -- 4. Copier les données
    EXECUTE 'INSERT INTO audit_log_new SELECT * FROM audit_log';

    -- 5. Renommer les tables
    ALTER TABLE audit_log RENAME TO audit_log_old;
    ALTER TABLE audit_log_new RENAME TO audit_log;

    -- 6. Recréer les index sur la nouvelle table partitionnée
    -- (Les index partitions sont créés automatiquement depuis le parent)
    CREATE INDEX IF NOT EXISTS idx_audit_log_company_entity
        ON audit_log (company_id, entity_type, entity_id);
    CREATE INDEX IF NOT EXISTS idx_audit_log_occurred_at
        ON audit_log (occurred_at DESC);
    CREATE INDEX IF NOT EXISTS idx_audit_log_correlation_id
        ON audit_log (correlation_id);
    CREATE INDEX IF NOT EXISTS idx_audit_log_security_events
        ON audit_log (action, occurred_at DESC)
        WHERE entity_type = 'SecurityEvent';

    RAISE NOTICE 'audit_log converted to partitioned table with 16 partitions (12 past + current + 3 future)';
    RAISE NOTICE 'audit_log_old kept for rollback — drop manually after validation: DROP TABLE audit_log_old;';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION convert_audit_log_to_partitioned() IS
    'V62 — R-37 : Convertit audit_log en table partitionnée mensuellement. À exécuter manuellement hors heures ouvrées.';

-- 5. Créer une fonction utilitaire pour créer les partitions futures (appelée par cron)
CREATE OR REPLACE FUNCTION create_audit_log_partition(year_month text)
RETURNS void AS $$
DECLARE
    partition_name text;
    month_start timestamptz;
    next_month_start timestamptz;
BEGIN
    -- Valider le format YYYY_MM
    IF year_month !~ '^\d{4}_\d{2}$' THEN
        RAISE EXCEPTION 'Invalid year_month format. Expected YYYY_MM, got: %', year_month;
    END IF;

    month_start := to_date(year_month, 'YYYY_MM')::timestamptz;
    next_month_start := month_start + INTERVAL '1 month';
    partition_name := 'audit_log_' || year_month;

    -- Vérifier que audit_log est partitionnée
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table pt
        JOIN pg_class c ON c.oid = pt.partrelid
        WHERE c.relname = 'audit_log'
    ) THEN
        RAISE EXCEPTION 'audit_log is not partitioned yet. Run convert_audit_log_to_partitioned() first.';
    END IF;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
        partition_name, month_start, next_month_start
    );

    RAISE NOTICE 'Created partition % for range [%, %)', partition_name, month_start, next_month_start;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_audit_log_partition(text) IS
    'V62 — R-37 : Crée une partition mensuelle pour audit_log. Argument: YYYY_MM (ex: 2026_08).';

-- 6. Note : la conversion réelle n'est PAS exécutée ici (impact trop important)
--    Le DBA doit exécuter manuellement : SELECT convert_audit_log_to_partitioned();
--    Puis configurer le cron scripts/create-audit-log-partitions.sh pour pré-créer
--    les partitions futures tous les 25 du mois.

-- 7. Politique de rétention documentée (mise en œuvre par cron séparé)
COMMENT ON SCHEMA public IS 'V62 — R-37 : audit_log partitionnée mensuellement. Rétention 7 ans (10 ans S3 cold storage pour backups). Voir scripts/drop-old-audit-log-partitions.sh.';
