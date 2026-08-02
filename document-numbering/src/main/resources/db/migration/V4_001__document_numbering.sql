-- V4_001 — document-numbering (module :document-numbering, §6).
-- Deux tables :
-- - document_sequence_config : configuration d'une séquence (prefix, padding, resetPolicy...)
-- Une ligne par (company_id, document_type, scope_key). TenantAwareEntity.
-- - document_sequence_counter : compteur d'émission. Une ligne active par (config_id, period_key).
-- Verrou pessimiste SELECT ... FOR UPDATE à chaque émission (atomicité, §6).


CREATE TABLE IF NOT EXISTS document_sequence_config (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    document_type   VARCHAR(30) NOT NULL,
    scope_key       VARCHAR(30) NOT NULL DEFAULT '',
    prefix          VARCHAR(20) NOT NULL,
    include_year    BOOLEAN     NOT NULL DEFAULT TRUE,
    padding         INT         NOT NULL DEFAULT 6,
    reset_policy    VARCHAR(10) NOT NULL DEFAULT 'YEARLY',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_doc_seq_config UNIQUE (company_id, document_type, scope_key),
    CONSTRAINT chk_doc_seq_doc_type CHECK (document_type IN ('JOURNAL_ENTRY','SALES_INVOICE','CREDIT_NOTE','DONATION_RECEIPT')),
    CONSTRAINT chk_doc_seq_reset_policy CHECK (reset_policy IN ('NEVER','YEARLY','MONTHLY')),
    CONSTRAINT chk_doc_seq_padding CHECK (padding BETWEEN 1 AND 12)
);

CREATE INDEX IF NOT EXISTS idx_doc_seq_config_company ON document_sequence_config (company_id);

CREATE TABLE IF NOT EXISTS document_sequence_counter (
    id                   UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id           UUID        NOT NULL,
    sequence_config_id   UUID        NOT NULL REFERENCES document_sequence_config(id) ON DELETE CASCADE,
    period_key           VARCHAR(10) NOT NULL DEFAULT '',
    last_value           BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    updated_by           UUID,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_doc_seq_counter UNIQUE (sequence_config_id, period_key),
    -- Le last_value ne peut pas être négatif. La première émission d'une période pose last_value=1.
    CONSTRAINT chk_doc_seq_last_value CHECK (last_value >= 0)
);

CREATE INDEX IF NOT EXISTS idx_doc_seq_counter_config_period ON document_sequence_counter (sequence_config_id, period_key);
CREATE INDEX IF NOT EXISTS idx_doc_seq_counter_company ON document_sequence_counter (company_id);
