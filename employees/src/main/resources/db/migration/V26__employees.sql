-- V26 — employees (module :employees, restructuration 2026-07-24 — 4 nouveaux modules bonus).
-- Fiche employé rattachée à un ThirdParty de type EMPLOYEE. Pas d'écriture comptable.

CREATE TABLE IF NOT EXISTS employee (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    third_party_id          UUID        NOT NULL,
    employee_number         VARCHAR(50) NOT NULL,
    position                VARCHAR(200),
    department              VARCHAR(100),
    hire_date               DATE        NOT NULL,
    termination_date        DATE,
    base_salary             NUMERIC(19, 4) NOT NULL,
    salary_currency         CHAR(3)     NOT NULL DEFAULT 'HTG',
    contract_type           VARCHAR(20) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    bank_account_number     VARCHAR(50),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_emp_contract CHECK (contract_type IN ('PERMANENT','FIXED_TERM','CONSULTANT')),
    CONSTRAINT chk_emp_status CHECK (status IN ('ACTIVE','ON_LEAVE','TERMINATED'))
);

CREATE INDEX IF NOT EXISTS idx_emp_company ON employee (company_id);
CREATE INDEX IF NOT EXISTS idx_emp_company_status ON employee (company_id, status);
CREATE INDEX IF NOT EXISTS idx_emp_third_party ON employee (third_party_id);
CREATE UNIQUE INDEX IF NOT EXISTS uc_emp_company_number ON employee (company_id, employee_number);
