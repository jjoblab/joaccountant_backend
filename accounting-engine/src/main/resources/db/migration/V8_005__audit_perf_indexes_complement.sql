-- V8_005 — audit perf indexes complement
-- Index composites sur account et journal_line (tables du module accounting-engine
-- ou antérieures). Les index sur tb_timesheet_entry (table créée par le module
-- time-billing en V13_001) ont été déplacés vers V13_002.


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. account — index composites pour ChartOfAccountsService
-- ─────────────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_account_company_class_active
    ON account (company_id, reporting_class, active);

CREATE INDEX IF NOT EXISTS idx_account_parent
    ON account (parent_id)
    WHERE parent_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_account_company_tax_mapping_active
    ON account (company_id, tax_mapping_code)
    WHERE active = TRUE AND tax_mapping_code IS NOT NULL;

COMMENT ON INDEX idx_account_company_class_active IS
    'Accélère ChartOfAccountsService.findFirstBy*AndActiveTrue* (32 sites d appel).';
COMMENT ON INDEX idx_account_company_tax_mapping_active IS
    'Accélère les lookups par taxMappingCode (VAT_COLLECTED, PURCHASES, etc.).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. journal_line — index partiel WHERE third_party_id IS NULL
-- ─────────────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_journal_line_company_account_no_tp
    ON journal_line (company_id, account_id)
    WHERE third_party_id IS NULL;

COMMENT ON INDEX idx_journal_line_company_account_no_tp IS
    'Accélère les requêtes sur écritures sans tiers (OD, clôture, ouverture).';
