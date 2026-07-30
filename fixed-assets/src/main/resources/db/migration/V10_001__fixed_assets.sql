-- V10_001 — fixed-assets (module :fixed-assets, §13 Phase 8).
--
-- Tables : asset, depreciation_schedule_line.

CREATE TABLE IF NOT EXISTS asset (
    id                                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                          UUID        NOT NULL,
    label                               VARCHAR(200) NOT NULL,
    acquisition_date                    DATE        NOT NULL,
    acquisition_cost                    NUMERIC(19, 4) NOT NULL,
    useful_life_months                  INT         NOT NULL,
    residual_value                      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    depreciation_method                 VARCHAR(25) NOT NULL DEFAULT 'STRAIGHT_LINE',
    asset_account_id                    UUID        NOT NULL,
    depreciation_expense_account_id     UUID        NOT NULL,
    accumulated_depreciation_account_id  UUID        NOT NULL,
    status                              VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    disposal_date                       DATE,
    disposal_amount                     NUMERIC(19, 4),
    gain_or_loss                        NUMERIC(19, 4),
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                          UUID,
    updated_by                          UUID,
    version                             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_asset_status CHECK (status IN ('ACTIVE','DISPOSED')),
    CONSTRAINT chk_asset_method CHECK (depreciation_method IN ('STRAIGHT_LINE','DECLINING_BALANCE')),
    CONSTRAINT chk_asset_useful_life CHECK (useful_life_months >= 1),
    CONSTRAINT chk_asset_residual CHECK (residual_value >= 0 AND residual_value <= acquisition_cost),
    CONSTRAINT chk_asset_cost CHECK (acquisition_cost > 0),
    -- Si status = DISPOSED, disposal_date et disposal_amount sont requis
    CONSTRAINT chk_asset_disposal CHECK (
        (status = 'ACTIVE') OR (disposal_date IS NOT NULL AND disposal_amount IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_asset_company ON asset (company_id);
CREATE INDEX IF NOT EXISTS idx_asset_company_status ON asset (company_id, status);

CREATE TABLE IF NOT EXISTS depreciation_schedule_line (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    asset_id            UUID        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    period_id           UUID,
    period_date         DATE        NOT NULL,
    amount              NUMERIC(19, 4) NOT NULL,
    cumulative_amount   NUMERIC(19, 4) NOT NULL,
    journal_entry_id    UUID,
    posted_at           TIMESTAMPTZ,
    posted_by           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_dsl_asset_period UNIQUE (asset_id, period_id),
    CONSTRAINT chk_dsl_amount CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_dsl_asset ON depreciation_schedule_line (asset_id);
CREATE INDEX IF NOT EXISTS idx_dsl_company ON depreciation_schedule_line (company_id);
CREATE INDEX IF NOT EXISTS idx_dsl_posted ON depreciation_schedule_line (asset_id, journal_entry_id) WHERE journal_entry_id IS NOT NULL;
