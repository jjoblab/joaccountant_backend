-- V10_001 — third-parties (module :third-parties, §13 Phase 7).
-- Tables : third_party, lettrage_match.


CREATE TABLE IF NOT EXISTS third_party (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    type                    VARCHAR(12) NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    collective_account_id   UUID        NOT NULL,
    dedicated_account_id    UUID,
    active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    email                   VARCHAR(255),
    phone                   VARCHAR(30),
    address                 VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_tp_company_dedicated_account UNIQUE (company_id, dedicated_account_id),
    CONSTRAINT chk_tp_type CHECK (type IN ('CLIENT','SUPPLIER','DONOR','EMPLOYEE','OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_tp_company ON third_party (company_id);
CREATE INDEX IF NOT EXISTS idx_tp_company_type ON third_party (company_id, type);
CREATE INDEX IF NOT EXISTS idx_tp_collective_account ON third_party (collective_account_id);

CREATE TABLE IF NOT EXISTS lettrage_match (
    id                UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id        UUID        NOT NULL,
    third_party_id    UUID        NOT NULL,
    journal_line_ids  JSONB       NOT NULL,
    match_code        VARCHAR(10) NOT NULL,
    status            VARCHAR(10) NOT NULL,
    matched_amount    NUMERIC(19, 4) NOT NULL,
    matched_at        TIMESTAMPTZ NOT NULL,
    matched_by        UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_lm_status CHECK (status IN ('PARTIAL','FULL','DELETED'))
);

CREATE INDEX IF NOT EXISTS idx_lm_company_third_party ON lettrage_match (company_id, third_party_id);
