-- V3_005 — Backfill des lignes existantes de companies + élargissement du CHECK sur sector.
-- 1. Renommage des anciennes valeurs sector (ONG → ONG_HUMANITAIRE, MIXTE → AUTRE) ;
-- 2. Mapping ancien sector → business_type_code par défaut ;
-- 3. Déduction de organization_nature à partir de legal_form existant ;
-- 4. Remplissage de primary_activity_label (vide avant ce script) ;
-- 5. Élargissement de la contrainte CHECK sur sector ;
-- 6. Verrouillage : business_type_code NOT NULL.
-- Restructuration de la modélisation organisationnelle (prompt 2026-07-24, §4.4).
-- IMPORTANT : l'ordre des opérations est critique — le renommage des valeurs sector doit
-- précéder le drop/recreate du CHECK sur sector, sinon PostgreSQL refuse l'ALTER.

-- 1. Renommage des anciennes valeurs sector vers les nouvelles.
-- Les valeurs COMMERCE et SERVICE restent valides dans le nouveau domaine.
-- Les anciennes valeurs ONG et MIXTE sont retirées du nouveau domaine et doivent
-- être migrées vers ONG_HUMANITAIRE et AUTRE respectivement.


UPDATE companies SET sector = 'ONG_HUMANITAIRE' WHERE sector = 'ONG';
UPDATE companies SET sector = 'AUTRE'           WHERE sector = 'MIXTE';

-- 2. Backfill business_type_code depuis l'ancien sector (avant renommage ci-dessus, mais
--    on se base désormais sur les nouvelles valeurs sector pour choisir le type métier).

UPDATE companies
SET business_type_code = CASE
        WHEN business_type_code IS NOT NULL THEN business_type_code
        WHEN sector = 'COMMERCE'         THEN 'RETAIL_COMMERCE'
        WHEN sector = 'SERVICE'           THEN 'PROFESSIONAL_SERVICES'
        WHEN sector = 'ONG_HUMANITAIRE'  THEN 'NGO_HUMANITARIAN'
        WHEN sector = 'CABINET_COMPTABLE' THEN 'ACCOUNTING_FIRM'
        WHEN sector = 'EDUCATION'         THEN 'SCHOOL'
        WHEN sector = 'SANTE'             THEN 'HOSPITAL'
        ELSE 'CUSTOM'
    END
WHERE business_type_code IS NULL;

-- 3. Backfill organization_nature depuis legal_form (uniquement si nature encore à la valeur
--    par défaut FOR_PROFIT). IMPORTANT : ne pas écraser une nature déjà positionnée
--    explicitement par un write post-migration.

UPDATE companies
SET organization_nature = 'NON_PROFIT'
WHERE organization_nature = 'FOR_PROFIT'
  AND legal_form IN ('NGO', 'ASSOCIATION');

-- 4. Backfill primary_activity_label — laisse vide si non renseigné, mais la colonne reste NOT NULL.

UPDATE companies
SET primary_activity_label = ''
WHERE primary_activity_label IS NULL OR primary_activity_label = '';

-- 5. Élargissement de la contrainte CHECK sur sector : on retire l'ancienne (4 valeurs,
--    avec ONG et MIXTE) et on en pose une nouvelle (10 valeurs, sans ONG ni MIXTE).
--    L'UPDATE du point 1 garantit qu'aucune ligne n'a désormais une valeur hors domaine.

ALTER TABLE companies DROP CONSTRAINT IF EXISTS chk_companies_sector;
ALTER TABLE companies
    ADD CONSTRAINT chk_companies_sector CHECK (sector IN
        ('COMMERCE','SERVICE','SANTE','EDUCATION','AGRICULTURE','INDUSTRIE',
         'ADMINISTRATION_PUBLIQUE','ONG_HUMANITAIRE','CABINET_COMPTABLE','AUTRE'));

-- 6. Verrouillage : business_type_code devient NOT NULL une fois toutes les lignes backfillées.

ALTER TABLE companies ALTER COLUMN business_type_code SET NOT NULL;

-- 7. Index de recherche par business_type_code (utile pour les agrégations statistiques).

CREATE INDEX IF NOT EXISTS idx_companies_business_type_code ON companies (business_type_code);
