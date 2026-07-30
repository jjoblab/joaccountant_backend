-- V82 — v8-6 : Étendre invoice_line_tax.tax_type pour supporter VAT_EXEMPT_ZF et VAT_EXEMPT_NGO.
--
-- CONTEXTE : avant V8-6, les imports en franchise douanière d'une entreprise zone franche
-- (Code Fiscal art. 195) généraient une TVA déductible fictive (10% sur facture d'import),
-- ce qui faussait la déclaration TVA mensuelle. En réalité, une ZF ne peut PAS déduire la TVA
-- sur imports en franchise — elle doit porter un taux 0% explicite + tracer l'exonération.
--
-- Idem pour les ONG exonérées (Code Fiscal art. 195) sur certains achats.
--
-- Valeurs ajoutées :
--   - VAT_EXEMPT_ZF  : Exonération TVA zone franche (taux 0% + filtrage de la déclaration TVA)
--   - VAT_EXEMPT_NGO : Exonération TVA ONG (taux 0% + filtrage de la déclaration TVA)

ALTER TABLE invoice_line_tax DROP CONSTRAINT IF EXISTS chk_invoice_line_tax_type;
ALTER TABLE invoice_line_tax
    ADD CONSTRAINT chk_invoice_line_tax_type CHECK (
        tax_type IN ('VAT', 'TCA', 'TURNOVER_TAX', 'EXCISE', 'VAT_EXEMPT_ZF', 'VAT_EXEMPT_NGO')
    );

COMMENT ON CONSTRAINT chk_invoice_line_tax_type ON invoice_line_tax IS
    'V82 — v8-6 : ajout VAT_EXEMPT_ZF (zone franche) et VAT_EXEMPT_NGO (ONG) — Code Fiscal art. 195.';
