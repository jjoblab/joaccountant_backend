-- V70 — exchange_rate_snapshot (Task v6-4-presentation-currency).
-- Table pour stocker le taux de change officiel à utiliser pour la présentation
-- des états financiers (DCR DGI Haïti en HTG depuis comptabilité USD — PME3 ONG + PME4 zone franche).
--
-- Deux types de snapshot :
--   * CLOSING       : taux à la date de clôture (bilan). period_year/period_month = NULL.
--   * PERIOD_AVERAGE : taux moyen sur une période (compte de résultat). period_year/period_month renseignés.
--
-- Source typique : BRH (Banque de la République d'Haïti), COMMERCIAL (banque commerciale), MANUAL.
--
-- Compatibilité TenantAwareEntity : colonnes company_id, created_at, updated_at, created_by,
-- updated_by, version présentes (voir pattern V31 fx_operation).

CREATE TABLE IF NOT EXISTS exchange_rate_snapshot (
    id                  UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID            NOT NULL,
    from_currency       CHAR(3)         NOT NULL,  -- ex : USD
    to_currency         CHAR(3)         NOT NULL,  -- ex : HTG
    rate                NUMERIC(19, 6)  NOT NULL,
    rate_date           DATE            NOT NULL,
    source              VARCHAR(50)     NOT NULL DEFAULT 'BRH',  -- BRH (Banque Rép. Haïti), COMMERCIAL, MANUAL
    snapshot_type       VARCHAR(20)     NOT NULL DEFAULT 'CLOSING',  -- CLOSING (clôture), PERIOD_AVERAGE (moyenne période)
    period_year         INT,                       -- pour PERIOD_AVERAGE
    period_month        INT,                       -- pour PERIOD_AVERAGE (1-12)
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uc_exchange_rate_snapshot UNIQUE (company_id, from_currency, to_currency, rate_date, snapshot_type, period_year, period_month)
);

CREATE INDEX IF NOT EXISTS idx_exchange_rate_snapshot_company_date
    ON exchange_rate_snapshot (company_id, rate_date DESC);
