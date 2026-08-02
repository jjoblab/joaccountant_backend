-- V24_001 — payroll
-- V27 — payroll (module :payroll, — 4 nouveaux modules bonus).
-- Cycle de vie : DRAFT → CALCULATED → APPROVED → PAID → CLOSED. Un Payslip par employé ACTIVE.


CREATE TABLE IF NOT EXISTS payroll_run (
    id                              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                      UUID        NOT NULL,
    period_month                    INT         NOT NULL,
    period_year                     INT         NOT NULL,
    status                          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    total_gross                     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_net                       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_employer_contributions    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    journal_entry_id                UUID,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                      UUID,
    updated_by                      UUID,
    version                         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_pr_status CHECK (status IN ('DRAFT','CALCULATED','APPROVED','PAID','CLOSED')),
    CONSTRAINT chk_pr_period CHECK (period_month >= 1 AND period_month <= 12 AND period_year >= 2000)
);

CREATE INDEX IF NOT EXISTS idx_pr_company ON payroll_run (company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uc_pr_company_period ON payroll_run (company_id, period_year, period_month);

CREATE TABLE IF NOT EXISTS payslip (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID        NOT NULL,
    run_id                      UUID        NOT NULL REFERENCES payroll_run(id) ON DELETE CASCADE,
    employee_id                 UUID        NOT NULL,
    gross_salary                NUMERIC(19, 4) NOT NULL,
    deductions                  JSONB,
    employer_contributions      JSONB,
    net_pay                     NUMERIC(19, 4) NOT NULL,
    payslip_number              VARCHAR(50),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ps_run ON payslip (run_id);
CREATE INDEX IF NOT EXISTS idx_ps_company ON payslip (company_id);
CREATE INDEX IF NOT EXISTS idx_ps_employee ON payslip (employee_id);
