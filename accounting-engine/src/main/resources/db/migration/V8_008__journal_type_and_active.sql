-- V8_008 — journal type and active
-- V85 — V8.2 Phase 3 : Ajout des colonnes type et active sur la table journal.
-- CONTEXTE : .2, l'entité Journal était anémique (2 champs : code + label).
-- Le "type" de journal était purement conventionnel via le code (VT=Ventes, AC=Achats, etc.).
-- Aucun moyen de désactiver un journal sans le supprimer (impossible si écritures référencent).
-- Cette migration :
-- 1. Ajoute la colonne `type` (VARCHAR 15, nullable — null pour les journaux personnalisés)
-- 2. Ajoute la colonne `active` (BOOLEAN NOT NULL DEFAULT true)
-- 3. Backfill `type` depuis le code existant (VT→VENTES, AC→ACHATS, BQ→BANQUE, CA→CAISSE,
-- OD→OD, PA→PAIE, DP→DEPENSES, FX→FX)
-- 4. Ajoute un COMMENT sur les colonnes pour documentation

-- 1. Ajout colonne type (nullable — null pour journaux personnalisés non standards)


ALTER TABLE journal ADD COLUMN IF NOT EXISTS type VARCHAR(15);

-- 2. Ajout colonne active (NOT NULL, défaut true)
ALTER TABLE journal ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;

-- 3. Backfill type depuis le code existant
UPDATE journal SET type = 'VENTES'   WHERE code = 'VT' AND type IS NULL;
UPDATE journal SET type = 'ACHATS'   WHERE code = 'AC' AND type IS NULL;
UPDATE journal SET type = 'BANQUE'   WHERE code = 'BQ' AND type IS NULL;
UPDATE journal SET type = 'CAISSE'   WHERE code = 'CA' AND type IS NULL;
UPDATE journal SET type = 'OD'       WHERE code = 'OD' AND type IS NULL;
UPDATE journal SET type = 'PAIE'     WHERE code = 'PA' AND type IS NULL;
UPDATE journal SET type = 'DEPENSES' WHERE code = 'DP' AND type IS NULL;
UPDATE journal SET type = 'FX'       WHERE code = 'FX' AND type IS NULL;
-- Les journaux avec un code non-standard (ex: BQ1, BQ2, OD-Cloture) restent à NULL
-- (journaux personnalisés — l'admin peut les typer manuellement via UPDATE si besoin).

COMMENT ON COLUMN journal.type IS
    'V8.2 Phase 3 — Type de journal (VENTES, ACHATS, BANQUE, CAISSE, OD, PAIE, DEPENSES, FX). Null pour les journaux personnalisés.';
COMMENT ON COLUMN journal.active IS
    'V8.2 Phase 3 — Indique si le journal accepte de nouvelles écritures (true par défaut). Inactif = historique conservé mais saisie bloquée.';
