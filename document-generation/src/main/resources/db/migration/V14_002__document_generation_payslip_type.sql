-- V14_002 — document generation payslip type
-- V30 — Élargit les CHECK chk_dt_document_type et chk_gd_document_type pour autoriser PAYSLIP.
-- de paie PDF via :document-generation. La facture d'achat n'a pas de PDF au MVP (document
-- interne — aucune valeur PURCHASE_INVOICE à ajouter ici, voir §2.5 du prompt).
-- L'enum Java DocumentType (module :document-generation) a déjà été mis à jour (cf.
-- document-generation/.../entity/DocumentType.java) — cette migration corrige les deux
-- contraintes DB qui ne listaient que les 7 valeurs d'origine.


ALTER TABLE document_template DROP CONSTRAINT IF EXISTS chk_dt_document_type;
ALTER TABLE document_template ADD CONSTRAINT chk_dt_document_type CHECK (document_type IN (
    'INVOICE','CREDIT_NOTE','DONATION_RECEIPT','BALANCE_SHEET',
    'INCOME_STATEMENT','GENERAL_LEDGER','DONOR_REPORT',
    -- Restructuration 2026-07-24 (suite) — bulletin de paie
    'PAYSLIP'
));

ALTER TABLE generated_document DROP CONSTRAINT IF EXISTS chk_gd_document_type;
ALTER TABLE generated_document ADD CONSTRAINT chk_gd_document_type CHECK (document_type IN (
    'INVOICE','CREDIT_NOTE','DONATION_RECEIPT','BALANCE_SHEET',
    'INCOME_STATEMENT','GENERAL_LEDGER','DONOR_REPORT',
    -- Restructuration 2026-07-24 (suite) — bulletin de paie
    'PAYSLIP'
));
