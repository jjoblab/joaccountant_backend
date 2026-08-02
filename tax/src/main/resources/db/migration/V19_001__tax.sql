-- V19_001 — tax (module :tax, §13 Phase 16).


CREATE TABLE IF NOT EXISTS tax_rule (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id            UUID,
    code                  VARCHAR(30) NOT NULL,
    label                 VARCHAR(200) NOT NULL,
    rate                  NUMERIC(5, 2) NOT NULL,
    payable_account_id    UUID,
    receivable_account_id UUID,
    applicable_from       DATE        NOT NULL,
    applicable_to         DATE,
    active                BOOLEAN     NOT NULL DEFAULT TRUE,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_tax_rate CHECK (rate >= 0 AND rate <= 100)
);

CREATE INDEX IF NOT EXISTS idx_tax_rule_company ON tax_rule (company_id);

CREATE TABLE IF NOT EXISTS withholding_rule (
    id                              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                      UUID        NOT NULL,
    code                            VARCHAR(30) NOT NULL,
    label                           VARCHAR(200) NOT NULL,
    rate                            NUMERIC(5, 2) NOT NULL,
    applicable_third_party_types    JSONB,
    active                          BOOLEAN     NOT NULL DEFAULT TRUE,
    version                         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_wh_rate CHECK (rate >= 0 AND rate <= 100)
);

CREATE INDEX IF NOT EXISTS idx_wh_rule_company ON withholding_rule (company_id);
