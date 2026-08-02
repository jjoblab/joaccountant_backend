-- V1_002 — Seed data for AccountingFramework (§4) and Currency (§3.5).
-- These are reference data, not editable by users.


CREATE TABLE IF NOT EXISTS accounting_framework (
    id                        UUID        PRIMARY KEY DEFAULT uuidv7(),
    code                      VARCHAR(40) NOT NULL UNIQUE,
    numbering_mode            VARCHAR(20) NOT NULL,
    label                     VARCHAR(120) NOT NULL,
    mandated_class_seed_json  JSONB,
    mandatory_statements      VARCHAR(200),
    version                   BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS currency (
    code      CHAR(3)     PRIMARY KEY,
    label     VARCHAR(60) NOT NULL,
    decimals  INT         NOT NULL,
    version   BIGINT      NOT NULL DEFAULT 0
);

-- Reference UUIDs (stable, hardcoded so tests can refer to them by name)
-- IFRS_FULL:          00000000-0000-0000-0000-000000000001
-- IFRS_SME:           00000000-0000-0000-0000-000000000002
-- SYSCOHADA_REVISED:  00000000-0000-0000-0000-000000000003
-- PCG_FRANCE:         00000000-0000-0000-0000-000000000004
-- PCN_HAITI:          00000000-0000-0000-0000-000000000005
-- PCGR_CANADA:        00000000-0000-0000-0000-000000000006

INSERT INTO accounting_framework (id, code, numbering_mode, label, mandated_class_seed_json, mandatory_statements) VALUES
  ('00000000-0000-0000-0000-000000000001', 'IFRS_FULL',         'FREE',     'IFRS (full)',
     NULL,
     'BALANCE_SHEET,INCOME_STATEMENT,STATEMENT_OF_CASH_FLOWS,STATEMENT_OF_CHANGES_IN_EQUITY'),
  ('00000000-0000-0000-0000-000000000002', 'IFRS_SME',          'FREE',     'IFRS for SMEs',
     NULL,
     'BALANCE_SHEET,INCOME_STATEMENT'),
  ('00000000-0000-0000-0000-000000000003', 'SYSCOHADA_REVISED', 'MANDATED', 'SYSCOHADA révisé (OHADA)',
     '[{"class":"1","label":"Ressources durables"},{"class":"2","label":"Actifs immobilisés"},{"class":"3","label":"Stocks"},{"class":"4","label":"Tiers"},{"class":"5","label":"Trésorerie"},{"class":"6","label":"Charges des activités ordinaires"},{"class":"7","label":"Produits des activités ordinaires"},{"class":"8","label":"Autres charges et autres produits (HAO)"}]'::jsonb,
     'BALANCE_SHEET,INCOME_STATEMENT,TAFIRE'),
  ('00000000-0000-0000-0000-000000000004', 'PCG_FRANCE',        'MANDATED', 'Plan comptable général (France)',
     '[{"class":"1","label":"Comptes de capitaux"},{"class":"2","label":"Comptes d''immobilisations"},{"class":"3","label":"Comptes de stocks et en-cours"},{"class":"4","label":"Comptes de tiers"},{"class":"5","label":"Comptes financiers"},{"class":"6","label":"Charges"},{"class":"7","label":"Produits"}]'::jsonb,
     'BALANCE_SHEET,INCOME_STATEMENT'),
  ('00000000-0000-0000-0000-000000000005', 'PCN_HAITI',         'MANDATED', 'Plan comptable national (Haïti)',
     '[{"class":"1","label":"Comptes de capitaux"},{"class":"2","label":"Comptes d''immobilisations"},{"class":"3","label":"Comptes de stocks"},{"class":"4","label":"Comptes de tiers"},{"class":"5","label":"Comptes financiers"},{"class":"6","label":"Charges"},{"class":"7","label":"Produits"},{"class":"8","label":"Comptes spéciaux"}]'::jsonb,
     'BALANCE_SHEET,INCOME_STATEMENT'),
  ('00000000-0000-0000-0000-000000000006', 'PCGR_CANADA',       'MANDATED', 'Plan comptable pour les SJC (Canada)',
     '[{"class":"1","label":"Actif à court terme"},{"class":"2","label":"Actif à long terme"},{"class":"3","label":"Dettes à court terme"},{"class":"4","label":"Dettes à long terme"},{"class":"5","label":"Avoir des actionnaires"},{"class":"6","label":"Produits"},{"class":"7","label":"Charges"},{"class":"8","label":"Impôts sur les bénéfices"}]'::jsonb,
     'BALANCE_SHEET,INCOME_STATEMENT')
ON CONFLICT (code) DO NOTHING;

-- Currencies (subset relevant to the three target geographies)
INSERT INTO currency (code, label, decimals) VALUES
  ('HTG', 'Gourde haïtienne',  2),
  ('USD', 'Dollar américain',  2),
  ('EUR', 'Euro',              2),
  ('XOF', 'Franc CFA (BCEAO)', 0),
  ('XAF', 'Franc CFA (BEAC)',  0),
  ('CAD', 'Dollar canadien',   2),
  ('JPY', 'Yen japonais',      0)
ON CONFLICT (code) DO NOTHING;
