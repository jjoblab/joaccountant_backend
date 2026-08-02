-- V16_002 — index perf bank_statement_line
-- Index composite sur bank_statement_line pour accélérer autoMatch
-- (uniquement les lignes non matchées). Partial index critique car ~95%
-- des lignes sont matchées après rapprochement — l'index reste petit.
-- (Historiquement dans V36 du module accounting-engine, déplacé ici car
--  bank_statement_line est créée en V16_001.)


CREATE INDEX IF NOT EXISTS idx_bank_statement_line_unmatched
    ON bank_statement_line (bank_account_id, amount, line_date)
    WHERE matched = FALSE;
