-- V21_003 — postgres rls purchase_invoice
-- Row-Level Security sur purchase_invoice (factures fournisseurs — TVA déductible).
-- (Historiquement dans V51 du module accounting-engine, déplacé ici car
--  purchase_invoice est créée en V21_001.)


ALTER TABLE purchase_invoice ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON purchase_invoice;
CREATE POLICY tenant_isolation ON purchase_invoice
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE purchase_invoice FORCE ROW LEVEL SECURITY;
