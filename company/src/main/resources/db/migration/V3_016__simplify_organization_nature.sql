-- V3_016 — simplify organization nature
-- V90 — V2.6.0 (wizard refonte) : Simplification du domaine organization_nature à 2 valeurs.
-- CONTEXTE : OrganizationNature enum initialement définie avec 4 valeurs
-- (FOR_PROFIT, NON_PROFIT, PUBLIC_SECTOR, COOPERATIVE). L'audit `wizard-audit` a montré que
-- (a) aucun seed business_type n'utilise PUBLIC_SECTOR ou COOPERATIVE, (b) le wizard refonte
-- ne propose que 2 choix à l'utilisateur (« à but lucratif » / « non lucratif »).
-- Cette migration :
-- 1. Re-saupoudre les companies existantes avec organization_nature = PUBLIC_SECTOR ou
-- COOPERATIVE vers FOR_PROFIT (safe default — aucune company en production n'utilise
-- ces valeurs, mais on reste défensif).
-- 2. Re-saupoudre les business_type existants avec default_organization_nature = PUBLIC_SECTOR
-- ou COOPERATIVE vers FOR_PROFIT (même raisonnement).
-- 3. Drop + recreate la contrainte CHECK sur companies.organization_nature pour n'accepter
-- que FOR_PROFIT et NON_PROFIT.
-- 4. Drop + recreate la contrainte CHECK sur business_type.default_organization_nature pour
-- n'accepter que FOR_PROFIT et NON_PROFIT.
-- Note : les CHECK constraints existantes ont été posées par V3_004 (companies) et V3_003
-- (business_type). Leurs noms sont `chk_companies_organization_nature` et
-- `chk_business_type_nature` respectivement.

-- 1. Backfill companies — toute valeur hors nouveau domaine → FOR_PROFIT (safe default).


UPDATE companies
SET organization_nature = 'FOR_PROFIT',
    updated_at = NOW()
WHERE organization_nature IN ('PUBLIC_SECTOR', 'COOPERATIVE');

-- 2. Backfill business_type — idem (données de référence globales).
UPDATE business_type
SET default_organization_nature = 'FOR_PROFIT',
    updated_at = NOW()
WHERE default_organization_nature IN ('PUBLIC_SECTOR', 'COOPERATIVE');

-- 3. Remplacer la contrainte CHECK sur companies.organization_nature.
ALTER TABLE companies DROP CONSTRAINT IF EXISTS chk_companies_organization_nature;
ALTER TABLE companies
    ADD CONSTRAINT chk_companies_organization_nature
    CHECK (organization_nature IN ('FOR_PROFIT', 'NON_PROFIT'));

-- 4. Remplacer la contrainte CHECK sur business_type.default_organization_nature.
ALTER TABLE business_type DROP CONSTRAINT IF EXISTS chk_business_type_nature;
ALTER TABLE business_type
    ADD CONSTRAINT chk_business_type_nature
    CHECK (default_organization_nature IN ('FOR_PROFIT', 'NON_PROFIT'));

COMMENT ON COLUMN companies.organization_nature IS
    'V2.6.0 — Simplifié à 2 valeurs : FOR_PROFIT (à but lucratif) / NON_PROFIT (non lucratif).';
COMMENT ON COLUMN business_type.default_organization_nature IS
    'V2.6.0 — Simplifié à 2 valeurs : FOR_PROFIT / NON_PROFIT.';
