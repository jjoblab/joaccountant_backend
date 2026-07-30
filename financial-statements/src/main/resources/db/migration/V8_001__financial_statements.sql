-- V8_001 — financial-statements (module :financial-statements, §13 Phase 6).
--
-- Table : financial_statement_snapshot
-- Un snapshot par (company_id, type, period_id) — contrainte unique.

CREATE TABLE IF NOT EXISTS financial_statement_snapshot (
    id            UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id    UUID        NOT NULL,
    type          VARCHAR(20) NOT NULL,
    period_id     UUID        NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL,
    frozen        BOOLEAN     NOT NULL DEFAULT TRUE,
    content_json  JSONB       NOT NULL,
    as_of_date    DATE,
    from_date     DATE,
    to_date       DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_fss_company_type_period UNIQUE (company_id, type, period_id),
    CONSTRAINT chk_fss_type CHECK (type IN ('BALANCE_SHEET','INCOME_STATEMENT'))
);

CREATE INDEX IF NOT EXISTS idx_fss_company ON financial_statement_snapshot (company_id);
CREATE INDEX IF NOT EXISTS idx_fss_period ON financial_statement_snapshot (period_id);
