-- V17_006 — donation receipt type
-- V81 — v8-5 : Ajout du champ donation_type sur fg_donation_receipt.
-- CONTEXTE : -5, tous les dons (cash + en nature) étaient comptabilisés via
-- D 521 Trésorerie / C 70x Produit de don. Pour les ONG qui reçoivent 30% de leurs revenus
-- en nature (médicaments, nourriture, équipements), c'est incorrect : un don en nature
-- doit débiter un compte de stock (3x) ou d'immobilisation (215), pas la trésorerie.
-- Cette migration ajoute la colonne donation_type (CASH ou IN_KIND) avec défaut CASH
-- pour préserver la rétro-compatibilité (les reçus créés -5 sont considérés cash).


ALTER TABLE fg_donation_receipt
    ADD COLUMN IF NOT EXISTS donation_type VARCHAR(10) NOT NULL DEFAULT 'CASH';

ALTER TABLE fg_donation_receipt
    DROP CONSTRAINT IF EXISTS chk_fg_donation_receipt_type;
ALTER TABLE fg_donation_receipt
    ADD CONSTRAINT chk_fg_donation_receipt_type CHECK (
        donation_type IN ('CASH', 'IN_KIND')
    );

COMMENT ON COLUMN fg_donation_receipt.donation_type IS
    'V81 — v8-5 : CASH (D 521/C 70x) ou IN_KIND (D 3x ou D 215 / C 70x). Défaut CASH.';
