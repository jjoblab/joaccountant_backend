-- V42 — Audit v4.7 §4.2 Finding HAUT — Champs SIRET + TVA intracomm. + NIF + adresse sur
--        Company et ThirdParty pour conformité mentions légales factures (CGI art. 289) et Factur-X.
--
-- Sans ces champs, le Factur-X généré par FacturXExporter ne contient pas les mentions légales
-- obligatoires (SIRET émetteur, TVA intracommunautaire émetteur + client). Sanctions :
-- 15 EUR/mention manquante (LPF art. 1737 II) + amende 50% du montant (CGI art. 1737).

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Company — ajouter siret, vat_number, nif, address
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE companies ADD COLUMN IF NOT EXISTS siret VARCHAR(20);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS vat_number VARCHAR(20);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS nif VARCHAR(30);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS address VARCHAR(500);

COMMENT ON COLUMN companies.siret IS
    'V42 — Audit v4.7 §4.2 — SIRET (14 chiffres France) pour mentions légales factures + Factur-X.';
COMMENT ON COLUMN companies.vat_number IS
    'V42 — Audit v4.7 §4.2 — TVA intracommunautaire (ex: FR12345678901) pour B2B intra-UE + Factur-X.';
COMMENT ON COLUMN companies.nif IS
    'V42 — Audit v4.7 §4.2 — NIF (Numéro Identification Fiscale) — équivalent SIRET hors France.';
COMMENT ON COLUMN companies.address IS
    'V42 — Audit v4.7 §4.2 — Adresse postale pour mentions légales factures (CGI art. 289).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. ThirdParty — ajouter siret, vat_number, nif (address existe déjà)
-- NOTE : la table s'appelle "third_party" (singulier) — voir V9_001__third_parties.sql
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE third_party ADD COLUMN IF NOT EXISTS siret VARCHAR(20);
ALTER TABLE third_party ADD COLUMN IF NOT EXISTS vat_number VARCHAR(20);
ALTER TABLE third_party ADD COLUMN IF NOT EXISTS nif VARCHAR(30);

COMMENT ON COLUMN third_party.siret IS
    'V42 — Audit v4.7 §4.2 — SIRET du tiers pour mentions légales factures + Factur-X.';
COMMENT ON COLUMN third_party.vat_number IS
    'V42 — Audit v4.7 §4.2 — TVA intracommunautaire du tiers pour B2B intra-UE + Factur-X.';
COMMENT ON COLUMN third_party.nif IS
    'V42 — Audit v4.7 §4.2 — NIF du tiers (équivalent SIRET hors France).';

-- Note : pas d'index sur ces colonnes — les lookups par SIRET/VAT sont rares (admin only).
-- Si besoin d'unicité (un SIRET = une entreprise), ajouter un index unique plus tard.
