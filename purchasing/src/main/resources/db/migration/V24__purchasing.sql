-- V24 — purchasing (module :purchasing, restructuration 2026-07-24 — 4 nouveaux modules bonus).
-- Factures fournisseur + lignes. Cycle de vie : DRAFT → RECEIVED → PARTIALLY_PAID → PAID / VOID.

CREATE TABLE IF NOT EXISTS purchase_invoice (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID        NOT NULL,
    third_party_id              UUID        NOT NULL,
    type                        VARCHAR(15) NOT NULL DEFAULT 'STANDARD',
    status                      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    invoice_number              VARCHAR(50),
    supplier_reference          VARCHAR(100),
    issue_date                  DATE,
    due_date                    DATE,
    currency                    CHAR(3)     NOT NULL DEFAULT 'HTG',
    subtotal                    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_amount                  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_amount                NUMERIC(19, 4) NOT NULL DEFAULT 0,
    paid_amount                 NUMERIC(19, 4) NOT NULL DEFAULT 0,
    journal_entry_id            UUID,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_pi_type CHECK (type IN ('STANDARD','DEBIT_NOTE')),
    CONSTRAINT chk_pi_status CHECK (status IN ('DRAFT','RECEIVED','PARTIALLY_PAID','PAID','VOID'))
);

CREATE INDEX IF NOT EXISTS idx_pi_company ON purchase_invoice (company_id);
CREATE INDEX IF NOT EXISTS idx_pi_company_status ON purchase_invoice (company_id, status);
CREATE INDEX IF NOT EXISTS idx_pi_third_party ON purchase_invoice (third_party_id);
CREATE INDEX IF NOT EXISTS idx_pi_supplier_ref ON purchase_invoice (company_id, supplier_reference);

CREATE TABLE IF NOT EXISTS purchase_invoice_line (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    invoice_id          UUID        NOT NULL REFERENCES purchase_invoice(id) ON DELETE CASCADE,
    description         VARCHAR(500) NOT NULL,
    quantity            NUMERIC(19, 4) NOT NULL,
    unit_price          NUMERIC(19, 4) NOT NULL,
    tax_rate            NUMERIC(5, 2) NOT NULL DEFAULT 0,
    expense_account_id  UUID,
    line_total_ht       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total_tax      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pil_invoice ON purchase_invoice_line (invoice_id);
CREATE INDEX IF NOT EXISTS idx_pil_company ON purchase_invoice_line (company_id);
