-- V4_002 — document numbering type expansion
-- V29 — Élargit le CHECK chk_doc_seq_doc_type pour autoriser PURCHASE_INVOICE et PAYSLIP.
-- émettent des documents numérotés visibles (facture d'achat interne, bulletin de paie).
-- L'enum Java DocumentType a déjà été mis à jour (cf.
-- document-numbering/.../entity/DocumentType.java) — cette migration corrige la contrainte
-- DB qui ne listait que les 4 valeurs d'origine.


ALTER TABLE document_sequence_config DROP CONSTRAINT IF EXISTS chk_doc_seq_doc_type;

ALTER TABLE document_sequence_config ADD CONSTRAINT chk_doc_seq_doc_type CHECK (document_type IN (
    'JOURNAL_ENTRY','SALES_INVOICE','CREDIT_NOTE','DONATION_RECEIPT',
    -- Restructuration 2026-07-24 (suite) — 2 nouveaux types de documents numérotés
    'PURCHASE_INVOICE','PAYSLIP'
));
