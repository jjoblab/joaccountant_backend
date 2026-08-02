-- V1_001 — audit_log table (module :audit-trail, §3.6).
-- Note: audit_log is the ONE entity in the project that is NOT a TenantAwareEntity. Its rows
-- must survive tenant deletion, so company_id is a plain nullable column, not a discriminator.

CREATE TABLE IF NOT EXISTS audit_log (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID,
    actor_user_id   UUID,
    entity_type     VARCHAR(120) NOT NULL,
    entity_id       UUID,
    action          VARCHAR(60) NOT NULL,
    old_value_json  JSONB,
    new_value_json  JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL,
    correlation_id  VARCHAR(80),
    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_audit_log_company_entity ON audit_log (company_id, entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_occurred_at    ON audit_log (occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation_id ON audit_log (correlation_id);
