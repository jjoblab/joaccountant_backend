-- V3_013 — company tax exemption status
-- =====================================================================
-- V80 — v8-1 : tax_exemption_status sur companies
-- =====================================================================
-- Ajoute une colonne tax_exemption_status sur companies pour router
-- l'entreprise vers la bonne CorporateTaxRule :
-- - STANDARD : IS 30% Haïti / 25% France (comportement par défaut)
-- - FREE_ZONE : IS 15% zone franche (Code Fiscal art. 195 — CODEVI/SONAPI)
-- - NGO_EXEMPT: IS 0% ONG exonérée (Code Fiscal art. 195 — agrément DGI)
-- Complète la colonne is_free_zone (V66) qui ne distinguait que 2 cas
-- (standard / zone franche) sans permettre l'exonération totale ONG.
-- NOTE DE CONFLIT : le module :demo-data prévoit une migration V80 — pour
-- éviter tout conflit Flyway (duplicate version), le module :demo-data
-- doit renuméroter sa migration V80 → V82 (cf. V79 doc).
-- =====================================================================


ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS tax_exemption_status VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_tax_exemption_status;
ALTER TABLE companies
    ADD CONSTRAINT chk_companies_tax_exemption_status CHECK (
        tax_exemption_status IN ('STANDARD', 'FREE_ZONE', 'NGO_EXEMPT')
    );

-- Backfill heuristique : si is_free_zone=TRUE → FREE_ZONE
UPDATE companies
SET tax_exemption_status = 'FREE_ZONE'
WHERE is_free_zone = TRUE
  AND tax_exemption_status = 'STANDARD';

-- Backfill heuristique : ONG / association NON_PROFIT en Haïti → NGO_EXEMPT
-- (l'utilisateur peut reclasser manuellement via l'API legal-fields)
UPDATE companies
SET tax_exemption_status = 'NGO_EXEMPT'
WHERE country = 'HT'
  AND tax_exemption_status = 'STANDARD'
  AND (legal_form IN ('NGO', 'ASSOCIATION')
       OR organization_nature = 'NON_PROFIT');

COMMENT ON COLUMN companies.tax_exemption_status IS
    'V80 — v8-1 : STANDARD (IS 30% Haïti / 25% France), FREE_ZONE (IS 15% art. 195), NGO_EXEMPT (IS 0% art. 195).';
