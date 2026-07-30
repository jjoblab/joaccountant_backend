-- V34 — Catalogue SERVICE (3 nouvelles entrées).
-- Même mapping modules que PROFESSIONAL_SERVICES (déjà présent dans V23).

INSERT INTO business_type (code, label, default_organization_nature, default_sector, description, active) VALUES
    ('IT_CONSULTING',         'Conseil et services informatiques',          'FOR_PROFIT', 'SERVICE',
     'Conseil IT, développement logiciel, intégration de systèmes, infogérance.', true),
    ('CREATIVE_AGENCY',       'Agence créative, marketing et communication', 'FOR_PROFIT', 'SERVICE',
     'Agence de communication, design, marketing digital, production audiovisuelle.', true),
    ('MAINTENANCE_SERVICES',  'Services de maintenance et réparation',       'FOR_PROFIT', 'SERVICE',
     'Maintenance industrielle, réparation d''équipements, services techniques.', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO business_type_module (business_type_code, module_code) VALUES
    ('IT_CONSULTING',         'TIME_BILLING'),
    ('IT_CONSULTING',         'FIXED_ASSETS'),
    ('IT_CONSULTING',         'BANK_RECONCILIATION'),
    ('IT_CONSULTING',         'TAX'),
    ('IT_CONSULTING',         'PURCHASING'),
    ('CREATIVE_AGENCY',       'TIME_BILLING'),
    ('CREATIVE_AGENCY',       'FIXED_ASSETS'),
    ('CREATIVE_AGENCY',       'BANK_RECONCILIATION'),
    ('CREATIVE_AGENCY',       'TAX'),
    ('CREATIVE_AGENCY',       'PURCHASING'),
    ('MAINTENANCE_SERVICES',  'TIME_BILLING'),
    ('MAINTENANCE_SERVICES',  'FIXED_ASSETS'),
    ('MAINTENANCE_SERVICES',  'BANK_RECONCILIATION'),
    ('MAINTENANCE_SERVICES',  'TAX'),
    ('MAINTENANCE_SERVICES',  'PURCHASING')
ON CONFLICT DO NOTHING;
