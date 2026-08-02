-- V15_001 — invoicing (module :invoicing, §13 Phase 12).


CREATE TABLE IF NOT EXISTS sales_invoice (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID        NOT NULL,
    third_party_id              UUID        NOT NULL,
    type                        VARCHAR(15) NOT NULL DEFAULT 'STANDARD',
    status                      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    invoice_number              VARCHAR(50),
    issue_date                  DATE,
    due_date                    DATE,
    currency                    CHAR(3)     NOT NULL DEFAULT 'HTG',
    subtotal                    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_amount                  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_amount                NUMERIC(19, 4) NOT NULL DEFAULT 0,
    paid_amount                 NUMERIC(19, 4) NOT NULL DEFAULT 0,
    credit_note_for_invoice_id  UUID,
    journal_entry_id            UUID,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_si_type CHECK (type IN ('STANDARD','CREDIT_NOTE')),
    CONSTRAINT chk_si_status CHECK (status IN ('DRAFT','ISSUED','PARTIALLY_PAID','PAID','VOID'))
);

CREATE INDEX IF NOT EXISTS idx_si_company ON sales_invoice (company_id);
CREATE INDEX IF NOT EXISTS idx_si_company_status ON sales_invoice (company_id, status);
CREATE INDEX IF NOT EXISTS idx_si_third_party ON sales_invoice (third_party_id);

CREATE TABLE IF NOT EXISTS invoice_line (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    invoice_id          UUID        NOT NULL REFERENCES sales_invoice(id) ON DELETE CASCADE,
    description         VARCHAR(500) NOT NULL,
    quantity            NUMERIC(19, 4) NOT NULL,
    unit_price          NUMERIC(19, 4) NOT NULL,
    discount_percent    NUMERIC(5, 2) NOT NULL DEFAULT 0,
    tax_rate            NUMERIC(5, 2) NOT NULL DEFAULT 0,
    item_id             UUID,
    timesheet_entry_id  UUID,
    line_total_ht       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total_tax      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    -- Règle : itemId OU timesheetEntryId, jamais les deux
    CONSTRAINT chk_il_item_xor_timesheet CHECK (
        (item_id IS NOT NULL AND timesheet_entry_id IS NULL) OR
        (item_id IS NULL AND timesheet_entry_id IS NOT NULL) OR
        (item_id IS NULL AND timesheet_entry_id IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_il_invoice ON invoice_line (invoice_id);
CREATE INDEX IF NOT EXISTS idx_il_company ON invoice_line (company_id);
