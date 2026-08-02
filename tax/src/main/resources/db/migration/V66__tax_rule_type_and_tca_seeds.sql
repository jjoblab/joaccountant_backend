-- V55 — Lot B R-07 — Ajout colonne tax_type sur tax_rule + seeds TCA/TVA Haïti.
--
-- Avant V55, toutes les TaxRule étaient implicitement considérées comme de la TVA. Or, le
-- Code Fiscal haïtien distingue plusieurs taxes sur le chiffre d'affaires qui sont
-- CUMULABLES sur une même opération :
--   * TVA  — Taxe sur la Valeur Ajoutée (10% en Haïti, art. 191 du Code Fiscal)
--   * TCA  — Taxe sur le Chiffre d'Affaires :
--              - 2% sur opérations bancaires (art. 197)
--              - 5% sur télécommunications
--              - 10% sur autres services (art. 196)
--   * TURNOVER_TAX — Taxe minimum forfaitaire OHADA (hors périmètre Haïti pour l'instant)
--   * EXCISE       — Accises (alcool, tabac, carburant — non modélisé au MVP)
--
-- Sans cette distinction, une entreprise haïtienne qui émet une facture de prestation de
-- services devait choisir entre TVA 10% et TCA 10% — alors qu'en réalité les deux taxes
-- s'appliquent simultanément (TVA + TCA = 20% sur le service). La déclaration TVA était
-- donc systématiquement sous-évaluée pour ces entreprises.
--
-- Approche : ajout d'une colonne tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT' sur tax_rule,
-- avec CHECK constraint pour garantir la cohérence. Le défaut 'VAT' préserve le
-- comportement historique de toutes les règles existantes (rétro-compatibilité).
--
-- En parallèle, on seede 4 règles globales Haïti (company_id IS NULL) :
--   * TVA_HT_10          — TVA 10% (Code Fiscal art. 191)
--   * TCA_HT_2_BANK      — TCA 2% sur opérations bancaires (art. 197)
--   * TCA_HT_5_TELECOM   — TCA 5% sur télécommunications
--   * TCA_HT_10_SERVICES — TCA 10% sur autres services (art. 196)
--
-- Ces règles globales sont disponibles pour toutes les entreprises dont country='HT' ; elles
-- sont filtrables via listTaxRules (findByCompanyIdOrCompanyIdIsNull).

ALTER TABLE tax_rule
    ADD COLUMN IF NOT EXISTS tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT';

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE tax_rule SET tax_type = 'VAT' WHERE tax_type IS NULL;

ALTER TABLE tax_rule
    DROP CONSTRAINT IF EXISTS chk_tax_rule_tax_type;
ALTER TABLE tax_rule
    ADD CONSTRAINT chk_tax_rule_tax_type CHECK (tax_type IN ('VAT','TCA','TURNOVER_TAX','EXCISE'));

COMMENT ON COLUMN tax_rule.tax_type IS
    'V55 — Lot B R-07 : type de taxe. VAT=défaut (rétro-compat), TCA=Taxe Chiffre Affaires Haïti (art. 196/197), TURNOVER_TAX, EXCISE.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Seeds Haïti — règles globales (company_id IS NULL)
-- ─────────────────────────────────────────────────────────────────────────────
-- Note : payable_account_id et receivable_account_id sont laissés NULL (les comptes
-- sont résolus au moment de l'écriture comptable via AccountResolver). applicable_from
-- = '2024-01-01' (date indicative — l'utilisateur peut surcharger par une règle
-- spécifique à son entreprise).

INSERT INTO tax_rule (id, company_id, code, label, rate, payable_account_id, receivable_account_id,
                      applicable_from, applicable_to, active, vat_mode, tax_type, version)
VALUES
    (uuidv7(), NULL, 'TVA_HT_10',
     'TVA Haïti 10% (Code Fiscal art. 191)',
     10.00, NULL, NULL, '2024-01-01', NULL, TRUE, 'DEBIT', 'VAT', 0),
    (uuidv7(), NULL, 'TCA_HT_2_BANK',
     'TCA Haïti 2% sur opérations bancaires (Code Fiscal art. 197)',
     2.00, NULL, NULL, '2024-01-01', NULL, TRUE, 'DEBIT', 'TCA', 0),
    (uuidv7(), NULL, 'TCA_HT_5_TELECOM',
     'TCA Haïti 5% sur télécommunications',
     5.00, NULL, NULL, '2024-01-01', NULL, TRUE, 'DEBIT', 'TCA', 0),
    (uuidv7(), NULL, 'TCA_HT_10_SERVICES',
     'TCA Haïti 10% sur autres services (Code Fiscal art. 196)',
     10.00, NULL, NULL, '2024-01-01', NULL, TRUE, 'DEBIT', 'TCA', 0)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE tax_rule IS
    'V55 — Lot B R-07 : tax_rule supporte désormais TVA + TCA + TURNOVER_TAX + EXCISE. Seeds Haïti ajoutés.';
