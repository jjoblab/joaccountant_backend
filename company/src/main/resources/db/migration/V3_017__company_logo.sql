-- V3_017 — company logo storage key
-- Fix PDF v9.4 — Ajoute une colonne logo_storage_key sur companies pour stocker
-- la clé opaque du logo entreprise (uploadé via FileStoragePort).
--
-- Avant ce fix, le logo entreprise était promis en Javadoc de DocumentGenerationService
-- mais jamais implémenté : aucune colonne n'existait pour stocker la clé du logo.
-- Les PDF générés affichaient seulement le nom de l'entreprise en texte brut.
--
-- Approche : ALTER TABLE ... ADD COLUMN IF NOT EXISTS (idempotent).
-- La clé fait référence à un blob stocké via FileStoragePort (S3 ou filesystem).
-- NULL = pas de logo configuré (le PDF affiche le nom en texte brut).


ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS logo_storage_key VARCHAR(200);

COMMENT ON COLUMN companies.logo_storage_key IS
    'Fix PDF v9.4 — Clé opaque du logo entreprise (stocké via FileStoragePort). NULL = pas de logo. Uploadé via POST /api/v1/companies/{id}/logo.';
