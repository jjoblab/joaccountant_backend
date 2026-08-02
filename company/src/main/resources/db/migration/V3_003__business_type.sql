-- V3_003 — Catalogue de types métier (BusinessType) + mapping modules + champs requis.
-- Restructuration de la modélisation organisationnelle (prompt 2026-07-24).
-- Tables de RÉFÉRENCE GLOBALES — NON tenant-scopées (au même titre que accounting_framework).

-- 1. Catalogue des types métier ----------------------------------------------------


CREATE TABLE IF NOT EXISTS business_type (
    code                            VARCHAR(60)  PRIMARY KEY,
    label                           VARCHAR(200) NOT NULL,
    default_organization_nature     VARCHAR(30)  NOT NULL,
    default_sector                  VARCHAR(30)  NOT NULL,
    description                     VARCHAR(1000),
    active                          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_business_type_nature CHECK (default_organization_nature IN
        ('FOR_PROFIT','NON_PROFIT','PUBLIC_SECTOR','COOPERATIVE')),
    CONSTRAINT chk_business_type_sector CHECK (default_sector IN
        ('COMMERCE','SERVICE','SANTE','EDUCATION','AGRICULTURE','INDUSTRIE',
         'ADMINISTRATION_PUBLIQUE','ONG_HUMANITAIRE','CABINET_COMPTABLE','AUTRE'))
);

-- 2. Mapping type métier → module activé (remplace le switch SectorModuleMapping) ----

CREATE TABLE IF NOT EXISTS business_type_module (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    business_type_code    VARCHAR(60) NOT NULL REFERENCES business_type(code) ON DELETE CASCADE,
    module_code           VARCHAR(40) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_business_type_module UNIQUE (business_type_code, module_code),
    CONSTRAINT chk_btm_module_code CHECK (module_code IN
        ('CHART_OF_ACCOUNTS','ACCOUNTING_ENGINE','THIRD_PARTIES','INVOICING',
         'DOCUMENT_NUMBERING','APPROVAL_WORKFLOW','DOCUMENT_GENERATION','NOTIFICATIONS',
         'AUDIT_TRAIL','FINANCIAL_STATEMENTS','ANALYTICS','REPORTING',
         'INVENTORY','TIME_BILLING','FUNDS_GRANTS','FIXED_ASSETS','BANK_RECONCILIATION','TAX'))
);

CREATE INDEX IF NOT EXISTS idx_btm_business_type_code ON business_type_module (business_type_code);

-- 3. Champs additionnels obligatoires par type métier -------------------------------

CREATE TABLE IF NOT EXISTS business_type_required_field (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    business_type_code    VARCHAR(60) NOT NULL REFERENCES business_type(code) ON DELETE CASCADE,
    field_key             VARCHAR(60) NOT NULL,
    label                 VARCHAR(200) NOT NULL,
    field_type            VARCHAR(20) NOT NULL,
    required              BOOLEAN     NOT NULL DEFAULT TRUE,
    display_order         INT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_bt_required_field UNIQUE (business_type_code, field_key),
    CONSTRAINT chk_btrf_field_type CHECK (field_type IN
        ('STRING','NUMBER','DATE','BOOLEAN'))
);

CREATE INDEX IF NOT EXISTS idx_btrf_business_type_code ON business_type_required_field (business_type_code);

