-- V21_002 — index perf purchase_invoice
-- Index composite sur purchase_invoice pour accélérer la balance âgée
-- fournisseurs et les alertes d'échéance.
-- (Historiquement dans V36 du module accounting-engine, déplacé ici car
--  purchase_invoice est créée en V21_001.)


CREATE INDEX IF NOT EXISTS idx_purchase_invoice_company_due_open
    ON purchase_invoice (company_id, due_date)
    WHERE status IN ('RECEIVED', 'PARTIALLY_PAID');
