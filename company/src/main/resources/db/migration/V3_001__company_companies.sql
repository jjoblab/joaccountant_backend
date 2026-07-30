-- V3_001 — companies table (§13 Phase 1).
-- A Company IS the tenant; it does NOT have a company_id column.

CREATE TABLE IF NOT EXISTS companies (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    name                        VARCHAR(255) NOT NULL,
    legal_form                  VARCHAR(30) NOT NULL,
    country                     CHAR(2)     NOT NULL,
    functional_currency         CHAR(3)     NOT NULL,
    sector                      VARCHAR(20) NOT NULL,
    accounting_framework_id     UUID        NOT NULL,
    fiscal_year_start_month     INT         NOT NULL,
    wizard_step                 INT         NOT NULL DEFAULT 1,
    wizard_completed            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_companies_legal_form CHECK (legal_form IN ('SOLE_PROPRIETORSHIP','SARL','SA','SAS','NGO','ASSOCIATION','OTHER')),
    CONSTRAINT chk_companies_sector      CHECK (sector IN ('COMMERCE','SERVICE','ONG','MIXTE')),
    CONSTRAINT chk_companies_fy_start    CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    CONSTRAINT chk_companies_country     CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_companies_currency    CHECK (functional_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS idx_companies_created_by ON companies (created_by);

-- FK to accounting_framework
ALTER TABLE companies
    ADD CONSTRAINT fk_companies_accounting_framework
    FOREIGN KEY (accounting_framework_id) REFERENCES accounting_framework(id);
