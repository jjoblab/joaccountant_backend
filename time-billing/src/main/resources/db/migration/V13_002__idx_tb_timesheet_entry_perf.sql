-- V13_002 — index perf tb_timesheet_entry
-- Index composites sur tb_timesheet_entry pour accélérer le reporting
-- "temps passé par employé sur une période" (ReportingService.getDashboard).
-- (Historiquement dans V38 du module accounting-engine, déplacé ici car
--  tb_timesheet_entry est créée en V13_001.)


CREATE INDEX IF NOT EXISTS idx_tb_entry_company_resource_date
    ON tb_timesheet_entry (company_id, resource_user_id, entry_date);

CREATE INDEX IF NOT EXISTS idx_tb_entry_company_date
    ON tb_timesheet_entry (company_id, entry_date);

COMMENT ON INDEX idx_tb_entry_company_resource_date IS
    'Accélère le reporting temps passé par employé sur une période.';
COMMENT ON INDEX idx_tb_entry_company_date IS
    'Accélère le reporting temps passé tous employés sur une période.';
