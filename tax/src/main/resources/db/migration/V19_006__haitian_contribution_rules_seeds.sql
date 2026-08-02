-- V19_006 — haitian contribution rules seeds
-- V57 — Lot B — Seeds ContributionRule Haïti (CNSS, OFATMA, AST).
-- :
-- - L'enum ContributionRule.HT_GENERAL existait (V40) mais aucune règle n'était seedée.
-- - Une entreprise haïtienne ne voyait aucune cotisation sociale par défaut et devait saisir
-- manuellement CNSS, OFATMA et AST pour chaque employé — configuration fastidieuse et
-- source d'erreurs (taux erronés, plafonds oubliés).
-- - Le régime haïtien comporte 5 cotisations distinctes :
-- * CNSS Employeur (6%, base plafonnée à 6×SMG = 150 000 HTG/mois en 2024)
-- * CNSS Salarié (6%, même plafond)
-- * OFATMA Santé Employeur (3%, base brute sans plafond)
-- * OFATMA Santé Salarié (1%, base brute)
-- * OFATMA Accidents (2% secteur default, variable 0.5%-6% selon secteur — base brute)
-- * AST (Ajustement Social Temporaire) — barème progressif 0%/1%/2%/3% par tranches
-- (indiciel 2024, à valider par arrêté ministériel)
-- APPROCHE :
-- 1. ALTER contribution_rule.company_id pour autoriser NULL (règles globales par pays,
-- comme pour tax_rule). La contrainte UNIQUE (company_id, code) reste valable —
-- PostgreSQL traite les NULL comme distincts, donc (NULL, 'CNSS_HT_EMPL') n'entre
-- pas en conflit avec un (companyId, 'CNSS_HT_EMPL') spécifique à une entreprise.
-- 2. ALTER pour ajouter bracket_type + brackets_json sur contribution_rule (cf. V46 qui
-- faisait la même chose sur withholding_rule). Cela permet de modéliser l'AST comme
-- un barème progressif par tranches.
-- 3. Seed de 6 ContributionRule globales Haïti (company_id IS NULL, regime=HT_GENERAL).
-- Toutes sont actives par défaut ; l'entreprise peut les surcharger par des règles
-- spécifiques (companyId non-null).
-- Notes :
-- - Les taux sont indicatifs 2024 (à valider par expert-comptable DGI / OFATMA).
-- - monthlyCeiling CNSS = 150 000 HTG (6 × SMG 25 000 HTG/mois en 2024).
-- - OFATMA Accidents taux variable selon secteur (0.5%-6%) — défaut 2% (sector default).
-- Le code secteur OFATMA est stocké sur l'employé (employee.ofatma_sector_code, V58).

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Rendre company_id nullable pour autoriser les règles globales par pays
-- ─────────────────────────────────────────────────────────────────────────────


ALTER TABLE contribution_rule ALTER COLUMN company_id DROP NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Ajouter bracket_type + brackets_json (comme V46 sur withholding_rule)
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE contribution_rule
    ADD COLUMN IF NOT EXISTS bracket_type VARCHAR(15) NOT NULL DEFAULT 'FLAT';

UPDATE contribution_rule SET bracket_type = 'FLAT' WHERE bracket_type IS NULL;

ALTER TABLE contribution_rule
    DROP CONSTRAINT IF EXISTS chk_contribution_rule_bracket_type;
ALTER TABLE contribution_rule
    ADD CONSTRAINT chk_contribution_rule_bracket_type CHECK (bracket_type IN ('FLAT', 'PROGRESSIVE'));

ALTER TABLE contribution_rule
    ADD COLUMN IF NOT EXISTS brackets_json JSONB;

COMMENT ON COLUMN contribution_rule.bracket_type IS
    'V57 — Lot B R-25 : FLAT=défaut (rate×assiette/100), PROGRESSIVE=barème par tranches (brackets_json).';
