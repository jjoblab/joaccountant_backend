-- V3_007 — business type catalog expansion
-- V23 — Expansion du catalogue de types métier (Partie A du prompt 2026-07-24 — suite).
-- Trois axes dans cette migration :
-- 1. Élargir le CHECK chk_btm_module_code pour autoriser PURCHASING, EXPENSES, EMPLOYEES,
-- PAYROLL (sinon les INSERT ci-dessous pour PURCHASING échouent — la contrainte d'origine
-- ne listait que les 18 codes du socle + sectoriels initiaux).
-- 2. Ajouter 3 nouveaux types métier COMMERCE : WHOLESALE_COMMERCE, MIXED_COMMERCE, ECOMMERCE.
-- Tous avec defaultOrganizationNature=FOR_PROFIT, defaultSector=COMMERCE.
-- 3. Corriger le mapping existant :
-- - HOSPITAL gagne INVENTORY ( trou fonctionnel — gestion des stocks de médicaments).
-- - RETAIL_COMMERCE, PROFESSIONAL_SERVICES, NGO_HUMANITARIAN, ACCOUNTING_FIRM, SCHOOL,
-- HOSPITAL, ainsi que les 3 nouveaux types COMMERCE, gagnent tous PURCHASING.
-- Les 4 variants commerce (RETAIL, WHOLESALE, MIXED, ECOMMERCE) partagent aujourd'hui
-- le même set de modules — c'est volontaire (distinction descriptive/UX seulement,
-- pas d'implication technique gros vs détail — voir §1.2 du prompt).
-- Pas de BusinessTypeRequiredField ajouté : aucun des 3 nouveaux types n'a de champ
-- spécifique obligatoire évident au MVP (voir §1.4 du prompt — laisser vide si rien d'évident).

-- 1. Élargir le CHECK chk_btm_module_code -------------------------------------------
-- DROP + recréer avec les 4 nouveaux codes ajoutés à la liste existante.


ALTER TABLE business_type_module DROP CONSTRAINT IF EXISTS chk_btm_module_code;

ALTER TABLE business_type_module ADD CONSTRAINT chk_btm_module_code CHECK (module_code IN (
    'CHART_OF_ACCOUNTS','ACCOUNTING_ENGINE','THIRD_PARTIES','INVOICING',
    'DOCUMENT_NUMBERING','APPROVAL_WORKFLOW','DOCUMENT_GENERATION','NOTIFICATIONS',
    'AUDIT_TRAIL','FINANCIAL_STATEMENTS','ANALYTICS','REPORTING',
    'INVENTORY','TIME_BILLING','FUNDS_GRANTS','FIXED_ASSETS','BANK_RECONCILIATION','TAX',
    -- Restructuration 2026-07-24 (suite) — 4 nouveaux modules
    'PURCHASING','EXPENSES','EMPLOYEES','PAYROLL'
));

-- 2. Nouveaux types métier COMMERCE --------------------------------------------------

INSERT INTO business_type (code, label, default_organization_nature, default_sector, description, active) VALUES
    ('WHOLESALE_COMMERCE', 'Commerce de gros',                'FOR_PROFIT', 'COMMERCE',
     'Commerce de gros — vente en quantité à des distributeurs, détaillants ou autres professionnels.', true),
    ('MIXED_COMMERCE',     'Commerce de gros et de détail',   'FOR_PROFIT', 'COMMERCE',
     'Activité mixte combinant vente en gros et vente au détail (ex. comptoir de distribution avec showroom).', true),
    ('ECOMMERCE',          'Commerce électronique / vente en ligne', 'FOR_PROFIT', 'COMMERCE',
     'Vente en ligne — boutique e-commerce, marketplace, dropshipping. Logistique externalisée ou propre.', true)
ON CONFLICT (code) DO NOTHING;

-- 3. Mapping type métier → modules sectoriels --------------------------------------
--    3.a — 3 nouveaux types COMMERCE × 5 modules (INVENTORY, FIXED_ASSETS,
--          BANK_RECONCILIATION, TAX, PURCHASING). Tous les quatre variants commerce
--          activent aujourd'hui les mêmes modules (volontaire — voir §1.2 du prompt).

INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('WHOLESALE_COMMERCE', 'INVENTORY'),
    ('WHOLESALE_COMMERCE', 'FIXED_ASSETS'),
    ('WHOLESALE_COMMERCE', 'BANK_RECONCILIATION'),
    ('WHOLESALE_COMMERCE', 'TAX'),
    ('WHOLESALE_COMMERCE', 'PURCHASING'),
    ('MIXED_COMMERCE',     'INVENTORY'),
    ('MIXED_COMMERCE',     'FIXED_ASSETS'),
    ('MIXED_COMMERCE',     'BANK_RECONCILIATION'),
    ('MIXED_COMMERCE',     'TAX'),
    ('MIXED_COMMERCE',     'PURCHASING'),
    ('ECOMMERCE',          'INVENTORY'),
    ('ECOMMERCE',          'FIXED_ASSETS'),
    ('ECOMMERCE',          'BANK_RECONCILIATION'),
    ('ECOMMERCE',          'TAX'),
    ('ECOMMERCE',          'PURCHASING')
ON CONFLICT DO NOTHING;

--    3.b — RETAIL_COMMERCE gagne PURCHASING (cohérence avec les 3 nouveaux variants).

INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('RETAIL_COMMERCE', 'PURCHASING')
ON CONFLICT DO NOTHING;

--    3.c — PROFESSIONAL_SERVICES, NGO_HUMANITARIAN, ACCOUNTING_FIRM, SCHOOL gagnent PURCHASING.

INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('PROFESSIONAL_SERVICES', 'PURCHASING'),
    ('NGO_HUMANITARIAN',      'PURCHASING'),
    ('ACCOUNTING_FIRM',       'PURCHASING'),
    ('SCHOOL',                'PURCHASING')
ON CONFLICT DO NOTHING;

--    3.d — HOSPITAL gagne INVENTORY (gestion des stocks de médicaments/consommables —
--          trou fonctionnel documenté au §1.3 du prompt) ET PURCHASING.

INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('HOSPITAL', 'INVENTORY'),
    ('HOSPITAL', 'PURCHASING')
ON CONFLICT DO NOTHING;
