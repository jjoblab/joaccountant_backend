-- V28_001 — demo data module
-- V83 — V8.1 Module Démos : flag is_demo sur companies + table demo_seed_history.
-- 4 entreprises fictives haïtiennes :
-- - BOUTIK_LAKAY (retail Pétion-Ville, 4 employés, ~6M HTG/an, HTG, PCN_HAITI, IS 30%)
-- - MOISE_ASSOCIES (services pro PAP, 8 consultants, ~18M HTG/an, HTG, PCN_HAITI, IS 30%)
-- - ESPWA_POU_AYITI (ONG humanitaire PAP, 35 employés, ~60M HTG/an, USD, PCN_HAITI, IS 0% NGO_EXEMPT)
-- - CARIBBEAN_TEXTILES (zone franche CODEVI Ouanaminthe, 1200 employés, ~144M HTG/an, USD, IFRS_FULL, IS 15% ZF)
-- Sur 2 exercices fiscaux (exercice haïtien 01/10 → 30/09) :
-- - FY2024-2025 : 01/10/2024 → 30/09/2025
-- - FY2025-2026 : 01/10/2025 → 30/09/2026


ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN companies.is_demo IS
    'V83 — V8.1 Module Démos : TRUE si entreprise fictive (mode démo, lecture seule publique).';

CREATE INDEX IF NOT EXISTS idx_companies_is_demo
    ON companies (is_demo)
    WHERE is_demo = TRUE;

CREATE TABLE IF NOT EXISTS demo_seed_history (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    demo_code       VARCHAR(50)     NOT NULL,
    fiscal_year     VARCHAR(20)     NOT NULL,
    seeded_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    seeded_by       UUID,
    records_count   INT             NOT NULL DEFAULT 0,
    duration_ms     BIGINT,
    status          VARCHAR(20)     NOT NULL DEFAULT 'COMPLETED',
    CONSTRAINT uc_demo_seed_history UNIQUE (demo_code, fiscal_year),
    CONSTRAINT chk_demo_seed_status CHECK (status IN ('COMPLETED', 'FAILED', 'IN_PROGRESS'))
);

COMMENT ON TABLE demo_seed_history IS
    'V83 — V8.1 Historique des seeds démo (idempotence — un seed par démo + exercice fiscal).';
