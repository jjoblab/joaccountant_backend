-- V8_009 — fiscal year audit and constraints
-- Fix Dim 5 H2/H3/H4 (audit v9.4) — Améliore l'intégrité et la traçabilité des exercices fiscaux.
--
-- 3 ajouts idempotents :
-- 1. Colonnes closed_at / closed_by sur fiscal_year pour traçabilité fiscale.
--    Avant ce fix, impossible de répondre à "qui a clôturé l'exercice 2024 et quand ?"
--    sans requêter l'audit_log global (qui peut avoir été purgé).
-- 2. FK sur companies.active_fiscal_year_id → fiscal_year(id).
--    Avant ce fix, le pointeur pouvait référencer un UUID inexistant ou un exercice
--    d'une autre entreprise.
-- 3. Contrainte d'unicité partielle : 1 entreprise = 1 exercice OPEN maximum.
--    Avant ce fix, un ADMIN pouvait créer 2 exercices OPEN chevauchants.
--
-- Approche : ALTER TABLE ... ADD COLUMN IF NOT EXISTS (idempotent) + CREATE INDEX
-- CONCURRENTLY (PostgreSQL, non bloquant). Toutes les opérations sont sûres à ré-exécuter.


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Colonnes closed_at / closed_by sur fiscal_year
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE fiscal_year
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;

ALTER TABLE fiscal_year
    ADD COLUMN IF NOT EXISTS closed_by UUID;

COMMENT ON COLUMN fiscal_year.closed_at IS
    'Fix Dim 5 H4 — Timestamp de clôture de l''exercice (NULL si encore OPEN/LOCKED). Peuplé par FiscalYearClosingService.closeFiscalYear.';
COMMENT ON COLUMN fiscal_year.closed_by IS
    'Fix Dim 5 H4 — ID de l''utilisateur qui a clôturé l''exercice (NULL si encore OPEN/LOCKED). Peuplé par FiscalYearClosingService.closeFiscalYear.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. FK sur companies.active_fiscal_year_id → fiscal_year(id)
-- ─────────────────────────────────────────────────────────────────────────────
-- Note : on ne peut pas utiliser ADD CONSTRAINT IF NOT EXISTS en PostgreSQL (pas supporté).
-- On vérifie l'existence via une condition DO block.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_companies_active_fy'
          AND conrelid = 'companies'::regclass
    ) THEN
        ALTER TABLE companies
            ADD CONSTRAINT fk_companies_active_fy
            FOREIGN KEY (active_fiscal_year_id) REFERENCES fiscal_year(id)
            ON DELETE SET NULL;
    END IF;
END $$;

COMMENT ON CONSTRAINT fk_companies_active_fy ON companies IS
    'Fix Dim 5 H3 — Garantit que active_fiscal_year_id pointe vers un exercice existant. ON DELETE SET NULL pour ne pas casser la company si l''exercice est supprimé.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Contrainte d'unicité partielle : 1 entreprise = 1 exercice OPEN maximum
-- ─────────────────────────────────────────────────────────────────────────────
-- Cette contrainte empêche la création de 2 exercices OPEN simultanés pour la même
-- entreprise. Le code applicatif (AccountingEngineService.createFiscalYear) peut aussi
-- vérifier en amont, mais la contrainte DB est le garde-fou ultime.
-- Note : un index partiel WHERE status = 'OPEN' est plus efficace qu'un trigger.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'uc_one_open_per_company'
          AND tablename = 'fiscal_year'
    ) THEN
        CREATE UNIQUE INDEX uc_one_open_per_company
            ON fiscal_year (company_id)
            WHERE status = 'OPEN';
    END IF;
END $$;

COMMENT ON INDEX uc_one_open_per_company IS
    'Fix Dim 5 H2 — Garantit qu''une entreprise ne peut avoir qu''un seul exercice OPEN à la fois. Complète le guard applicatif dans AccountingEngineService.createFiscalYear.';
