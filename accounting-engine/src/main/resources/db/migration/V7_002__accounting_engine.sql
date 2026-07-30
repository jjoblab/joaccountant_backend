-- V7_002 — accounting-engine (module :accounting-engine, §13 Phase 5).
--
-- Tables : fiscal_year, fiscal_period, journal, journal_entry, journal_line,
-- journal_line_analytical_tag.
--
-- Invariant DB : somme(débit) = somme(crédit) par écriture, vérifié par trigger.

CREATE TABLE IF NOT EXISTS fiscal_year (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id  UUID        NOT NULL,
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    label       VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_fy_company_dates UNIQUE (company_id, start_date, end_date),
    CONSTRAINT chk_fy_status CHECK (status IN ('OPEN','LOCKED','CLOSED')),
    CONSTRAINT chk_fy_dates CHECK (end_date > start_date)
);

CREATE INDEX IF NOT EXISTS idx_fy_company ON fiscal_year (company_id);

CREATE TABLE IF NOT EXISTS fiscal_period (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    fiscal_year_id  UUID        NOT NULL REFERENCES fiscal_year(id) ON DELETE CASCADE,
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    label           VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_fp_year_dates UNIQUE (fiscal_year_id, start_date, end_date),
    CONSTRAINT chk_fp_status CHECK (status IN ('OPEN','LOCKED')),
    CONSTRAINT chk_fp_dates CHECK (end_date >= start_date)
);

CREATE INDEX IF NOT EXISTS idx_fp_year ON fiscal_period (fiscal_year_id);
CREATE INDEX IF NOT EXISTS idx_fp_company_dates ON fiscal_period (company_id, start_date, end_date);

CREATE TABLE IF NOT EXISTS journal (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id  UUID        NOT NULL,
    code        VARCHAR(10) NOT NULL,
    label       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_journal_company_code UNIQUE (company_id, code)
);

CREATE INDEX IF NOT EXISTS idx_journal_company ON journal (company_id);

CREATE TABLE IF NOT EXISTS journal_entry (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id            UUID        NOT NULL,
    journal_id            UUID        NOT NULL REFERENCES journal(id) ON DELETE CASCADE,
    fiscal_period_id      UUID        NOT NULL REFERENCES fiscal_period(id),
    entry_date            DATE        NOT NULL,
    reference             VARCHAR(50),
    description           VARCHAR(500),
    status                VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    posted_at             TIMESTAMPTZ,
    posted_by             UUID,
    reversal_of_entry_id  UUID,
    source_module         VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    idempotency_key       VARCHAR(100) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_je_company_idempotency UNIQUE (company_id, idempotency_key),
    CONSTRAINT chk_je_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','POSTED','VOIDED')),
    CONSTRAINT chk_je_source_module CHECK (source_module IN ('MANUAL','FIXED_ASSETS','INVENTORY','INVOICING','FUNDS_GRANTS','REVERSAL'))
);

CREATE INDEX IF NOT EXISTS idx_je_company_status ON journal_entry (company_id, status);
CREATE INDEX IF NOT EXISTS idx_je_company_date ON journal_entry (company_id, entry_date);
CREATE INDEX IF NOT EXISTS idx_je_period ON journal_entry (fiscal_period_id);

CREATE TABLE IF NOT EXISTS journal_line (
    id                            UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                    UUID        NOT NULL,
    journal_entry_id              UUID        NOT NULL REFERENCES journal_entry(id) ON DELETE CASCADE,
    account_id                    UUID        NOT NULL,
    account_code                  VARCHAR(30) NOT NULL,
    third_party_id                UUID,
    debit                         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    credit                        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_number                   INT         NOT NULL,
    description                   VARCHAR(500),
    amount_transaction_currency   NUMERIC(19, 4),
    transaction_currency          VARCHAR(3),
    exchange_rate_used            NUMERIC(19, 6) NOT NULL DEFAULT 1,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                    UUID,
    updated_by                    UUID,
    version                       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_jl_debit_credit CHECK (debit >= 0 AND credit >= 0),
    CONSTRAINT chk_jl_exclusive CHECK (
        (debit > 0 AND credit = 0) OR (debit = 0 AND credit > 0) OR (debit = 0 AND credit = 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_jl_entry ON journal_line (journal_entry_id);
CREATE INDEX IF NOT EXISTS idx_jl_company_account ON journal_line (company_id, account_id);

CREATE TABLE IF NOT EXISTS journal_line_analytical_tag (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    journal_line_id         UUID        NOT NULL REFERENCES journal_line(id) ON DELETE CASCADE,
    plan_id                 UUID        NOT NULL,
    value_id                UUID        NOT NULL,
    allocation_percentage   NUMERIC(5, 2) NOT NULL,
    CONSTRAINT uc_jlat_line_plan_value UNIQUE (journal_line_id, plan_id, value_id),
    CONSTRAINT chk_jlat_percentage CHECK (allocation_percentage > 0 AND allocation_percentage <= 100)
);

CREATE INDEX IF NOT EXISTS idx_jlat_line ON journal_line_analytical_tag (journal_line_id);
CREATE INDEX IF NOT EXISTS idx_jlat_plan ON journal_line_analytical_tag (plan_id);

-- Trigger DB : somme(débit) = somme(crédit) par écriture POSTED.
-- Vérifie l'invariant à chaque INSERT/UPDATE sur journal_line.
-- Déclenche une erreur si l'équilibre est rompu — filet de sécurité en plus de la
-- vérification applicative (qui rejette les écritures déséquilibrées à la création).
CREATE OR REPLACE FUNCTION check_journal_entry_balance()
RETURNS TRIGGER AS $$
DECLARE
    total_debit NUMERIC(19, 4);
    total_credit NUMERIC(19, 4);
    entry_status VARCHAR(20);
BEGIN
    -- Récupérer le statut de l'écriture concernée
    SELECT status INTO entry_status FROM journal_entry WHERE id = COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);
    IF entry_status IS NULL THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    -- Le contrôle ne s'applique qu'aux écritures POSTED (les DRAFT peuvent être en cours de saisie)
    IF entry_status <> 'POSTED' THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0)
    INTO total_debit, total_credit
    FROM journal_line
    WHERE journal_entry_id = COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'Unbalanced journal entry % : debit=%, credit=%',
            COALESCE(NEW.journal_entry_id, OLD.journal_entry_id), total_debit, total_credit
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_entry_balance ON journal_line;
CREATE TRIGGER trg_journal_entry_balance
    AFTER INSERT OR UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balance();