-- 4. Seed des types métier de base + mappings modules + champs requis ---------------
--    Équivalence approximative avec l'ancien mapping sectoriel :
--      COMMERCE  → RETAIL_COMMERCE (mêmes modules : INVENTORY, FIXED_ASSETS, BANK_RECONCILIATION, TAX)
--      SERVICE   → PROFESSIONAL_SERVICES (mêmes modules : TIME_BILLING, FIXED_ASSETS, BANK_RECONCILIATION, TAX)
--      ONG       → NGO_HUMANITARIAN (mêmes modules : FUNDS_GRANTS, FIXED_ASSETS, BANK_RECONCILIATION, TAX)
--      MIXTE     → CUSTOM (sélection manuelle à l'étape 8 du wizard — bug historique corrigé)

INSERT INTO business_type (code, label, default_organization_nature, default_sector, description, active) VALUES
    ('RETAIL_COMMERCE',        'Commerce de détail',            'FOR_PROFIT',    'COMMERCE',              'Boutique, supermarché, e-commerce, distribution de biens physiques.', true),
    ('PROFESSIONAL_SERVICES',  'Services professionnels',       'FOR_PROFIT',    'SERVICE',               'Cabinet de conseil, services IT, agence, freelance spécialisé.', true),
    ('NGO_HUMANITARIAN',       'ONG humanitaire',               'NON_PROFIT',    'ONG_HUMANITAIRE',       'Organisation non gouvernementale, projets financés par bailleurs.', true),
    ('ACCOUNTING_FIRM',        'Cabinet d''expertise comptable','FOR_PROFIT',    'CABINET_COMPTABLE',     'Cabinet comptable tenant la comptabilité de plusieurs clients.', true),
    ('SCHOOL',                 'École / établissement scolaire', 'NON_PROFIT',    'EDUCATION',             'Établissement d''enseignement (primaire, secondaire, supérieur).', true),
    ('HOSPITAL',               'Hôpital / clinique',            'NON_PROFIT',    'SANTE',                 'Établissement de santé, clinique, centre médical.', true),
    ('CUSTOM',                 'Personnalisé (sélection manuelle)', 'FOR_PROFIT', 'AUTRE',                 'Type métier générique — l''utilisateur sélectionne manuellement les modules à l''étape 8 du wizard. Remplace l''ancien secteur MIXTE.', true)
ON CONFLICT (code) DO NOTHING;

-- Mapping type métier → modules sectoriels (les always-on sont activés par CompanyService.completeWizard
-- indépendamment de cette table, conformément à SectorModuleMapping.alwaysOnModules() inchangé).
INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('RETAIL_COMMERCE',         'INVENTORY'),
    ('RETAIL_COMMERCE',         'FIXED_ASSETS'),
    ('RETAIL_COMMERCE',         'BANK_RECONCILIATION'),
    ('RETAIL_COMMERCE',         'TAX'),
    ('PROFESSIONAL_SERVICES',   'TIME_BILLING'),
    ('PROFESSIONAL_SERVICES',   'FIXED_ASSETS'),
    ('PROFESSIONAL_SERVICES',   'BANK_RECONCILIATION'),
    ('PROFESSIONAL_SERVICES',   'TAX'),
    ('NGO_HUMANITARIAN',        'FUNDS_GRANTS'),
    ('NGO_HUMANITARIAN',        'FIXED_ASSETS'),
    ('NGO_HUMANITARIAN',        'BANK_RECONCILIATION'),
    ('NGO_HUMANITARIAN',        'TAX'),
    ('ACCOUNTING_FIRM',         'TIME_BILLING'),
    ('ACCOUNTING_FIRM',         'FIXED_ASSETS'),
    ('ACCOUNTING_FIRM',         'BANK_RECONCILIATION'),
    ('ACCOUNTING_FIRM',         'TAX'),
    ('SCHOOL',                  'FIXED_ASSETS'),
    ('SCHOOL',                  'BANK_RECONCILIATION'),
    ('SCHOOL',                  'TAX'),
    ('HOSPITAL',                'FIXED_ASSETS'),
    ('HOSPITAL',                'BANK_RECONCILIATION'),
    ('HOSPITAL',                'TAX')
ON CONFLICT DO NOTHING;

-- Champs additionnels obligatoires par type métier. Le modèle est volontairement générique :
-- ajouter un champ = INSERT de référence, pas de modification de code + redéploiement.
INSERT INTO business_type_required_field (business_type_code, field_key, label, field_type, required, display_order) VALUES
    ('SCHOOL',                'ministry_approval_number',  'Numéro d''agrément ministériel',           'STRING', TRUE, 10),
    ('HOSPITAL',              'health_license_number',     'Numéro de licence sanitaire',             'STRING', TRUE, 10),
    ('ACCOUNTING_FIRM',       'professional_order_number', 'Numéro d''ordre professionnel',           'STRING', TRUE, 10),
    ('NGO_HUMANITARIAN',      'donor_reporting_currency',  'Devise de reporting bailleur (ISO 4217)', 'STRING', TRUE, 10)
ON CONFLICT DO NOTHING;
