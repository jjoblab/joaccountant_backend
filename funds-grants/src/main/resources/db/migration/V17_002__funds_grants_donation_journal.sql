-- V17_002 — funds-grants : tracer l'écriture comptable générée à la création d'un reçu de don
-- (audit M7 — rapport bailleur ONG non reconciliable).
-- AUDIT M7 : avant cette correction, FundsGrantsService.createDonationReceipt ne générait
-- AUCUNE écriture comptable. Le getDonorReport calculait alors :
-- - totalReceived = Σ DonationReceipt.amount (documents externes)
-- - totalSpent = Σ JournalLine.debit (écritures comptables, POSTED, taguées)
-- Ces deux montants venaient de sources disjointes : aucun moyen de les reconcilier, et le
-- bilan ne reflétait pas les produits de dons reçus. Pour un ONG, le rapport bailleur était
-- donc faux par construction (le total reçu n'apparaissait jamais dans la comptabilité).
-- CORRECTION : createDonationReceipt génère désormais une écriture de produit (D trésorerie /
-- C produit de don) au moment de la création, et stocke l'ID de cette écriture dans la
-- nouvelle colonne journal_entry_id. Le getDonorReport peut alors calculer totalReceived
-- depuis les JournalLine (même source que totalSpent) — reconciliable.
-- Rétro-compatibilité : les reçus créés avant cette correction ont journal_entry_id = NULL.
-- Le getDonorReport utilise alors le fallback sur DonationReceipt.amount pour ces reçus
-- anciens, afin de ne pas perdre l'historique.


ALTER TABLE fg_donation_receipt
    ADD COLUMN IF NOT EXISTS journal_entry_id UUID;

COMMENT ON COLUMN fg_donation_receipt.journal_entry_id IS
    'ID de l''écriture de JournalEntry générée à la création du reçu (audit M7). NULL pour les reçus créés avant la correction — fallback sur amount dans le rapport bailleur.';
