-- =====================================================================
-- V66 — R-F-validation (lot-G) — Flag is_free_zone sur companies
-- =====================================================================
-- Découlé de la validation PME4 Caribbean Textiles (zone franche) :
--   - IS réduit 15% en zone franche (Code Fiscal art. 195)
--   - Nécessite un flag au niveau Company pour router vers la bonne CorporateTaxRule
-- =====================================================================

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS is_free_zone BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN companies.is_free_zone IS
    'V66 — R-F-validation : TRUE si société agréée zone franche (CODEVI, SONAPI). IS réduit 15% au lieu de 30% (Code Fiscal art. 195).';

-- Backfill heuristique : si country=HT et name contient "ZONE FRANCHE" ou "CODEVI"
UPDATE companies
SET is_free_zone = TRUE
WHERE country = 'HT'
  AND (LOWER(name) LIKE '%zone franche%'
    OR LOWER(name) LIKE '%codevi%'
    OR LOWER(name) LIKE '%sonapi%');
