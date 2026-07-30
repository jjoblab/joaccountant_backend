-- V31 — fx-operations (module :fx-operations, restructuration 2026-07-24 suite 3).
-- Opérations en devises étrangères : achat/vente de devises + réévaluation de fin de période.
-- Génère des écritures comptables avec gain/perte de change.

CREATE TABLE IF NOT EXISTS fx_operation (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    type                VARCHAR(15) NOT NULL,
    from_currency       CHAR(3)     NOT NULL,
    to_currency         CHAR(3)     NOT NULL,
    from_amount         NUMERIC(19, 4) NOT NULL,
    to_amount           NUMERIC(19, 4) NOT NULL,
    rate                NUMERIC(19, 6) NOT NULL,
    -- Montants en devise fonctionnelle (HTG par défaut) — calculés via ExchangeRateService
    from_amount_functional NUMERIC(19, 4) NOT NULL,
    to_amount_functional   NUMERIC(19, 4) NOT NULL,
    fx_gain_loss        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    operation_date      DATE        NOT NULL,
    description         VARCHAR(500),
    journal_entry_id    UUID,
    reversal_of_id      UUID,
    status              VARCHAR(15) NOT NULL DEFAULT 'POSTED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_fxo_type CHECK (type IN ('BUY','SELL','REVALUATION')),
    CONSTRAINT chk_fxo_status CHECK (status IN ('POSTED','REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_fxo_company ON fx_operation (company_id);
CREATE INDEX IF NOT EXISTS idx_fxo_company_date ON fx_operation (company_id, operation_date);
CREATE INDEX IF NOT EXISTS idx_fxo_reversal ON fx_operation (reversal_of_id);