COMMENT ON COLUMN contribution_rule.brackets_json IS
    'V57 — Lot B R-25 : barème progressif par tranches [{threshold,rate}]. Utilisé si bracket_type=PROGRESSIVE (ex: AST Haïti).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Seeds — 6 ContributionRule globales Haïti (company_id IS NULL, regime=HT_GENERAL)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO contribution_rule (id, company_id, code, label, regime, contribution_type, rate,
                                base_type, abatement_rate, monthly_ceiling, ceiling_multiplier,
                                bracket_type, brackets_json, active, tax_mapping_code, version)
VALUES
    -- CNSS Employeur — 6%, base plafonnée à 150 000 HTG (6 × SMG 25 000 HTG/mois 2024)
    (uuidv7(), NULL, 'CNSS_HT_EMPL',
     'CNSS Employeur Haïti — 6% (base plafonnée 6×SMG)',
     'HT_GENERAL', 'EMPLOYER', 6.0000,
     'CAPPED_GROSS', 99.9999, 150000.0000, NULL,
     'FLAT', NULL, TRUE, 'SOCIAL_SECURITY_PAYABLE', 0),

    -- CNSS Salarié — 6%, même plafond
    (uuidv7(), NULL, 'CNSS_HT_SAL',
     'CNSS Salarié Haïti — 6% (base plafonnée 6×SMG)',
     'HT_GENERAL', 'EMPLOYEE', 6.0000,
     'CAPPED_GROSS', 99.9999, 150000.0000, NULL,
     'FLAT', NULL, TRUE, 'SOCIAL_SECURITY_PAYABLE', 0),

    -- OFATMA Santé Employeur — 3%, base brute sans plafond
    (uuidv7(), NULL, 'OFATMA_HT_HEALTH_EMPL',
     'OFATMA Santé Employeur Haïti — 3% (base brute)',
     'HT_GENERAL', 'EMPLOYER', 3.0000,
     'GROSS', 99.9999, NULL, NULL,
     'FLAT', NULL, TRUE, 'SOCIAL_SECURITY_PAYABLE', 0),

    -- OFATMA Santé Salarié — 1%, base brute
    (uuidv7(), NULL, 'OFATMA_HT_HEALTH_SAL',
     'OFATMA Santé Salarié Haïti — 1% (base brute)',
     'HT_GENERAL', 'EMPLOYEE', 1.0000,
     'GROSS', 99.9999, NULL, NULL,
     'FLAT', NULL, TRUE, 'SOCIAL_SECURITY_PAYABLE', 0),

    -- OFATMA Accidents — 2% secteur default (variable 0.5%-6% selon secteur)
    (uuidv7(), NULL, 'OFATMA_HT_ACCIDENT',
     'OFATMA Accidents Haïti — 2% secteur default (0.5%-6% selon secteur)',
     'HT_GENERAL', 'EMPLOYER', 2.0000,
     'GROSS', 99.9999, NULL, NULL,
     'FLAT', NULL, TRUE, 'SOCIAL_SECURITY_PAYABLE', 0),

    -- AST (Ajustement Social Temporaire) — barème progressif 0%/1%/2%/3% par tranches
    -- Barème indicatif 2024 — à valider par arrêté ministériel
    (uuidv7(), NULL, 'AST_HT',
     'AST Haïti — Ajustement Social Temporaire (barème progressif)',
     'HT_GENERAL', 'EMPLOYEE', 0.0000,
     'GROSS', 99.9999, NULL, NULL,
     'PROGRESSIVE',
     '[{"threshold":0,"rate":0},{"threshold":50000,"rate":1},{"threshold":100000,"rate":2},{"threshold":150000,"rate":3}]',
     TRUE, 'SOCIAL_SECURITY_PAYABLE', 0)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE contribution_rule IS
    'V57 — Lot B R-25 : company_id NULLABLE (règles globales par pays) + bracket_type/brackets_json + seeds Haïti (CNSS/OFATMA/AST).';
