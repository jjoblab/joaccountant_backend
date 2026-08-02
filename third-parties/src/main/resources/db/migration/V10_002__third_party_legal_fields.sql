-- V10_002 — third party legal fields
-- Ajout des champs SIRET, VAT, NIF sur la table third_party pour
-- conformité mentions légales factures (CGI art. 289) et Factur-X.
-- (Ces champs étaient historiquement dans la migration company V42,
--  déplacés ici car third_party est créée en V10_001.)


ALTER TABLE third_party ADD COLUMN IF NOT EXISTS siret VARCHAR(20);
ALTER TABLE third_party ADD COLUMN IF NOT EXISTS vat_number VARCHAR(20);
ALTER TABLE third_party ADD COLUMN IF NOT EXISTS nif VARCHAR(30);

COMMENT ON COLUMN third_party.siret IS
    'SIRET du tiers pour mentions légales factures + Factur-X.';
COMMENT ON COLUMN third_party.vat_number IS
    'TVA intracommunautaire du tiers pour B2B intra-UE + Factur-X.';
COMMENT ON COLUMN third_party.nif IS
    'NIF du tiers (équivalent SIRET hors France).';
