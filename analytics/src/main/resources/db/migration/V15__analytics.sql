-- V7_001 — analytics (module :analytics, §5 + §13 Phase 5).
--
-- Deux tables :
--  - analytical_dimension_plan : un axe d'analyse par entreprise (Fonds/Projets pour ONG,
--    Projets clients pour Service, Points de vente pour Commerce)
--  - analytical_dimension_value : valeurs au sein d'un plan, hiérarchie parent/enfant optionnelle

CREATE TABLE IF NOT EXISTS analytical_dimension_plan (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id  UUID        NOT NULL,
    code        VARCHAR(20) NOT NULL,
    label       VARCHAR(100) NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_adp_company_code UNIQUE (company_id, code)
);

CREATE INDEX IF NOT EXISTS idx_adp_company ON analytical_dimension_plan (company_id);

CREATE TABLE IF NOT EXISTS analytical_dimension_value (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id  UUID        NOT NULL,
    plan_id     UUID        NOT NULL REFERENCES analytical_dimension_plan(id) ON DELETE CASCADE,
    parent_id   UUID,
    code        VARCHAR(30) NOT NULL,
    label       VARCHAR(200) NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_adv_plan_code UNIQUE (plan_id, code)
);

CREATE INDEX IF NOT EXISTS idx_adv_plan ON analytical_dimension_value (plan_id);
CREATE INDEX IF NOT EXISTS idx_adv_company ON analytical_dimension_value (company_id);
CREATE INDEX IF NOT EXISTS idx_adv_parent ON analytical_dimension_value (parent_id);
