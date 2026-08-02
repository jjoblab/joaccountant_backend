-- V3_011 — company legal fields
-- Ajout des champs SIRET, VAT, NIF, adresse sur la table companies pour
-- conformité mentions légales factures (CGI art. 289) et Factur-X.


ALTER TABLE companies ADD COLUMN IF NOT EXISTS siret VARCHAR(20);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS vat_number VARCHAR(20);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS nif VARCHAR(30);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS address VARCHAR(500);

COMMENT ON COLUMN companies.siret IS
    'SIRET (14 chiffres France) pour mentions légales factures + Factur-X.';
COMMENT ON COLUMN companies.vat_number IS
    'TVA intracommunautaire (ex: FR12345678901) pour B2B intra-UE + Factur-X.';
COMMENT ON COLUMN companies.nif IS
    'NIF (Numéro Identification Fiscale) — équivalent SIRET hors France.';
COMMENT ON COLUMN companies.address IS
    'Adresse postale pour mentions légales factures (CGI art. 289).';

-- Note : les champs équivalents sur third_party (siret, vat_number, nif) sont
-- ajoutés par la migration V10_002__third_party_legal_fields.sql dans le module
-- third-parties (car la table third_party y est créée en V10_001).
