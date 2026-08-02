-- V40 — Audit v4.7 §4.1 Finding #3 — Table contribution_rule pour le moteur de paie par tranches.
--
-- La v4.7 utilisait un calcul simpliste (gross × rate / 100) sans notion de plafond (PMSS),
-- d'abattement (CSG sur 98.25% du brut), ni de tranche (Tranche A < PMSS, Tranche B > PMSS).
-- Cette migration crée la table qui supporte le nouveau moteur PayrollCalculator.

CREATE TABLE IF NOT EXISTS contribution_rule (
    id                  UUID            NOT NULL PRIMARY KEY,
    company_id          UUID            NOT NULL,
    code                VARCHAR(30)     NOT NULL,
    label               VARCHAR(200)    NOT NULL,
    regime              VARCHAR(30)     NOT NULL,
    contribution_type   VARCHAR(20)     NOT NULL,
    rate                NUMERIC(6, 4)   NOT NULL,
    base_type           VARCHAR(30)     NOT NULL,
    abatement_rate      NUMERIC(6, 4)   DEFAULT 100.0000,
    monthly_ceiling     NUMERIC(19, 4),
    ceiling_multiplier  NUMERIC(5, 2),
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    tax_mapping_code    VARCHAR(50),
    version             BIGINT          NOT NULL DEFAULT 0,

    -- Une entreprise ne peut pas avoir 2 règles actives avec le même code
    CONSTRAINT uk_contribution_rule_company_code UNIQUE (company_id, code),
    -- Validation : regime dans l'énumération
    CONSTRAINT chk_contribution_rule_regime CHECK (
        regime IN ('FR_GENERAL', 'FR_CADRE', 'FR_NON_CADRE', 'HT_GENERAL', 'CUSTOM')
    ),
    -- Validation : contribution_type dans l'énumération
    CONSTRAINT chk_contribution_rule_type CHECK (
        contribution_type IN ('EMPLOYEE', 'EMPLOYER', 'EMPLOYEE_AND_EMPLOYER')
    ),
    -- Validation : base_type dans l'énumération
    CONSTRAINT chk_contribution_rule_base CHECK (
        base_type IN ('GROSS', 'GROSS_ABATED', 'CAPPED_GROSS', 'CAPPED_GROSS_ABATED', 'TRANCHE_B')
    ),
    -- Validation : rate >= 0 (un taux négatif n'a pas de sens pour une cotisation)
    CONSTRAINT chk_contribution_rule_rate CHECK (rate >= 0),
    -- Validation : abatement_rate entre 0 et 100
    CONSTRAINT chk_contribution_rule_abatement CHECK (abatement_rate >= 0 AND abatement_rate <= 100)
);

-- Index pour findByCompanyIdAndActiveTrue (cache Caffeine TTL 10 min)
CREATE INDEX IF NOT EXISTS idx_contribution_rule_company_active
    ON contribution_rule (company_id, active);

-- Index pour findByCompanyIdAndRegimeAndActiveTrue
CREATE INDEX IF NOT EXISTS idx_contribution_rule_company_regime_active
    ON contribution_rule (company_id, regime, active);

COMMENT ON TABLE contribution_rule IS
    'V40 — Audit v4.7 §4.1 #3 — Règles de cotisation sociale pour PayrollCalculator (PMSS, tranches, abattement CSG).';
COMMENT ON COLUMN contribution_rule.monthly_ceiling IS
    'Plafond mensuel (PMSS France 2024 = 3864 EUR). Si NULL, pas de plafond.';
COMMENT ON COLUMN contribution_rule.ceiling_multiplier IS
    'Multiplicateur du plafond pour Tranche B (ex: 4 = 4xPMSS).';
COMMENT ON COLUMN contribution_rule.abatement_rate IS
    'Taux dabattement de lassiette (ex: 98.25 pour CSG/CRDS). 100 si pas dabattement.';
