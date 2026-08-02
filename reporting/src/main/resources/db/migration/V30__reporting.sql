-- V19_001 — reporting (module :reporting, §13 Phase 17).
-- Pas de tables dédiées en Phase 17 : :reporting orchestre uniquement les exports
-- à partir des données des autres modules. Les PDF générés sont stockés via
-- :document-generation (GeneratedDocument). Les exports CSV sont générés à la volée.

-- Aucune table à créer — c'est intentionnel. :reporting est un module d'orchestration
-- qui ne persiste aucune donnée propre.
SELECT 1;
