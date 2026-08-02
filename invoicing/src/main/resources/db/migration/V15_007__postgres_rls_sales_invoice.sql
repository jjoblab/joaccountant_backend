-- V15_007 — postgres rls sales_invoice
-- Row-Level Security sur sales_invoice (factures clients — TVA, CA, recouvrement).
-- (Historiquement dans V51 du module accounting-engine, déplacé ici car
--  sales_invoice est créée en V15_001.)


ALTER TABLE sales_invoice ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON sales_invoice;
CREATE POLICY tenant_isolation ON sales_invoice
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE sales_invoice FORCE ROW LEVEL SECURITY;
