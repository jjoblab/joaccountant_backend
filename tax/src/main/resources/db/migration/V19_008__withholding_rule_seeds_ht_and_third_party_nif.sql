-- V19_008 — withholding rule seeds ht and third party nif
-- =====================================================================
-- V64 — R-F-validation (lot-G) — Seeds RS Haïti + nif sur ThirdParty
-- =====================================================================
-- Découlé des validations PME/expert-comptable (29 juillet 2026) :
-- - Expert-comptable Maître Pierre-Louis : aucun seed RS 2% Haïti (P0)
-- - PME2 Moïse & Associés : RS 2% sur mes ventes + 30% non-résidents (BLOQUANT)
-- - PME1 Boutik Lakay : NIF client non saisissable (BLOQUANT)
-- Cette migration :
-- 1. Rend withholding_rule.company_id NULLable (autorise seeds globaux par pays)
-- 2. Ajoute country_code sur withholding_rule (pour filtrer par pays)
-- 3. Seeds 3 WithholdingRule globales Haïti (RS 2% / RS 10% royalties / RS 30% non-résidents)
-- 4. Ajoute la colonne nif sur third_party (si pas déjà présente)
-- =====================================================================

-- ───────────────────────────────────────────────────────────────────────
-- 1. withholding_rule : company_id NULLable + country_code
-- ───────────────────────────────────────────────────────────────────────


ALTER TABLE withholding_rule ALTER COLUMN company_id DROP NOT NULL;

ALTER TABLE withholding_rule
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);

COMMENT ON COLUMN withholding_rule.country_code IS
    'V64 — R-F-validation : code pays ISO 2 lettres pour les règles globales (company_id NULL). Ex : HT, FR, CA.';

-- Backfill country_code pour les règles existantes (NULL = applicable tous pays)
UPDATE withholding_rule SET country_code = NULL WHERE country_code IS NULL;

-- Index pour la recherche de règles globales par pays
CREATE INDEX IF NOT EXISTS idx_wh_rule_country_active
    ON withholding_rule (country_code, active)
    WHERE company_id IS NULL;

-- ───────────────────────────────────────────────────────────────────────
-- 2. Seeds WithholdingRule globales Haïti (Code Fiscal art. 156)
-- ───────────────────────────────────────────────────────────────────────
INSERT INTO withholding_rule (id, company_id, country_code, code, label, rate,
                                applicable_third_party_types, active, version)
VALUES
    -- RS 2% sur prestations locales (Code Fiscal art. 156-1)
    -- Applicable aux SUPPLIER (factures fournisseurs de prestations de services)
    (uuidv7(), NULL, 'HT', 'RS_HT_PRESTATIONS_LOCAL',
     'Retenue à la Source Haïti — 2% sur prestations locales (Code Fiscal art. 156-1)',
     2.00,
     '["SUPPLIER"]'::jsonb,
     TRUE, 0),

    -- RS 10% sur royalties (Code Fiscal art. 156-2)
    -- Applicable aux SUPPLIER (redevances, licences, droits d'auteur)
    (uuidv7(), NULL, 'HT', 'RS_HT_ROYALTIES',
     'Retenue à la Source Haïti — 10% sur royalties/redevances (Code Fiscal art. 156-2)',
     10.00,
     '["SUPPLIER"]'::jsonb,
     TRUE, 0),

    -- RS 30% sur services non-résidents (Code Fiscal art. 156-3)
    -- Applicable aux SUPPLIER dont le pays ≠ HT
    (uuidv7(), NULL, 'HT', 'RS_HT_NON_RESIDENT_SERVICES',
     'Retenue à la Source Haïti — 30% sur services de non-résidents (Code Fiscal art. 156-3)',
     30.00,
     '["SUPPLIER"]'::jsonb,
     TRUE, 0),

    -- RS 10% sur loyers (Code Fiscal art. 156-4)
    -- Applicable aux SUPPLIER (bailleurs de biens immobiliers)
    (uuidv7(), NULL, 'HT', 'RS_HT_RENT',
     'Retenue à la Source Haïti — 10% sur loyers (Code Fiscal art. 156-4)',
     10.00,
     '["SUPPLIER"]'::jsonb,
     TRUE, 0)
ON CONFLICT DO NOTHING;

-- ───────────────────────────────────────────────────────────────────────
-- 3. Colonne nif sur third_party (si pas déjà présente)
-- ───────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'third_party' AND column_name = 'nif'
    ) THEN
        ALTER TABLE third_party ADD COLUMN nif VARCHAR(30);
        COMMENT ON COLUMN third_party.nif IS
            'V64 — R-F-validation : NIF (Numéro Identification Fiscale) du tiers. Format Haïti : 10 chiffres + 2 lettres (^[0-9]{10}[A-Z]{2}$).';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_third_party_company_nif
    ON third_party (company_id, nif)
    WHERE nif IS NOT NULL;
