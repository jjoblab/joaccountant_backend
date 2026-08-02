-- V45 — Finding #7 — Autoliquidation / reverse-charge (intra-UE B2B).
--
-- Avant V45, le module :invoicing collectait systématiquement la TVA sur le compte 443 (TVA
-- collectée) à l'émission des factures. Or, pour les opérations intra-UE B2B (Article 283,
-- 2 nonies du CGI), la TVA n'est pas collectée par l'émetteur : c'est le client qui l'auto-
-- liquidé. Sans ce support, les factures B2B intra-UE étaient incorrectement assujetties à
-- la TVA côté émetteur, et la déclaration TVA de l'entreprise était fausse (TVA collectée à
-- tort).
--
-- V45 ajoute une colonne sur sales_invoice :
--
-- 1. sales_invoice.is_reverse_charge (BOOLEAN, NOT NULL, défaut FALSE)
--    Positionné à TRUE à l'émission quand le tiers client ET l'entreprise émettrice disposent
--    tous deux d'un numéro de TVA intracommunautaire. Dans ce cas, l'écriture comptable crédite
--    le compte 447 « TDA autoliquidation » (taxMappingCode = "VAT_REVERSE_CHARGE",
--    fallback SYSCOHADA/PCG 444700/4447) au lieu du 443 (TVA collectée). La facture porte la
--    mention « Autoliquidation - Article 283, 2 nonies du CGI ».
--
-- Le défaut FALSE assure la rétro-compatibilité de toutes les factures existantes (aucun
-- comportement modifié — toutes les factures existantes sont pré-V45 et ont été émises en
-- collecte classique).

ALTER TABLE sales_invoice
    ADD COLUMN IF NOT EXISTS is_reverse_charge BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE sales_invoice SET is_reverse_charge = FALSE WHERE is_reverse_charge IS NULL;

COMMENT ON COLUMN sales_invoice.is_reverse_charge IS
    'V45 — Finding #7 : autoliquidation intra-UE B2B. TRUE si le tiers et l''entreprise ont un VAT number (Article 283, 2 nonies CGI). Crédit 447 au lieu de 443.';
