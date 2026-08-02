-- V3_002 — company_module table (§11: tracks which modules are enabled per company).
-- IS a TenantAwareEntity — always queried from within a tenant context.


CREATE TABLE IF NOT EXISTS company_module (
    id            UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id    UUID        NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    module_code   VARCHAR(40) NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    activated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_company_module UNIQUE (company_id, module_code)
);

CREATE INDEX IF NOT EXISTS idx_company_module_company_id ON company_module (company_id);

-- Now that companies exists, add the deferred FK on user_company_role (V2_002 could not add it).
ALTER TABLE user_company_role
    ADD CONSTRAINT fk_ucr_company_id FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE;
