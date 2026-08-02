-- V17_004 — cost category mapping
-- V71 — v7-1 : table cost_category_mapping pour alimentation automatique de donor_report_line.
-- CONTEXTE : la validation PME3 (ONG Espwa pou Ayiti) a identifié que les exports bailleurs
-- (USAID SF-425, EU PRAG, BM) livrés en v6.3 retournent des zéros car la table
-- donor_report_line n'est jamais alimentée. Cette table de mapping permet de déduire
-- la cost_category d'une charge à partir du compte PCN (classe 6).
-- RÈGLE : pour une écriture POSTED sur un compte de charge (code LIKE '6%') avec un
-- analytical_tag pointant vers une subvention, la cost_category est résolue en cherchant
-- le pattern (account_code_pattern) qui matche le code du compte, par priorité décroissante.
-- Si plusieurs patterns matchent, le plus spécifique (longueur code + priority la plus haute)
-- gagne (ex: 641% bat 63% bat 6%).
-- company_id NULL = mapping global par défaut (utilisable par toutes les entreprises).
-- company_id non NULL = surcharge spécifique à l'entreprise (prioritaire sur le global).


CREATE TABLE IF NOT EXISTS cost_category_mapping (
    id                          UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID,           -- NULL = mapping global par défaut
    accounting_framework_code   VARCHAR(40)     NOT NULL,  -- PCN_HAITI, SYSCOHADA_REVISED, etc.
    account_code_pattern        VARCHAR(30)     NOT NULL,  -- ex: '63%', '61%', '60%'
    cost_category               VARCHAR(50)     NOT NULL,  -- PERSONNEL, FRINGE, TRAVEL, EQUIPMENT, SUPPLIES, CONTRACTUAL, OTHER, INDIRECT_COST
    priority                    INT             NOT NULL DEFAULT 0,
    active                      BOOLEAN         NOT NULL DEFAULT TRUE,
    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cost_category_mapping CHECK (
        cost_category IN ('PERSONNEL', 'FRINGE', 'TRAVEL', 'EQUIPMENT',
                          'SUPPLIES', 'CONTRACTUAL', 'OTHER', 'INDIRECT_COST')
    )
);

CREATE INDEX IF NOT EXISTS idx_cost_category_mapping_framework
    ON cost_category_mapping (accounting_framework_code, active);

CREATE INDEX IF NOT EXISTS idx_cost_category_mapping_company
    ON cost_category_mapping (company_id, accounting_framework_code, active);

-- Seeds par défaut pour PCN_HAITI (Code Fiscal Haïti — Plan Comptable National).
-- Les patterns sont matchés par ordre de priorité décroissante : un pattern plus spécifique
-- (ex: 641%) a une priority plus haute qu'un pattern générique (ex: 63%), de sorte que
-- pour un compte 641010, c'est PERSONNEL (priorité 20) qui gagne sur TRAVEL (priorité 10
-- pour le pattern 6% ou autre qui pourrait matcher).
INSERT INTO cost_category_mapping (id, company_id, accounting_framework_code, account_code_pattern, cost_category, priority, active, version) VALUES
    (uuidv7(), NULL, 'PCN_HAITI', '641%',   'PERSONNEL',     20, TRUE, 0),  -- rémunérations directes
    (uuidv7(), NULL, 'PCN_HAITI', '645%',   'FRINGE',        20, TRUE, 0),  -- charges sociales
    (uuidv7(), NULL, 'PCN_HAITI', '63%',    'PERSONNEL',     10, TRUE, 0),  -- personnel (générique)
    (uuidv7(), NULL, 'PCN_HAITI', '625%',   'TRAVEL',        20, TRUE, 0),  -- déplacements
    (uuidv7(), NULL, 'PCN_HAITI', '61%',    'TRAVEL',        10, TRUE, 0),  -- transports (générique)
    (uuidv7(), NULL, 'PCN_HAITI', '215%',   'EQUIPMENT',     20, TRUE, 0),  -- immobilisations matériel
    (uuidv7(), NULL, 'PCN_HAITI', '60%',    'SUPPLIES',      10, TRUE, 0),  -- achats
    (uuidv7(), NULL, 'PCN_HAITI', '62%',    'CONTRACTUAL',   10, TRUE, 0),  -- services extérieurs
    (uuidv7(), NULL, 'PCN_HAITI', '68%',    'INDIRECT_COST', 10, TRUE, 0)   -- dotations aux amortissements
ON CONFLICT DO NOTHING;

-- Seeds indicatifs pour SYSCOHADA_REVISED — à affiner avec un expert-comptable.
INSERT INTO cost_category_mapping (id, company_id, accounting_framework_code, account_code_pattern, cost_category, priority, active, version) VALUES
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '661%',  'PERSONNEL',     20, TRUE, 0),  -- rémunérations
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '66%',   'PERSONNEL',     10, TRUE, 0),  -- personnel (générique)
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '658%',  'FRINGE',        20, TRUE, 0),  -- charges sociales
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '62%',   'TRAVEL',        10, TRUE, 0),  -- transports
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '60%',   'SUPPLIES',      10, TRUE, 0),  -- achats
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '61%',   'CONTRACTUAL',   10, TRUE, 0),  -- services extérieurs
    (uuidv7(), NULL, 'SYSCOHADA_REVISED', '68%',   'INDIRECT_COST', 10, TRUE, 0)   -- dotations
ON CONFLICT DO NOTHING;

COMMENT ON TABLE cost_category_mapping IS
    'V71 — v7-1 : mapping compte-PCN → cost_category pour alimentation automatique de donor_report_line.';
