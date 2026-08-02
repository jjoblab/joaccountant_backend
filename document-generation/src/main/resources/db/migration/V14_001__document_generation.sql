-- V14_001 — document-generation (module :document-generation, §8, §13 Phase 11).
-- Tables : document_template, generated_document.
-- document_template.company_id est NULLABLE (gabarit global par défaut).


CREATE TABLE IF NOT EXISTS document_template (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID,
    document_type   VARCHAR(25) NOT NULL,
    html_template   TEXT        NOT NULL,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    is_default      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_dt_document_type CHECK (document_type IN (
        'INVOICE','CREDIT_NOTE','DONATION_RECEIPT','BALANCE_SHEET',
        'INCOME_STATEMENT','GENERAL_LEDGER','DONOR_REPORT'
    ))
);

CREATE INDEX IF NOT EXISTS idx_dt_company ON document_template (company_id);
CREATE INDEX IF NOT EXISTS idx_dt_company_type ON document_template (company_id, document_type, is_default, active);

CREATE TABLE IF NOT EXISTS generated_document (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    document_type   VARCHAR(25) NOT NULL,
    resource_id     UUID        NOT NULL,
    storage_key     VARCHAR(200) NOT NULL,
    generated_at    TIMESTAMPTZ NOT NULL,
    generated_by    UUID,
    checksum        CHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_gd_company_resource UNIQUE (company_id, resource_id),
    CONSTRAINT chk_gd_document_type CHECK (document_type IN (
        'INVOICE','CREDIT_NOTE','DONATION_RECEIPT','BALANCE_SHEET',
        'INCOME_STATEMENT','GENERAL_LEDGER','DONOR_REPORT'
    ))
);

CREATE INDEX IF NOT EXISTS idx_gd_company ON generated_document (company_id);
CREATE INDEX IF NOT EXISTS idx_gd_resource ON generated_document (resource_id);
