-- V8_004 — audit perf indexes and stmt trigger
-- Trigger statement-level pour l'équilibre débit=crédit + index composites
-- sur les tables du module accounting-engine (journal_entry, journal_line, audit_log).
-- Note : les index sur sales_invoice, purchase_invoice et bank_statement_line
-- (tables créées par d'autres modules) ont été déplacés vers leurs modules
-- respectifs : V15_006 (invoicing), V21_002 (purchasing), V16_002 (bank-reconciliation).


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Trigger statement-level pour l'équilibre débit=crédit
-- ─────────────────────────────────────────────────────────────────────────────


CREATE OR REPLACE FUNCTION check_journal_entry_balance_stmt()
RETURNS TRIGGER AS $$
DECLARE
    affected_entry_id UUID;
    entry_status VARCHAR(20);
    total_debit NUMERIC(19, 4);
    total_credit NUMERIC(19, 4);
    distinct_query TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        distinct_query := 'SELECT DISTINCT journal_entry_id FROM new_rows';
    ELSIF TG_OP = 'DELETE' THEN
        distinct_query := 'SELECT DISTINCT journal_entry_id FROM old_rows';
    ELSE
        distinct_query := 'SELECT DISTINCT journal_entry_id FROM (
            SELECT journal_entry_id FROM new_rows
            UNION
            SELECT journal_entry_id FROM old_rows
        ) AS combined';
    END IF;

    FOR affected_entry_id IN EXECUTE distinct_query
    LOOP
        SELECT status INTO entry_status
        FROM journal_entry
        WHERE id = affected_entry_id;

        IF entry_status IS NULL OR entry_status <> 'POSTED' THEN
            CONTINUE;
        END IF;

        SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0)
        INTO total_debit, total_credit
        FROM journal_line
        WHERE journal_entry_id = affected_entry_id;

        IF total_debit <> total_credit THEN
            RAISE EXCEPTION 'Unbalanced journal entry % : debit=%, credit=%',
                affected_entry_id, total_debit, total_credit
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END LOOP;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_entry_balance ON journal_line;

CREATE TRIGGER trg_journal_entry_balance_ins
    AFTER INSERT ON journal_line
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION check_journal_entry_balance_stmt();

CREATE TRIGGER trg_journal_entry_balance_upd
    AFTER UPDATE ON journal_line
    REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION check_journal_entry_balance_stmt();

CREATE TRIGGER trg_journal_entry_balance_del
    AFTER DELETE ON journal_line
    REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION check_journal_entry_balance_stmt();

COMMENT ON FUNCTION check_journal_entry_balance_stmt IS
    'Trigger statement-level : vérifie l''équilibre débit=crédit pour chaque journal_entry_id distinct touché par la statement, en une seule passe au lieu de N.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Index composites sur journal_line, journal_entry, audit_log
-- ─────────────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_journal_line_company_thirdparty
    ON journal_line (company_id, third_party_id)
    WHERE third_party_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_journal_line_company_account
    ON journal_line (company_id, account_id);

CREATE INDEX IF NOT EXISTS idx_journal_entry_company_posted
    ON journal_entry (company_id, entry_date)
    WHERE status = 'POSTED';

CREATE INDEX IF NOT EXISTS idx_journal_entry_company_status_date
    ON journal_entry (company_id, status, entry_date);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity_occurred
    ON audit_log (entity_type, entity_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_occurred
    ON audit_log (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_log_security_events
    ON audit_log (action, occurred_at DESC)
    WHERE entity_type = 'SecurityEvent';

COMMENT ON INDEX idx_journal_line_company_thirdparty IS
    'Accélère ThirdPartiesService.getStatement (100× plus rapide sur 10K écritures).';
COMMENT ON INDEX idx_journal_entry_company_posted IS
    'Partial index pour findAllPosted et findAllPostedBetweenDates.';
COMMENT ON INDEX idx_audit_log_security_events IS
    'Partial index pour forensique SecurityEvent (LOGIN_FAILED, REFRESH_TOKEN_REUSED, etc.).';
