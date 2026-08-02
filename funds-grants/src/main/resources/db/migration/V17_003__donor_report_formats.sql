-- V17_003 — donor report formats
-- V69 — funds-grants : formats bailleurs structurés (USAID SF-425, EU PRAG, Banque Mondiale).
-- CONTEXTE : validation PME3 (Mme Nadège Saintilus, ONG Espwa pou Ayiti) — gap BLOQUANT.
-- Le module :funds-grants expose un DonorReport DTO générique
-- {totalReceived, totalSpent, balanceRemaining, from, to} qui ne permet PAS :
-- - la ventilation par ligne budgétaire (cost category),
-- - l'actual vs budget (variance),
-- - le cost share / match (participation ONG),
-- - la génération de formats structurés conformes aux exigences des bailleurs
-- institutionnels (USAID SF-425, EU PRAG, World Bank Quarterly Financial Report).
-- Pour une ONG multiprojets/multibailleurs (5M USD/an avec 4 bailleurs aux formats
-- incompatibles), l'équipe finance devrait refaire le reporting à la main dans Excel —
-- le SaaS n'économise pas de temps.
-- CORRECTION v6-3 : table `donor_report_line` qui stocke, par (grant, année, trimestre,
-- cost_category), les montants budget / actual / cost_share. Le service DonorReportExporter
-- agrège ces lignes pour produire les CSV structurés conformes aux formats bailleurs.
-- ÉTAT D'AVANCEMENT : squelette. L'alimentation réelle des lignes
-- (depuis les écritures comptables taguées par grant + cost_category) sera implémentée en v7
-- via un job de ventilation post-écriture. En attendant, les exports retournent des zéros
-- mais avec une structure CSV valide — les équipes finance peuvent déjà valider le format.
-- NOTE : `variance_amount` est une colonne GENERATED ALWAYS AS STORED — déduite de
-- budget_amount - actual_amount. Aucune écriture explicite nécessaire (CoVE best practice).


CREATE TABLE IF NOT EXISTS donor_report_line (
    id                  UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID            NOT NULL,
    grant_id            UUID            NOT NULL,
    donor_type          VARCHAR(20)     NOT NULL,  -- USAID, EU, WORLD_BANK, CRS, OTHER
    period_year         INT             NOT NULL,
    period_quarter      INT,                       -- 1-4 pour trimestriel, NULL pour annuel
    cost_category       VARCHAR(50)     NOT NULL,  -- PERSONNEL, FRINGE, TRAVEL, EQUIPMENT, SUPPLIES, CONTRACTUAL, OTHER, INDIRECT_COST
    budget_amount       NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    actual_amount       NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    variance_amount     NUMERIC(19, 4)  GENERATED ALWAYS AS (budget_amount - actual_amount) STORED,
    cost_share_amount   NUMERIC(19, 4)  NOT NULL DEFAULT 0,  -- participation ONG
    description         VARCHAR(500),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT chk_donor_report_line_donor CHECK (donor_type IN ('USAID', 'EU', 'WORLD_BANK', 'CRS', 'OTHER')),
    CONSTRAINT chk_donor_report_line_category CHECK (cost_category IN ('PERSONNEL', 'FRINGE', 'TRAVEL', 'EQUIPMENT', 'SUPPLIES', 'CONTRACTUAL', 'OTHER', 'INDIRECT_COST')),
    CONSTRAINT chk_donor_report_line_quarter CHECK (period_quarter IS NULL OR period_quarter BETWEEN 1 AND 4),
    CONSTRAINT chk_donor_report_line_year CHECK (period_year BETWEEN 1900 AND 2999)
);

-- Index pour les requêtes courantes du service DonorReportExporter :
--   - agrégation par (grant, year) pour un export donné
--   - agrégation par (company, donor, year) pour vue consolidée
--   - agrégation par (company, donor, year, quarter) pour exports trimestriels
CREATE INDEX IF NOT EXISTS idx_donor_report_line_grant
    ON donor_report_line (grant_id, period_year, period_quarter);

CREATE INDEX IF NOT EXISTS idx_donor_report_line_donor
    ON donor_report_line (company_id, donor_type, period_year);

CREATE INDEX IF NOT EXISTS idx_donor_report_line_company
    ON donor_report_line (company_id);

COMMENT ON TABLE donor_report_line IS
    'Lignes de rapport bailleur ventilées par cost category (v6-3 — formats USAID SF-425, EU PRAG, Banque Mondiale). Le squelette existe ; l''alimentation réelle depuis les écritures comptables taguées est à implémenter en v7.';

COMMENT ON COLUMN donor_report_line.donor_type IS
    'Type de bailleur : USAID, EU, WORLD_BANK, CRS, OTHER. Détermine le format d''export attendu.';

COMMENT ON COLUMN donor_report_line.cost_category IS
    'Catégorie de coût standardisée : PERSONNEL, FRINGE, TRAVEL, EQUIPMENT, SUPPLIES, CONTRACTUAL, OTHER, INDIRECT_COST. Alignée sur USAID SF-425 Section B et Banque Mondiale categories.';

COMMENT ON COLUMN donor_report_line.variance_amount IS
    'Colonne GENERATED ALWAYS AS STORED = budget_amount - actual_amount. Jamais écrite directement.';

COMMENT ON COLUMN donor_report_line.cost_share_amount IS
    'Participation de l''ONG (cost share / match funding). Pour USAID SF-425 Section A Line 10h-10i, pour EU PRAG co-financing, pour World Bank borrower contribution.';
