-- V22_001 — expenses
-- V25 — expenses (module :expenses, — 4 nouveaux modules bonus).
-- Notes de frais + lignes. Cycle de vie : DRAFT → SUBMITTED → APPROVED → PAID (ou REJECTED).


CREATE TABLE IF NOT EXISTS expense_report (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    third_party_id      UUID,
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    expense_date        DATE        NOT NULL,
    currency            CHAR(3)     NOT NULL DEFAULT 'HTG',
    description         VARCHAR(1000),
    total_amount        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    paid_directly       BOOLEAN     NOT NULL DEFAULT FALSE,
    journal_entry_id    UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_er_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAID'))
);

CREATE INDEX IF NOT EXISTS idx_er_company ON expense_report (company_id);
CREATE INDEX IF NOT EXISTS idx_er_company_status ON expense_report (company_id, status);
CREATE INDEX IF NOT EXISTS idx_er_third_party ON expense_report (third_party_id);

CREATE TABLE IF NOT EXISTS expense_line (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    report_id           UUID        NOT NULL REFERENCES expense_report(id) ON DELETE CASCADE,
    category            VARCHAR(20),
    description         VARCHAR(500) NOT NULL,
    amount              NUMERIC(19, 4) NOT NULL,
    expense_account_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_el_category CHECK (category IS NULL OR category IN
        ('TRAVEL','MEALS','SUPPLIES','OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_el_report ON expense_line (report_id);
CREATE INDEX IF NOT EXISTS idx_el_company ON expense_line (company_id);
