-- V3_009 — fx operations module
-- V33 — FX_OPERATIONS module ( §3).
-- 1. Élargir le CHECK chk_btm_module_code pour autoriser FX_OPERATIONS.
-- 2. Mapper FX_OPERATIONS par défaut sur les types métier qui en ont besoin.


ALTER TABLE business_type_module DROP CONSTRAINT IF EXISTS chk_btm_module_code;

ALTER TABLE business_type_module ADD CONSTRAINT chk_btm_module_code CHECK (module_code IN (
    'CHART_OF_ACCOUNTS','ACCOUNTING_ENGINE','THIRD_PARTIES','INVOICING',
    'DOCUMENT_NUMBERING','APPROVAL_WORKFLOW','DOCUMENT_GENERATION','NOTIFICATIONS',
    'AUDIT_TRAIL','FINANCIAL_STATEMENTS','ANALYTICS','REPORTING',
    'INVENTORY','TIME_BILLING','FUNDS_GRANTS','FIXED_ASSETS','BANK_RECONCILIATION','TAX',
    'PURCHASING','EXPENSES','EMPLOYEES','PAYROLL',
    'FX_OPERATIONS'
));

-- Mapping par défaut : FX_OPERATIONS activé pour les types métier avec opérations en devise étrangère.
INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('RETAIL_COMMERCE',        'FX_OPERATIONS'),
    ('WHOLESALE_COMMERCE',     'FX_OPERATIONS'),
    ('MIXED_COMMERCE',         'FX_OPERATIONS'),
    ('ECOMMERCE',              'FX_OPERATIONS'),
    ('NGO_HUMANITARIAN',       'FX_OPERATIONS'),
    ('HOSPITAL',               'FX_OPERATIONS')
ON CONFLICT DO NOTHING;
