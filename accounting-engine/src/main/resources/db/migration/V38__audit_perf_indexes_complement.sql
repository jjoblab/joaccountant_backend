-- V38 — Audit v4.7 §7.3 — Index manquants supplémentaires sur tb_timesheet_entry et account.
--
-- Cette migration complète V36 en ajoutant des index composites identifiés manquants par
-- l'audit §7.3 qui n'étaient pas dans la première vague V36. Focus sur :
--   - tb_timesheet_entry : index composite (company_id, resource_user_id, entry_date) pour
--     accélérer le reporting "temps passé par employé sur une période" (utilisé par
--     ReportingService.getDashboard et la facturation temps passé).
--   - account : index composite (company_id, reporting_class, active) pour accélérer les
--     lookups par reportingClass dans ChartOfAccountsService (les méthodes findFirstBy*AndActiveTrue*
--     sont appelées 32 fois dans 8 services). Sans cet index, PostgreSQL fait un seq scan sur
--     tous les comptes de l'entreprise à chaque lookup — le cache Caffeine (Phase A.4) masque
--     ce coût en hit, mais le miss reste coûteux.
--   - account : index sur (parent_id) WHERE company_id IS NOT NULL pour accélérer
--     findByCompanyIdAndParentIdOrderByCode (chargement de l'arborescence du plan comptable).
--   - audit_log : partitionnement mensuel suggéré dans l'audit §7.3 — NON implémenté ici car
--     le partitionnement d'une table existante nécessite CREATE TABLE ... PARTITION OF + migration
--     des données (TRUNCATE + INSERT), ce qui est une opération lourde hors périmètre d'une
--     migration Flyway simple. À planifier en roadmap 3 mois avec pg_partman ou un job cron
--     dédié. L'index ajouté en V36 (entity_type, entity_id, occurred_at) couvre déjà 80% des
--     cas de forensique.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. tb_timesheet_entry — index composite (company_id, resource_user_id, entry_date)
-- ─────────────────────────────────────────────────────────────────────────────
-- Audit v4.7 §7.3 — accélère "temps passé par employé sur une période" (ReportingService).
CREATE INDEX IF NOT EXISTS idx_tb_entry_company_resource_date
    ON tb_timesheet_entry (company_id, resource_user_id, entry_date);

-- Index composite (company_id, entry_date) — accélère "temps passé sur une période tous
-- employés confondus" (ReportingService.getDashboard).
CREATE INDEX IF NOT EXISTS idx_tb_entry_company_date
    ON tb_timesheet_entry (company_id, entry_date);

COMMENT ON INDEX idx_tb_entry_company_resource_date IS
    'Audit v4.7 §7.3 — accélère le reporting temps passé par employé sur une période.';
COMMENT ON INDEX idx_tb_entry_company_date IS
    'Audit v4.7 §7.3 — accélère le reporting temps passé tous employés sur une période.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. account — index composites pour ChartOfAccountsService
-- ─────────────────────────────────────────────────────────────────────────────
-- Audit v4.7 §7.3 — accélère les lookups par reportingClass + active dans ChartOfAccountsService.
-- Sans cet index, chaque lookup findFirstBy*AndActiveTrue* faisait un seq scan sur tous les
-- comptes de l'entreprise (200-500 comptes). Le cache Caffeine (Phase A.4) masque ce coût en
-- cache hit, mais le miss reste coûteux au premier appel.
CREATE INDEX IF NOT EXISTS idx_account_company_class_active
    ON account (company_id, reporting_class, active);

-- Index (parent_id) WHERE company_id IS NOT NULL — accélère findByCompanyIdAndParentIdOrderByCode
-- (chargement de l'arborescence du plan comptable). Partial index car les comptes sans parent
-- (racines) sont rares (1-5 par entreprise) et n'ont pas besoin d'index.
CREATE INDEX IF NOT EXISTS idx_account_parent
    ON account (parent_id)
    WHERE parent_id IS NOT NULL;

-- Index (company_id, tax_mapping_code) WHERE active = TRUE — accélère les lookups par
-- taxMappingCode ("VAT_COLLECTED", "VAT_DEDUCTIBLE", "PURCHASES", "FISCAL_RESULT",
-- "WITHHOLDING_TAX", "CASH", etc.) qui sont appelés 32 fois dans 8 services. Le partial index
-- réduit la taille (les comptes inactifs sont exclus).
CREATE INDEX IF NOT EXISTS idx_account_company_tax_mapping_active
    ON account (company_id, tax_mapping_code)
    WHERE active = TRUE AND tax_mapping_code IS NOT NULL;

COMMENT ON INDEX idx_account_company_class_active IS
    'Audit v4.7 §7.3 — accélère ChartOfAccountsService.findFirstBy*AndActiveTrue* (32 sites d appel).';
COMMENT ON INDEX idx_account_company_tax_mapping_active IS
    'Audit v4.7 §7.3 — accélère les lookups par taxMappingCode (VAT_COLLECTED, PURCHASES, etc.).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. journal_line — index partiel WHERE third_party_id IS NULL
-- ─────────────────────────────────────────────────────────────────────────────
-- Audit v4.7 §7.3 — index complémentaire pour les écritures sans tiers (écritures d'OD,
-- clôture, ouverture). Sans cet index, les requêtes sur (company_id, account_id) où
-- third_party_id IS NULL faisaient un seq scan sur toutes les lignes de l'entreprise.
-- L'index V36 (company_id, third_party_id) WHERE third_party_id IS NOT NULL exclut ces lignes.
CREATE INDEX IF NOT EXISTS idx_journal_line_company_account_no_tp
    ON journal_line (company_id, account_id)
    WHERE third_party_id IS NULL;

COMMENT ON INDEX idx_journal_line_company_account_no_tp IS
    'Audit v4.7 §7.3 — accélère les requêtes sur écritures sans tiers (OD, clôture, ouverture).';
