-- V3_014 — wizard refonte 4 steps
-- V84 — V8.2 Wizard refondu : 4 étapes au lieu de 9.
-- CONTEXTE : le wizard actuel a 9 étapes dont 2 inutiles (8 = no-op sauf CUSTOM, 9 = vide).
-- La refonte ramène à 4 étapes avec activation atomique.
-- Cette migration :
-- 1. Met à jour les companies existantes avec wizardStep > 4 (anciennes étapes) → wizardStep = 4
-- 2. Marque les companies abandonnées (wizardStep < 9 ET wizardCompleted = false) comme wizardStep = leur étape actuelle (max 4)
-- 3. Supprime la contrainte de out-of-order côté applicatif (pas de DDL — c'est du code Java)

-- Backfill : les companies déjà complétées (wizardCompleted = true) gardent leur statut.
-- Les companies en cours de wizard (wizardCompleted = false) sont ramenées à wizardStep = min(wizardStep, 4).


UPDATE companies
SET wizard_step = LEAST(wizard_step, 4),
    updated_at = NOW()
WHERE wizard_completed = false AND wizard_step > 4;

-- Les companies complétées sont forcées à wizardStep = 4 (la nouvelle valeur max).
UPDATE companies
SET wizard_step = 4,
    updated_at = NOW()
WHERE wizard_completed = true;

COMMENT ON COLUMN companies.wizard_step IS
    'V8.2 — Wizard refondu : 4 étapes (1=identité, 2=activité, 3=comptabilité, 4=activation). Anciennement 9 étapes.';
