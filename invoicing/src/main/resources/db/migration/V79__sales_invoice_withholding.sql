-- =====================================================================
-- V68 — R-F-validation v6-2 — RS sur ventes (SalesInvoice)
-- =====================================================================
-- Découlé des validations PME2 (Moïse & Associés) + expert-comptable
-- DGI Haïti : la retenue à la source (RS) sur les ventes (prestations
-- facturées aux entreprises clientes, Code Fiscal art. 156-1) n'était
-- PAS implémentée côté ventes — seulement côté achats
-- (`PurchasingService.calculateSupplierWithholding`).
--
-- En Haïti, le client retient 2% de RS à la source et paie HT - 2% RS.
-- L'entreprise émettrice doit déclarer cette RS à la DGI mensuellement
-- (15 du mois M+1) et la reverser pour le compte du client.
--
-- Cette migration ajoute 4 colonnes à sales_invoice :
--   1. withholding_rate   : taux RS appliqué (ex : 2.00 pour 2% Haïti art. 156-1)
--   2. withholding_amount : montant RS retenu = subtotal × withholding_rate / 100
--   3. net_receivable     : montant net à recevoir = totalAmount - withholdingAmount
--   4. withholding_rule_id : FK vers withholding_rule.id (règle appliquée)
--
-- Toutes les colonnes sont NULLables → rétro-compatibles : les factures
-- existantes (sans RS) ont NULL dans tous les champs, et le comportement
-- de InvoicingService.createInvoice est inchangé si withholdingRuleCode
-- et withholdingRate sont absents de la requête.
-- =====================================================================

ALTER TABLE sales_invoice
    ADD COLUMN IF NOT EXISTS withholding_rate        NUMERIC(5, 2),
    ADD COLUMN IF NOT EXISTS withholding_amount      NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS net_receivable          NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS withholding_rule_id     UUID;

COMMENT ON COLUMN sales_invoice.withholding_rate IS
    'V68 — R-F-validation v6-2 : taux RS appliqué sur la facture (ex : 2.00 pour RS 2% Haïti art. 156-1). NULL si pas de RS.';
COMMENT ON COLUMN sales_invoice.withholding_amount IS
    'V68 — R-F-validation v6-2 : montant RS retenu par le client = subtotal × withholding_rate / 100.';
COMMENT ON COLUMN sales_invoice.net_receivable IS
    'V68 — R-F-validation v6-2 : montant net à recevoir = totalAmount − withholdingAmount.';
COMMENT ON COLUMN sales_invoice.withholding_rule_id IS
    'V68 — R-F-validation v6-2 : référence à la WithholdingRule appliquée (FK vers withholding_rule.id).';

CREATE INDEX IF NOT EXISTS idx_sales_invoice_withholding_rule
    ON sales_invoice (company_id, withholding_rule_id)
    WHERE withholding_rule_id IS NOT NULL;
