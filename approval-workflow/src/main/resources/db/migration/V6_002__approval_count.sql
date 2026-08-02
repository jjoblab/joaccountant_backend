-- V6_002 — Fix S1-FIN : ajout colonnes approval_count et approver_user_ids (Vague fix).


ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS approval_count INT NOT NULL DEFAULT 0;
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS approver_user_ids JSONB;
