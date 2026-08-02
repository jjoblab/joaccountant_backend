-- V2_002 — user_company_role join table (§3.4: per-company role).
-- Not a TenantAwareEntity row: must be queryable by user_id alone (list my companies).
-- FK to companies(id) is added in V3_003 (companies table is created by V3_001).

CREATE TABLE IF NOT EXISTS user_company_role (
    id            UUID        PRIMARY KEY DEFAULT uuidv7(),
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_id    UUID        NOT NULL,
    role          VARCHAR(20) NOT NULL,
    invited_at    TIMESTAMPTZ NOT NULL,
    accepted_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_user_company UNIQUE (user_id, company_id),
    CONSTRAINT chk_user_company_role CHECK (role IN ('OWNER','ADMIN','ACCOUNTANT','BOOKKEEPER','VIEWER','AUDITOR'))
);

CREATE INDEX IF NOT EXISTS idx_ucr_user_id    ON user_company_role (user_id);
CREATE INDEX IF NOT EXISTS idx_ucr_company_id ON user_company_role (company_id);
