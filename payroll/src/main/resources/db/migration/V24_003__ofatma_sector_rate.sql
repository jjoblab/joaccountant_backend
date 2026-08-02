-- V24_003 — ofatma sector rate
-- V78 — v7-6 : Table ofatma_sector_rate (taux OFATMA Accidents par secteur).
-- CONTEXTE : la v5.5 a ajouté le champ Employee.ofatmaSectorCode mais PayrollCalculator
-- ne l'utilise pas — toujours taux fixe 2%. Or le taux OFATMA Accidents varie de 0,5% à 6%
-- selon le secteur d'activité (Loi OFATMA).
-- CORRECTION : créer une table de mapping ofatma_sector_rate (sector_code → accident_rate)
-- et modifier PayrollCalculator pour résoudre le taux dynamiquement.
-- La ContributionRule existante OFATMA_HT_ACCIDENT (V57) reste comme fallback (taux 2%)
-- pour les employés sans sector_code ou pour les sector_code non trouvés dans la table.


CREATE TABLE IF NOT EXISTS ofatma_sector_rate (
    id                  UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    sector_code         VARCHAR(10)     NOT NULL UNIQUE,
    sector_label        VARCHAR(200)    NOT NULL,
    accident_rate       NUMERIC(5, 2)   NOT NULL,  -- ex: 0.50, 2.00, 6.00
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ofatma_sector_rate CHECK (accident_rate >= 0 AND accident_rate <= 100)
);

-- Seeds indicatifs — À VALIDER AVEC OFATMA (Office d'Accidents du Travail, Haïti).
-- Les taux sont indicatifs basés sur la classification internationale (ILO Code of Practice).
INSERT INTO ofatma_sector_rate (id, sector_code, sector_label, accident_rate, active, version) VALUES
    (uuidv7(), 'GEN',     'Général (défaut)',                  2.00, TRUE, 0),
    (uuidv7(), 'AGRI',    'Agriculture',                        3.50, TRUE, 0),
    (uuidv7(), 'CONST',   'Construction',                       6.00, TRUE, 0),
    (uuidv7(), 'MANUF',   'Manufacture / Industrie',            3.00, TRUE, 0),
    (uuidv7(), 'TEXT',    'Textile (zone franche)',             2.50, TRUE, 0),
    (uuidv7(), 'TRADE',   'Commerce de détail',                 1.50, TRUE, 0),
    (uuidv7(), 'BANK',    'Banque / Finance',                   0.50, TRUE, 0),
    (uuidv7(), 'TELECOM', 'Télécommunications',                 1.00, TRUE, 0),
    (uuidv7(), 'TRANSP',  'Transport',                          4.50, TRUE, 0),
    (uuidv7(), 'HEALTH',  'Santé',                              2.00, TRUE, 0),
    (uuidv7(), 'EDU',     'Éducation',                          1.00, TRUE, 0),
    (uuidv7(), 'TOUR',    'Tourisme / Hôtellerie',              2.50, TRUE, 0),
    (uuidv7(), 'MINING',  'Mines',                              5.50, TRUE, 0),
    (uuidv7(), 'NGO',     'ONG / Humanitaire',                  1.50, TRUE, 0)
ON CONFLICT (sector_code) DO NOTHING;

COMMENT ON TABLE ofatma_sector_rate IS
    'V78 — v7-6 : taux OFATMA Accidents par secteur (Loi OFATMA). Taux indicatifs — à valider avec OFATMA.';
