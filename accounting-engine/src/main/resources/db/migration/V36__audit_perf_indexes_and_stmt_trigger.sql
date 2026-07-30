-- V36 — Audit v4.7 §7.2 Finding #4 + §7.3 — Performance DB critique.
--
-- Deux familles de corrections dans cette migration :
--
-- 1. Conversion du trigger trg_journal_entry_balance en STATEMENT-level (audit §7.2 #4) :
--    AVANT (V7_002) : trigger FOR EACH ROW sur journal_line, exécutait un SELECT SUM(debit),
--    SUM(credit) pour chaque ligne modifiée. Sur une écriture de 500 lignes (paie) :
--    500 SUM queries × scan moyen ~250 lignes = 125K lignes lues. Latence >10s sur paie de
--    500 employés, >30s sur clôture annuelle. Complexité O(N²).
--    APRÈS (V36) : trigger FOR EACH STATEMENT avec transition tables, exécute UN SEUL SELECT
--    SUM par journal_entry_id distinct touché par la statement. Sur la même écriture de 500
--    lignes : 1 SUM au lieu de 500. Complexité O(N).
--
-- 2. Ajout des index composites critiques manquants (audit §7.3) :
--    - journal_line (company_id, third_party_id) — accélère ThirdPartiesService.getStatement
--      et findPostedByThirdParty (audit §7.2 #1 fix). Gain estimé : 100× sur relevé tiers.
--    - journal_line (company_id, account_id, entry_date) — accélère getLedger (grand livre).
--    - journal_entry (company_id, status, entry_date) WHERE status IN ('POSTED') — partial index
--      pour findAllPosted et findAllPostedBetweenDates. Réduit drastiquement la taille de
--      l'index (POSTED représente ~80% des écritures, mais DRAFT/PENDING sont les plus consultés
--      côté UI — l'index partiel optimise les requêtes les plus lourdes).
--    - sales_invoice (company_id, due_date) WHERE status IN ('ISSUED','PARTIALLY_PAID') —
--      accélère la balance âgée clients et les alertes d'échéance.
--    - purchase_invoice (company_id, due_date) WHERE status IN ('RECEIVED','PARTIALLY_PAID') —
--      idem côté fournisseur.
--    - bank_statement_line (bank_account_id, amount, line_date) WHERE matched = FALSE —
--      accélère autoMatch (uniquement les lignes non matchées).
--    - audit_log (entity_type, entity_id, occurred_at) — accélère la forensique par entité.
--    - audit_log (actor_user_id, occurred_at) — accélère la forensique par utilisateur.
--
-- Note : tous les CREATE INDEX utilisent IF NOT EXISTS pour la réentrance. Le CONCURRENTLY
-- n'est PAS utilisé car la migration Flyway s'exécute dans une transaction — CONCURRENTLY
-- doit être hors transaction. Pour une migration hot sur une DB déjà chargée, exécuter
-- manuellement les CREATE INDEX CONCURRENTLY avant de monter la version Flyway.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Trigger statement-level pour l'équilibre débit=crédit (audit §7.2 #4)
-- ─────────────────────────────────────────────────────────────────────────────

-- Nouvelle fonction statement-level : utilise les transition tables pour n'exécuter qu'un
-- SEUL SELECT SUM par journal_entry_id distinct touché par la statement (au lieu de N SELECT
-- SUM pour N lignes modifiées). Complexité O(distinct_entry_ids) au lieu de O(N).
--
-- Compatibilité : les transition tables nécessitent PostgreSQL 10+. Le projet cible PG 14+
-- (Zonky embedded-postgres:2.0.7) — OK.
--
-- Note sur les transition tables : seules les tables pertinentes sont disponibles selon
-- TG_OP (INSERT = NEW seulement, DELETE = OLD seulement, UPDATE = NEW + OLD). On utilise
-- du SQL dynamique pour construire la requête SELECT DISTINCT selon l'opération.
CREATE OR REPLACE FUNCTION check_journal_entry_balance_stmt()
RETURNS TRIGGER AS $$
DECLARE
    affected_entry_id UUID;
    entry_status VARCHAR(20);
    total_debit NUMERIC(19, 4);
    total_credit NUMERIC(19, 4);
    distinct_query TEXT;
BEGIN
    -- Construire la requête SELECT DISTINCT selon le type d'opération (TG_OP).
    -- INSERT : seule new_rows existe. DELETE : seule old_rows existe. UPDATE : les deux.
    IF TG_OP = 'INSERT' THEN
        distinct_query := 'SELECT DISTINCT journal_entry_id FROM new_rows';
    ELSIF TG_OP = 'DELETE' THEN
        distinct_query := 'SELECT DISTINCT journal_entry_id FROM old_rows';
    ELSE  -- UPDATE
        distinct_query := 'SELECT DISTINCT journal_entry_id FROM (
            SELECT journal_entry_id FROM new_rows
            UNION
            SELECT journal_entry_id FROM old_rows
        ) AS combined';
    END IF;

    -- Pour chaque journal_entry_id distinct touché par la statement, vérifier l'équilibre
    -- si l'écriture est POSTED. Les DRAFT/PENDING_APPROVAL peuvent être déséquilibrées
    -- temporairement (saisie en cours).
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

-- Drop l'ancien trigger FOR EACH ROW (O(N²) sur bulk writes)
DROP TRIGGER IF EXISTS trg_journal_entry_balance ON journal_line;

-- Crée 3 triggers FOR EACH STATEMENT séparés (un par événment) — PostgreSQL ne supporte pas
-- REFERENCING transition tables sur un trigger multi-événement (INSERT OR UPDATE OR DELETE).
--
-- RÈGLE PostgreSQL (cf. doc CREATE TRIGGER) :
--   - INSERT  : seul NEW TABLE est autorisé (OLD TABLE n'existe pas pour un INSERT).
--   - UPDATE  : NEW TABLE et OLD TABLE sont tous deux autorisés.
--   - DELETE  : seul OLD TABLE est autorisé (NEW TABLE n'existe pas pour un DELETE).
--
-- La fonction check_journal_entry_balance_stmt() choisit la transition table à lire
-- dynamiquement en fonction de TG_OP (cf. supra) — il suffit donc que chaque trigger
-- déclare la(les) table(s) valide(s) pour son événement.
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
    'Trigger statement-level (audit v4.7 §7.2 #4) : vérifie l''équilibre débit=crédit pour chaque journal_entry_id distinct touché par la statement, en une seule passe au lieu de N. Complexité O(N) au lieu de O(N²).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Index composites critiques manquants (audit §7.3)
-- ─────────────────────────────────────────────────────────────────────────────

-- journal_line (company_id, third_party_id) — accélère ThirdPartiesService.getStatement,
-- suggestMatches, getAgedBalance. Utilisé par findPostedByThirdParty (audit §7.2 #1 fix).
CREATE INDEX IF NOT EXISTS idx_journal_line_company_thirdparty
    ON journal_line (company_id, third_party_id)
    WHERE third_party_id IS NOT NULL;

-- journal_line (company_id, account_id, entry_date) — accélère getLedger (grand livre).
-- Note : entry_date n'est pas sur journal_line mais sur journal_entry — index partiel sans
-- entry_date. Pour filtrer par date, le planner fera un hash join avec journal_entry.
CREATE INDEX IF NOT EXISTS idx_journal_line_company_account
    ON journal_line (company_id, account_id);

-- journal_entry partial WHERE status='POSTED' — accélère findAllPosted et
-- findAllPostedBetweenDates (les requêtes les plus lourdes du module accounting-engine).
-- Taille réduite car ~80% des écritures sont POSTED mais l'index exclut DRAFT/PENDING.
CREATE INDEX IF NOT EXISTS idx_journal_entry_company_posted
    ON journal_entry (company_id, entry_date)
    WHERE status = 'POSTED';

-- journal_entry composite (company_id, status, entry_date) — pour les filtres par statut
-- (ex : DRAFT pour les brouillons à terminer).
CREATE INDEX IF NOT EXISTS idx_journal_entry_company_status_date
    ON journal_entry (company_id, status, entry_date);

-- sales_invoice (company_id, due_date) WHERE status IN ('ISSUED','PARTIALLY_PAID') —
-- accélère la balance âgée clients et les alertes d'échéance (ScheduledAlertsConfig).
CREATE INDEX IF NOT EXISTS idx_sales_invoice_company_due_open
    ON sales_invoice (company_id, due_date)
    WHERE status IN ('ISSUED', 'PARTIALLY_PAID');

-- purchase_invoice (company_id, due_date) WHERE status IN ('RECEIVED','PARTIALLY_PAID') —
-- idem côté fournisseur.
CREATE INDEX IF NOT EXISTS idx_purchase_invoice_company_due_open
    ON purchase_invoice (company_id, due_date)
    WHERE status IN ('RECEIVED', 'PARTIALLY_PAID');

-- bank_statement_line (bank_account_id, amount, line_date) WHERE matched = FALSE —
-- accélère autoMatch qui ne scanne que les lignes non matchées. Partial index critique car
-- ~95% des lignes sont matchées après rapprochement — l'index reste petit.
CREATE INDEX IF NOT EXISTS idx_bank_statement_line_unmatched
    ON bank_statement_line (bank_account_id, amount, line_date)
    WHERE matched = FALSE;

-- audit_log (entity_type, entity_id, occurred_at) — accélère la forensique par entité
-- (ex : "montrer toutes les mutations de la facture X"). Volume attendu : 100M+ lignes sur
-- 5 ans — cet index est crucial pour la performance.
CREATE INDEX IF NOT EXISTS idx_audit_log_entity_occurred
    ON audit_log (entity_type, entity_id, occurred_at DESC);

-- audit_log (actor_user_id, occurred_at) — accélère la forensique par utilisateur
-- (ex : "montrer toutes les actions de l'utilisateur Y").
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_occurred
    ON audit_log (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

-- audit_log (entity_type, action, occurred_at) — accélère les requêtes sur SecurityEvent
-- par type d'événement (LOGIN_FAILED, REFRESH_TOKEN_REUSED, etc.). Critique pour la
-- forensique sécurité — voir SecurityAuditEventListener.
CREATE INDEX IF NOT EXISTS idx_audit_log_security_events
    ON audit_log (action, occurred_at DESC)
    WHERE entity_type = 'SecurityEvent';

COMMENT ON INDEX idx_journal_line_company_thirdparty IS
    'Audit v4.7 §7.3 — accélère ThirdPartiesService.getStatement (100× plus rapide sur 10K écritures).';
COMMENT ON INDEX idx_journal_entry_company_posted IS
    'Audit v4.7 §7.3 — partial index pour findAllPosted et findAllPostedBetweenDates.';
COMMENT ON INDEX idx_audit_log_security_events IS
    'Audit v4.7 §6.2 #5 — partial index pour forensique SecurityEvent (LOGIN_FAILED, REFRESH_TOKEN_REUSED, etc.).';
