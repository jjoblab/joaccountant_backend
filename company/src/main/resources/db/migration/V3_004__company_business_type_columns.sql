-- V3_004 — Ajout des colonnes organization_nature / business_type_code / primary_activity_label
-- + extra_attributes (JSONB) sur companies.
-- Restructuration de la modélisation organisationnelle (prompt 2026-07-24, §4.4).
-- NOTE : la contrainte CHECK sur sector est modifiée dans V3_005 (après backfill des anciennes
-- valeurs ONG/MIXTE vers leurs nouveaux équivalents ONG_HUMANITAIRE/AUTRE).

-- 1. Nouvelles colonnes (les anciennes restent intactes pour rétro-compat des writes/tests
-- le temps du backfill V3_005 ; ensuite les writes passent par les nouvelles).


ALTER TABLE companies ADD COLUMN IF NOT EXISTS organization_nature     VARCHAR(30)  NOT NULL DEFAULT 'FOR_PROFIT';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS business_type_code       VARCHAR(60);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS primary_activity_label   VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS extra_attributes         JSONB;

-- 2. Contraintes CHECK sur les nouvelles colonnes.

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_organization_nature
    CHECK (organization_nature IN ('FOR_PROFIT','NON_PROFIT','PUBLIC_SECTOR','COOPERATIVE'));

-- 3. FK business_type_code → business_type(code) — non déferrable (la table de référence
--    existe depuis V3_003). Nullable pendant le backfill (V3_005 le remplit, une contrainte
--    NOT NULL sera ajoutée explicitement à la fin de V3_005).

ALTER TABLE companies
    ADD CONSTRAINT fk_companies_business_type
    FOREIGN KEY (business_type_code) REFERENCES business_type(code);

-- Note : la colonne business_type_code reste NULLABLE jusqu'à la fin du backfill V3_005,
-- puis NOT NULL est ajouté dans V3_005 une fois toutes les lignes backfillées.
-- Note : la contrainte chk_companies_sector (qui n'accepte que COMMERCE/SERVICE/ONG/MIXTE)
-- reste en vigueur jusqu'à V3_005, où elle est remplacée après renommage des valeurs.
