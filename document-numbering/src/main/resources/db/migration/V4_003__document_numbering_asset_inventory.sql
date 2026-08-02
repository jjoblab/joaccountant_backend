-- V4_003 — document numbering asset inventory
-- V91 — Élargit le CHECK chk_doc_seq_doc_type pour autoriser ASSET et INVENTORY_ITEM.
-- v2.7.0 (2026-08-02) : les modules :fixed-assets et :inventory émettent des documents
-- numérotés visibles (numéro d'immobilisation, code article SKU auto-généré) — on étend
-- la contrainte DB pour autoriser ces 2 nouvelles valeurs.
-- L'enum Java DocumentType a déjà été mis à jour (cf. document-numbering/.../entity/DocumentType.java)
-- — cette migration corrige la contrainte DB qui ne listait que les 6 valeurs d'origine.


ALTER TABLE document_sequence_config DROP CONSTRAINT IF EXISTS chk_doc_seq_doc_type;

ALTER TABLE document_sequence_config ADD CONSTRAINT chk_doc_seq_doc_type CHECK (document_type IN (
    'JOURNAL_ENTRY','SALES_INVOICE','CREDIT_NOTE','DONATION_RECEIPT',
    -- Restructuration 2026-07-24 (suite) — 2 types de documents numérotés
    'PURCHASE_INVOICE','PAYSLIP',
    -- v2.7.0 (2026-08-02) — 2 nouveaux types pour fixed-assets et inventory
    'ASSET','INVENTORY_ITEM'
));
