-- V16_001 — bank-reconciliation (module :bank-reconciliation, §13 Phase 13).


CREATE TABLE IF NOT EXISTS bank_account (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id            UUID        NOT NULL,
    treasury_account_id   UUID        NOT NULL,
    label                 VARCHAR(200) NOT NULL,
    account_number        VARCHAR(50),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ba_company ON bank_account (company_id);

CREATE TABLE IF NOT EXISTS bank_statement_import (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    bank_account_id UUID        NOT NULL REFERENCES bank_account(id) ON DELETE CASCADE,
    format          VARCHAR(5)  NOT NULL,
    storage_key     VARCHAR(200) NOT NULL,
    imported_at     TIMESTAMPTZ NOT NULL,
    line_count      INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_bsi_format CHECK (format IN ('CSV','OFX'))
);

CREATE INDEX IF NOT EXISTS idx_bsi_account ON bank_statement_import (bank_account_id);

CREATE TABLE IF NOT EXISTS bank_statement_line (
    id                        UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                UUID        NOT NULL,
    import_id                 UUID        NOT NULL REFERENCES bank_statement_import(id) ON DELETE CASCADE,
    bank_account_id           UUID        NOT NULL,
    line_date                 DATE        NOT NULL,
    amount                    NUMERIC(19, 4) NOT NULL,
    description               VARCHAR(500),
    matched                   BOOLEAN     NOT NULL DEFAULT FALSE,
    matched_journal_line_id   UUID,
    matched_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                UUID,
    updated_by                UUID,
    version                   BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_bsl_account ON bank_statement_line (bank_account_id);
CREATE INDEX IF NOT EXISTS idx_bsl_account_unmatched ON bank_statement_line (bank_account_id) WHERE matched = FALSE;
CREATE INDEX IF NOT EXISTS idx_bsl_import ON bank_statement_line (import_id);
