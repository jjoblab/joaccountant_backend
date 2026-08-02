-- V22_003 — postgres rls expense_report
-- Row-Level Security sur expense_report (notes de frais — données employé + TVA déductible).
-- (Historiquement dans V51 du module accounting-engine, déplacé ici car
--  expense_report est créée en V22_001.)


ALTER TABLE expense_report ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON expense_report;
CREATE POLICY tenant_isolation ON expense_report
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE expense_report FORCE ROW LEVEL SECURITY;
