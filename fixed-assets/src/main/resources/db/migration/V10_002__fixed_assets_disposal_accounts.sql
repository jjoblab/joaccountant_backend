-- V10_002 — fixed-assets : ajout des comptes de plus/moins-value de cession + écriture d'acquisition
-- (audits M10 et M11).
--
-- AUDIT M11 : avant cette correction, la plus-value de cession était créditée sur
-- depreciationExpenseAccountId (compte de charge d'amortissement) au lieu d'un compte de
-- produit. La moins-value était débitée sur le même compte de charge. Cela produisait un
-- compte de résultat faux :
--   - produits sous-estimés (pas de produit de cession)
--   - charges surestimées (charge d'amortissement artificielle)
--   - résultat net sous-estimé du double de la plus-value
--
-- Cette migration ajoute deux colonnes optionnelles :
--   - disposal_gain_account_id  : compte de PRODUITS pour les plus-values de cession
--                                 (ex. 775 "Produits de cession d'immobilisations" en SYSCOHADA)
--   - disposal_loss_account_id  : compte de CHARGES pour les moins-values de cession
--                                 (ex. 675 "Valeurs comptables des immobilisations cédées" en SYSCOHADA)
--
-- Si NULL à la cession, le service fallback sur depreciationExpenseAccountId (rétro-compatibilité).
-- La validation sémantique (audit M9) exige PRODUITS pour gain et CHARGES pour loss.
--
-- AUDIT M10 : avant cette correction, AUCUNE écriture d'acquisition d'immobilisation n'était
-- générée automatiquement à la création de l'actif. Le compte d'immobilisation restait à 0
-- alors que l'amortissement mensuel créditait le compte d'amortissement cumulé et débiter
-- le compte de charge d'amortissement — d'où un bilan faux dès l'acquisition. Cette migration
-- ajoute une colonne acquisition_journal_entry_id pour tracer l'écriture d'acquisition générée.

ALTER TABLE asset
    ADD COLUMN IF NOT EXISTS disposal_gain_account_id UUID,
    ADD COLUMN IF NOT EXISTS disposal_loss_account_id UUID,
    ADD COLUMN IF NOT EXISTS acquisition_journal_entry_id UUID;

COMMENT ON COLUMN asset.disposal_gain_account_id IS
    'Compte de PRODUITS pour les plus-values de cession (audit M11). NULL = fallback sur depreciation_expense_account_id.';
COMMENT ON COLUMN asset.disposal_loss_account_id IS
    'Compte de CHARGES pour les moins-values de cession (audit M11). NULL = fallback sur depreciation_expense_account_id.';
COMMENT ON COLUMN asset.acquisition_journal_entry_id IS
    'ID de l''écriture de JournalEntry générée à l''acquisition (audit M10). NULL si non générée.';

