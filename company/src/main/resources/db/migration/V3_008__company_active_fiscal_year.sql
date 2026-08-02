-- V3_008 — company active fiscal year
-- V32 — Active fiscal year on Company ().
-- The active fiscal year is the single source of truth for which period
-- the company is currently working in. All data endpoints filter by this.


ALTER TABLE companies ADD COLUMN IF NOT EXISTS active_fiscal_year_id UUID;
