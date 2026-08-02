-- V5_001 — chart-of-accounts (module :chart-of-accounts, §4 + §13 Phase 3).
-- Deux tables :
-- - account : plan comptable hiérarchique (4 niveaux max), un compte par (company_id, code).
-- - account_numbering_template : gabarit de numérotation pour référentiels FREE (IFRS) —
-- relation 1-1 avec companies. Ignoré pour MANDATED.


CREATE TABLE IF NOT EXISTS account (
    id                                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                          UUID        NOT NULL,
    parent_id                           UUID,
    code                                VARCHAR(30) NOT NULL,
    label                               VARCHAR(200) NOT NULL,
    level                               INT         NOT NULL,
    reporting_class                     VARCHAR(25) NOT NULL,
    reporting_subcategory               VARCHAR(15),
    normal_balance                      VARCHAR(10) NOT NULL,
    locked                              BOOLEAN     NOT NULL DEFAULT FALSE,
    active                              BOOLEAN     NOT NULL DEFAULT TRUE,
    is_collective                       BOOLEAN     NOT NULL DEFAULT FALSE,
    path                                VARCHAR(200) NOT NULL,
    tax_mapping_code                    VARCHAR(30),
    requires_analytical_tag_plan_ids    JSONB,
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                          UUID,
    updated_by                          UUID,
    version                             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_account_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_account_level        CHECK (level BETWEEN 1 AND 4),
    CONSTRAINT chk_account_reporting_class CHECK (reporting_class IN ('ACTIF','PASSIF','CAPITAUX_PROPRES','PRODUITS','CHARGES')),
    CONSTRAINT chk_account_subcategory CHECK (reporting_subcategory IS NULL OR reporting_subcategory IN ('COURANT','NON_COURANT','N_A')),
    CONSTRAINT chk_account_normal_balance CHECK (normal_balance IN ('DEBIT','CREDIT'))
);

CREATE INDEX IF NOT EXISTS idx_account_company_code ON account (company_id, code);
CREATE INDEX IF NOT EXISTS idx_account_company_parent ON account (company_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_account_company_level ON account (company_id, level);
CREATE INDEX IF NOT EXISTS idx_account_path ON account (company_id, path);

CREATE TABLE IF NOT EXISTS account_numbering_template (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID        NOT NULL,
    accounting_framework_id     UUID        NOT NULL,
    code_length_level_1         INT         NOT NULL DEFAULT 1,
    code_length_level_2         INT         NOT NULL DEFAULT 2,
    code_length_level_3         INT         NOT NULL DEFAULT 3,
    code_length_level_4         INT         NOT NULL DEFAULT 6,
    spacing_step                INT         NOT NULL DEFAULT 3,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_account_numbering_template_company UNIQUE (company_id),
    CONSTRAINT chk_ant_code_lengths CHECK (
        code_length_level_1 BETWEEN 1 AND 10
        AND code_length_level_2 BETWEEN code_length_level_1 AND 10
        AND code_length_level_3 BETWEEN code_length_level_2 AND 10
        AND code_length_level_4 BETWEEN code_length_level_3 AND 20
    )
);
