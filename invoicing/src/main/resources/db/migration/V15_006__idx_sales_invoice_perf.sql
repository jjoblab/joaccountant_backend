-- V15_006 — index perf sales_invoice
-- Index composite sur sales_invoice pour accélérer la balance âgée clients
-- et les alertes d'échéance (ScheduledAlertsConfig).
-- (Historiquement dans V36 du module accounting-engine, déplacé ici car
--  sales_invoice est créée en V15_001.)


CREATE INDEX IF NOT EXISTS idx_sales_invoice_company_due_open
    ON sales_invoice (company_id, due_date)
    WHERE status IN ('ISSUED', 'PARTIALLY_PAID');
