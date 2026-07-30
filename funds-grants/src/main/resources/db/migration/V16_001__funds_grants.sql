-- V16_001 — funds-grants (module :funds-grants, §13 Phase 14).

CREATE TABLE IF NOT EXISTS fg_grant (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    donor_third_party_id    UUID        NOT NULL,
    code                    VARCHAR(30) NOT NULL,
    label                   VARCHAR(200) NOT NULL,
    total_amount            NUMERIC(19, 4) NOT NULL,
    currency                CHAR(3)     NOT NULL DEFAULT 'HTG',
    start_date              DATE        NOT NULL,
    end_date                DATE,
    restriction_type        VARCHAR(15) NOT NULL DEFAULT 'RESTRICTED',
    analytical_value_id     UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_fg_grant_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_fg_restriction CHECK (restriction_type IN ('RESTRICTED','UNRESTRICTED'))
);

CREATE INDEX IF NOT EXISTS idx_fg_grant_company ON fg_grant (company_id);
CREATE INDEX IF NOT EXISTS idx_fg_grant_donor ON fg_grant (donor_third_party_id);

CREATE TABLE IF NOT EXISTS fg_donation_receipt (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    grant_id                UUID REFERENCES fg_grant(id) ON DELETE SET NULL,
    donor_third_party_id    UUID        NOT NULL,
    amount                  NUMERIC(19, 4) NOT NULL,
    receipt_number          VARCHAR(50) NOT NULL,
    receipt_date            DATE        NOT NULL,
    description             VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_fg_receipt_amount CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_fg_receipt_company ON fg_donation_receipt (company_id);
CREATE INDEX IF NOT EXISTS idx_fg_receipt_grant ON fg_donation_receipt (grant_id);
CREATE INDEX IF NOT EXISTS idx_fg_receipt_donor ON fg_donation_receipt (donor_third_party_id);
