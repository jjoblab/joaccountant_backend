-- V19_009 — corporate tax rule table
-- =====================================================================
-- V65 — R-F-validation (lot-G) — Table corporate_tax_rule + seeds
-- =====================================================================
-- Découlé de la validation PME4 Caribbean Textiles :
-- - L'entité CorporateTaxRule existe côté Java (V40) mais la table DB n'avait
-- jamais été créée → endpoint /corporate-tax/projection cassé à l'exécution.
-- - Sans table, impossible de configurer IS 15% zone franche via API.
-- Cette migration (placée dans :tax) crée la table + seed 5 CorporateTaxRule
-- globales par pays (HT 30% / HT 15% zone franche / FR 25%+15% PME / CA 25% /
-- HT ONG exonérée).
-- =====================================================================


CREATE TABLE IF NOT EXISTS corporate_tax_rule (
    id                          UUID            NOT NULL PRIMARY KEY,
    company_id                  UUID,           -- NULL = règle globale par pays
    country_code                VARCHAR(2)      NOT NULL,
    standard_rate               NUMERIC(5, 2)   NOT NULL,
    reduced_rate                NUMERIC(5, 2),
    reduced_rate_threshold      NUMERIC(19, 4),
    is_free_zone_rate           BOOLEAN         NOT NULL DEFAULT FALSE,
    eligibility                 VARCHAR(20)     NOT NULL DEFAULT 'UNKNOWN',
    active                      BOOLEAN         NOT NULL DEFAULT TRUE,
    applicable_from             DATE,
    applicable_to               DATE,
    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uc_corporate_tax_rule UNIQUE (company_id, country_code, is_free_zone_rate, active),
    CONSTRAINT chk_corporate_tax_rule_eligibility CHECK (
        eligibility IN ('SME', 'LARGE', 'UNKNOWN', 'NGO_EXEMPT')
    ),
    CONSTRAINT chk_corporate_tax_rule_rate CHECK (
        standard_rate >= 0 AND (reduced_rate IS NULL OR reduced_rate >= 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_corporate_tax_rule_company_active
    ON corporate_tax_rule (company_id, country_code, active);

CREATE INDEX IF NOT EXISTS idx_corporate_tax_rule_country_active
    ON corporate_tax_rule (country_code, active)
    WHERE company_id IS NULL;

COMMENT ON TABLE corporate_tax_rule IS
    'V65 — R-F-validation : règle IS par pays. company_id NULL = règle globale.';

-- Seeds CorporateTaxRule globales
INSERT INTO corporate_tax_rule (id, company_id, country_code, standard_rate, reduced_rate,
                                 reduced_rate_threshold, is_free_zone_rate, eligibility, active, version)
VALUES
    -- Haïti standard : 30% (Code Fiscal art. 4)
    (uuidv7(), NULL, 'HT', 30.00, NULL, NULL, FALSE, 'UNKNOWN', TRUE, 0),

    -- Haïti zone franche : 15% (Code Fiscal art. 195, agrément CODEVI/SONAPI)
    (uuidv7(), NULL, 'HT', 15.00, NULL, NULL, TRUE, 'UNKNOWN', TRUE, 0),

    -- France standard : 25% + 15% PME < 42 500 € (CGI art. 219)
    (uuidv7(), NULL, 'FR', 25.00, 15.00, 42500.00, FALSE, 'SME', TRUE, 0),

    -- Canada : 25% (général)
    (uuidv7(), NULL, 'CA', 25.00, NULL, NULL, FALSE, 'UNKNOWN', TRUE, 0),

    -- ONG Haïti : exonérée IS (Code Fiscal art. 195, sous conditions agrément)
    (uuidv7(), NULL, 'HT', 0.00, NULL, NULL, FALSE, 'NGO_EXEMPT', TRUE, 0)
ON CONFLICT DO NOTHING;
