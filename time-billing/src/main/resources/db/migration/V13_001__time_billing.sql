-- V13_001 — time-billing (module :time-billing, §13 Phase 10).


CREATE TABLE IF NOT EXISTS tb_project (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    client_third_party_id   UUID,
    code                    VARCHAR(30) NOT NULL,
    label                   VARCHAR(200) NOT NULL,
    status                  VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    billing_type            VARCHAR(25) NOT NULL DEFAULT 'TIME_AND_MATERIALS',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_tb_project_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_tb_project_status CHECK (status IN ('ACTIVE','CLOSED')),
    CONSTRAINT chk_tb_project_billing_type CHECK (billing_type IN ('FIXED_FEE','TIME_AND_MATERIALS'))
);

CREATE INDEX IF NOT EXISTS idx_tb_project_company ON tb_project (company_id);

CREATE TABLE IF NOT EXISTS tb_billable_rate (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    project_id          UUID,
    resource_user_id    UUID,
    hourly_rate         NUMERIC(19, 4) NOT NULL,
    currency            CHAR(3)     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_tb_rate_positive CHECK (hourly_rate > 0)
);

CREATE INDEX IF NOT EXISTS idx_tb_rate_company ON tb_billable_rate (company_id);
CREATE INDEX IF NOT EXISTS idx_tb_rate_project ON tb_billable_rate (project_id);
CREATE INDEX IF NOT EXISTS idx_tb_rate_resource ON tb_billable_rate (resource_user_id);

CREATE TABLE IF NOT EXISTS tb_timesheet_entry (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    project_id          UUID        NOT NULL REFERENCES tb_project(id) ON DELETE CASCADE,
    resource_user_id    UUID        NOT NULL,
    entry_date          DATE        NOT NULL,
    hours               NUMERIC(5, 2) NOT NULL,
    billable            BOOLEAN     NOT NULL DEFAULT TRUE,
    approved            BOOLEAN     NOT NULL DEFAULT FALSE,
    description         VARCHAR(500),
    invoiced            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_tb_entry_hours CHECK (hours > 0)
);

CREATE INDEX IF NOT EXISTS idx_tb_entry_project ON tb_timesheet_entry (project_id);
CREATE INDEX IF NOT EXISTS idx_tb_entry_company ON tb_timesheet_entry (company_id);
CREATE INDEX IF NOT EXISTS idx_tb_entry_resource ON tb_timesheet_entry (resource_user_id);
CREATE INDEX IF NOT EXISTS idx_tb_entry_unbilled ON tb_timesheet_entry (project_id)
    WHERE approved = TRUE AND billable = TRUE AND invoiced = FALSE;
