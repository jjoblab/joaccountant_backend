-- V1_003 — exchange_rate (Vague 2, item 2.5 — multi-devises actif).


CREATE TABLE IF NOT EXISTS exchange_rate (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    from_currency   CHAR(3)     NOT NULL,
    to_currency     CHAR(3)     NOT NULL,
    rate            NUMERIC(19, 6) NOT NULL,
    as_of_date      DATE        NOT NULL,
    source          VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_er_company_from_to_date UNIQUE (company_id, from_currency, to_currency, as_of_date),
    CONSTRAINT chk_er_rate CHECK (rate > 0)
);

CREATE INDEX IF NOT EXISTS idx_er_company_from_to ON exchange_rate (company_id, from_currency, to_currency, as_of_date);
