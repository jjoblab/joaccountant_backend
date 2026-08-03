-- V19_013 — corporate_tax_rule audit columns
-- =====================================================================
-- v9.4 fix — L'entité CorporateTaxRule extends TenantAwareEntity qui déclare
-- created_by / updated_by. La table créée par V19_009 n'avait pas ces colonnes
-- → Hibernate génère un SELECT incluant created_by/updated_by → PSQLException
-- "column ctr1_0.created_by does not exist" sur tous les endpoints qui touchent
-- CorporateTaxRule (corporate-tax/projection, TVA declaration PDF, etc.).
-- Cette migration ajoute les colonnes manquantes (idempotent).
-- =====================================================================

ALTER TABLE corporate_tax_rule
    ADD COLUMN IF NOT EXISTS created_by UUID;

ALTER TABLE corporate_tax_rule
    ADD COLUMN IF NOT EXISTS updated_by UUID;

COMMENT ON COLUMN corporate_tax_rule.created_by IS
    'v9.4 fix — Colonne daudit requise par TenantAwareEntity (manquait dans V19_009).';
COMMENT ON COLUMN corporate_tax_rule.updated_by IS
    'v9.4 fix — Colonne daudit requise par TenantAwareEntity (manquait dans V19_009).';
