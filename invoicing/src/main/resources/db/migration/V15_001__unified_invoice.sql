-- ════════════════════════════════════════════════════════════════════════
-- V15_001__unified_invoice.sql
-- v9.1 — Table invoice unifiée (ventes + achats) + invoice_line + invoice_line_tax
-- ════════════════════════════════════════════════════════════════════════
-- Remplace les anciennes tables sales_invoice + purchase_invoice par une
-- seule table invoice avec un champ direction (SALES | PURCHASE).
-- Aucune donnée en production — migration clean (pas de data migration).

-- 1. Table invoice unifiée
CREATE TABLE IF NOT EXISTS invoice (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID        NOT NULL,
    direction                   VARCHAR(10) NOT NULL,  -- SALES | PURCHASE
    type                        VARCHAR(15) NOT NULL DEFAULT 'STANDARD',  -- STANDARD | CREDIT_NOTE | DEBIT_NOTE
    status                      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',    -- DRAFT | ISSUED | PARTIALLY_PAID | PAID | VOID
    third_party_id              UUID        NOT NULL,
    invoice_number              VARCHAR(50),
    supplier_reference          VARCHAR(100),  -- PURCHASE only
    issue_date                  DATE,
    due_date                    DATE,
    currency                    CHAR(3)     NOT NULL DEFAULT 'HTG',
    subtotal                    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_amount                  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_amount                NUMERIC(19, 4) NOT NULL DEFAULT 0,
    paid_amount                 NUMERIC(19, 4) NOT NULL DEFAULT 0,
    credit_note_for_invoice_id  UUID,  -- SALES CREDIT_NOTE only
    journal_entry_id            UUID,
    vat_settlement_entry_id     UUID,  -- SALES only (TVA sur encaissement)
    vat_deferred_amount         NUMERIC(19, 4) DEFAULT 0,  -- SALES only
    is_reverse_charge           BOOLEAN     NOT NULL DEFAULT FALSE,  -- SALES only
    withholding_rate            NUMERIC(5, 2),  -- SALES only
    withholding_amount          NUMERIC(19, 4),  -- SALES only
    net_receivable              NUMERIC(19, 4),  -- SALES only
    withholding_rule_id         UUID,  -- SALES only
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_invoice_direction CHECK (direction IN ('SALES', 'PURCHASE')),
    CONSTRAINT chk_invoice_type CHECK (type IN ('STANDARD', 'CREDIT_NOTE', 'DEBIT_NOTE')),
    CONSTRAINT chk_invoice_status CHECK (status IN ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    CONSTRAINT chk_invoice_type_direction CHECK (
        (type = 'STANDARD') OR
        (type = 'CREDIT_NOTE' AND direction = 'SALES') OR
        (type = 'DEBIT_NOTE' AND direction = 'PURCHASE')
    )
);

-- 2. Table invoice_line (unifiée — supporte itemId/timesheetEntryId pour SALES, expenseAccountId pour PURCHASE)
CREATE TABLE IF NOT EXISTS invoice_line (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    invoice_id          UUID        NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    description         VARCHAR(500) NOT NULL,
    quantity            NUMERIC(19, 4) NOT NULL,
    unit_price          NUMERIC(19, 4) NOT NULL,
    discount_percent    NUMERIC(5, 2) NOT NULL DEFAULT 0,
    tax_rate            NUMERIC(5, 2) NOT NULL DEFAULT 0,
    item_id             UUID,              -- SALES only (Commerce — COGS)
    timesheet_entry_id  UUID,              -- SALES only (Service — WIP)
    expense_account_id  UUID,              -- PURCHASE only (compte de charge classe 6)
    line_total_ht       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total_tax      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_il_item_xor_timesheet CHECK (
        (item_id IS NOT NULL AND timesheet_entry_id IS NULL AND expense_account_id IS NULL) OR
        (item_id IS NULL AND timesheet_entry_id IS NOT NULL AND expense_account_id IS NULL) OR
        (item_id IS NULL AND timesheet_entry_id IS NULL AND expense_account_id IS NOT NULL) OR
        (item_id IS NULL AND timesheet_entry_id IS NULL AND expense_account_id IS NULL)
    )
);

-- 3. Indexes
CREATE INDEX IF NOT EXISTS idx_invoice_company ON invoice (company_id);
CREATE INDEX IF NOT EXISTS idx_invoice_company_direction ON invoice (company_id, direction);
CREATE INDEX IF NOT EXISTS idx_invoice_company_status ON invoice (company_id, status);
CREATE INDEX IF NOT EXISTS idx_invoice_company_third_party ON invoice (company_id, third_party_id);
CREATE INDEX IF NOT EXISTS idx_invoice_company_due_open ON invoice (company_id)
    WHERE status IN ('ISSUED', 'PARTIALLY_PAID');
CREATE INDEX IF NOT EXISTS idx_invoice_company_issue_date ON invoice (company_id, issue_date DESC);
CREATE INDEX IF NOT EXISTS idx_invoice_number ON invoice (company_id, invoice_number)
    WHERE invoice_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_invoice_line_invoice ON invoice_line (invoice_id);
CREATE INDEX IF NOT EXISTS idx_invoice_line_expense_account ON invoice_line (expense_account_id)
    WHERE expense_account_id IS NOT NULL;

-- 4. RLS (Row Level Security) — isolation multi-tenant
ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE invoice_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_line
    USING (company_id = current_setting('app.current_tenant', true)::uuid);
