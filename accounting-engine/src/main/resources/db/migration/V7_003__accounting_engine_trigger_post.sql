-- V7_003 — accounting-engine : trigger DB pour la transition DRAFT → POSTED (audit M3).
--
-- AUDIT M3 : avant cette correction, le trigger trg_journal_entry_balance était sur
-- journal_line (AFTER INSERT/UPDATE/DELETE), pas sur journal_entry. Quand on passait une
-- écriture DRAFT → POSTED via UPDATE journal_entry SET status='POSTED', le trigger ne se
-- déclenchait PAS — laissant passer une écriture déséquilibrée si le contrôle applicatif
-- était contourné (SQL direct, bug dans le service, etc.).
--
-- Cette migration ajoute un second trigger trg_journal_entry_balance_on_post qui se
-- déclenche AFTER UPDATE OF status ON journal_entry et vérifie l'équilibre débit=crédit
-- uniquement quand NEW.status = 'POSTED' (pas pour les autres transitions).
--
-- Défense en profondeur : le contrôle applicatif dans AccountingEngineService.postJournalEntry
-- reste le garde-fou principal, mais ce trigger garantit l'invariant au niveau DB même en
-- cas de contournement.

CREATE OR REPLACE FUNCTION check_journal_entry_balance_on_post()
RETURNS TRIGGER AS $$
DECLARE
    total_debit NUMERIC(19, 4);
    total_credit NUMERIC(19, 4);
BEGIN
    -- Uniquement quand on passe à POSTED (pas pour DRAFT, PENDING_APPROVAL, VOIDED)
    IF NEW.status <> 'POSTED' THEN
        RETURN NEW;
    END IF;
    -- Si on était déjà POSTED, pas besoin de re-vérifier (transition POSTED → VOIDED par ex.)
    IF OLD.status = 'POSTED' THEN
        RETURN NEW;
    END IF;

    SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0)
      INTO total_debit, total_credit
      FROM journal_line
     WHERE journal_entry_id = NEW.id;

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'Unbalanced journal entry % on POST : debit=%, credit=%',
            NEW.id, total_debit, total_credit
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Vérifier aussi qu'il y a au moins 2 lignes (règle métier : ENTRY_TOO_FEW_LINES)
    IF (SELECT count(*) FROM journal_line WHERE journal_entry_id = NEW.id) < 2 THEN
        RAISE EXCEPTION 'Journal entry % has fewer than 2 lines on POST',
            NEW.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_entry_balance_on_post ON journal_entry;
CREATE TRIGGER trg_journal_entry_balance_on_post
    AFTER UPDATE OF status ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balance_on_post();

COMMENT ON FUNCTION check_journal_entry_balance_on_post IS
    'Trigger de défense en profondeur (audit M3) : vérifie l''équilibre débit=crédit et le minimum de 2 lignes quand une JournalEntry passe à POSTED, même via SQL direct.';
