-- V6_001 — approval-workflow (module :approval-workflow, §7 + §13 Phase 4).
-- Deux tables :
-- - approval_rule : règle de seuil pour un actionType donné. Une règle active par
-- (company_id, action_type) — contrainte unique partielle sur active=true.
-- - approval_request : demande d'approbation créée par evaluate() quand le montant dépasse
-- le seuil. PENDING → APPROVED / REJECTED / CANCELLED.


CREATE TABLE IF NOT EXISTS approval_rule (
    id                          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id                  UUID        NOT NULL,
    action_type                 VARCHAR(40) NOT NULL,
    threshold_amount            NUMERIC(19, 4) NOT NULL,
    required_approver_roles     JSONB       NOT NULL,
    min_approvals               INT         NOT NULL DEFAULT 1,
    active                      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_approval_rule_action_type CHECK (action_type IN ('JOURNAL_ENTRY_POST','INVOICE_ISSUE','GRANT_DISBURSEMENT_PROPOSAL')),
    CONSTRAINT chk_approval_rule_threshold   CHECK (threshold_amount >= 0),
    CONSTRAINT chk_approval_rule_min         CHECK (min_approvals >= 1)
);

-- Index pour la recherche rapide d'une règle active par (companyId, actionType)
CREATE INDEX IF NOT EXISTS idx_approval_rule_company_active_type
    ON approval_rule (company_id, action_type) WHERE active = TRUE;

-- Contrainte unique partielle : une seule règle active par (companyId, actionType).
-- Implémentée via un UNIQUE INDEX partiel (PostgreSQL ne supporte pas UNIQUE sur expression
-- dans la définition de table).
CREATE UNIQUE INDEX IF NOT EXISTS uc_approval_rule_active
    ON approval_rule (company_id, action_type) WHERE active = TRUE;

CREATE TABLE IF NOT EXISTS approval_request (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    action_type     VARCHAR(40) NOT NULL,
    resource_type   VARCHAR(60) NOT NULL,
    resource_id     UUID        NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    requested_by    UUID        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL,
    status          VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    decided_by      UUID,
    decided_at      TIMESTAMPTZ,
    comment         VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_approval_request_action_type CHECK (action_type IN ('JOURNAL_ENTRY_POST','INVOICE_ISSUE','GRANT_DISBURSEMENT_PROPOSAL')),
    CONSTRAINT chk_approval_request_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT chk_approval_request_amount CHECK (amount >= 0),
    -- Si status est terminal (APPROVED/REJECTED/CANCELLED), decided_by et decided_at sont requis
    CONSTRAINT chk_approval_request_decision CHECK (
        (status = 'PENDING') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_approval_request_company_status ON approval_request (company_id, status);
CREATE INDEX IF NOT EXISTS idx_approval_request_company_resource ON approval_request (company_id, resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_approval_request_requested_by ON approval_request (requested_by);
