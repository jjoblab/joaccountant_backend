-- V8_006 — postgres rls (accounting-engine tables)
-- Row-Level Security PostgreSQL sur les tables du module accounting-engine :
-- journal_line et journal_entry.
-- Note : les blocs RLS sur invoice, invoice, third_party et
-- expense_report (tables créées par d'autres modules) ont été déplacés vers
-- leurs modules respectifs : V15_007, V21_003, V10_003, V22_003.


-- ============================================================================
-- journal_line — lignes d'écritures comptables
-- ============================================================================

ALTER TABLE journal_line ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON journal_line;
CREATE POLICY tenant_isolation ON journal_line
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE journal_line FORCE ROW LEVEL SECURITY;

-- ============================================================================
-- journal_entry — entêtes d'écritures comptables
-- ============================================================================

ALTER TABLE journal_entry ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON journal_entry;
CREATE POLICY tenant_isolation ON journal_entry
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE journal_entry FORCE ROW LEVEL SECURITY;
