-- V28 — Élargit le CHECK chk_je_source_module pour autoriser PURCHASING, EXPENSES, PAYROLL.
-- Restructuration 2026-07-24 (suite — Partie B) : les 4 nouveaux modules :purchasing,
-- :expenses, :payroll génèrent des écritures comptables et doivent être tracés via
-- JournalEntry.sourceModule. :employees ne génère aucune écriture (aucune valeur à ajouter
-- pour ce module ici).
--
-- L'enum Java JournalEntrySourceModule a déjà été mis à jour (cf.
-- accounting-engine/.../entity/JournalEntrySourceModule.java) — cette migration corrige
-- la contrainte DB qui ne listait que les 6 valeurs d'origine.

ALTER TABLE journal_entry DROP CONSTRAINT IF EXISTS chk_je_source_module;

ALTER TABLE journal_entry ADD CONSTRAINT chk_je_source_module CHECK (source_module IN (
    'MANUAL','FIXED_ASSETS','INVENTORY','INVOICING','FUNDS_GRANTS','REVERSAL',
    -- Restructuration 2026-07-24 (suite) — 3 nouveaux modules générateurs d'écritures
    'PURCHASING','EXPENSES','PAYROLL'
));
