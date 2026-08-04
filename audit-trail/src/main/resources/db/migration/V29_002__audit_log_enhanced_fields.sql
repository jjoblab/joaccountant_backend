-- V29_002 — Audit trail : ajout des champs execution_context, ip_address, user_agent, field_name
-- ============================================================================================
-- MOTIVATION (audit v9.4, 2026-08-04) :
-- La recherche comparative avec NetSuite/Sage Intacct/Odoo a révélé que l'audit trail
-- actuel manque 4 champs essentiels pour la conformité forensique :
--
-- 1. execution_context — comment l'action a été déclenchée (UI/API/import/cron)
--    NetSuite "Execution Context", Sage Intacct "Source" : permet de distinguer
--    une action utilisateur d'un import CSV ou d'un job planifié.
--
-- 2. ip_address — adresse IP de l'utilisateur
--    Sage Intacct Advanced Audit Trail, Odoo OCA auditlog : essentiel pour forensique
--    (tracer la source d'une fraude, détecter un accès depuis un pays inhabituel).
--
-- 3. user_agent — User-Agent HTTP
--    Permet de distinguer mobile vs web vs API programmatique.
--
-- 4. field_name — nom du champ modifié
--    NetSuite "Field", SAP CDPOS "FNAME" : permet de filtrer "toutes les modifications
--    du champ 'amount'" sans parser le JSONB old/new.
--
-- Cette migration est ADDITIVE (ALTER TABLE ADD COLUMN IF NOT EXISTS) — les lignes
-- existantes auront NULL pour ces colonnes (compatibilité backward).
-- ============================================================================================

-- 1. Ajouter les colonnes (IF NOT EXISTS pour idempotence)
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS execution_context VARCHAR(20);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS user_agent VARCHAR(500);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS field_name VARCHAR(100);

-- 2. Index sur execution_context (filtrer "toutes les actions API")
CREATE INDEX IF NOT EXISTS idx_audit_log_exec_context
    ON audit_log (execution_context, occurred_at DESC);

-- 3. Index sur ip_address (forensique : tracer une IP suspecte)
CREATE INDEX IF NOT EXISTS idx_audit_log_ip_address
    ON audit_log (ip_address, occurred_at DESC)
    WHERE ip_address IS NOT NULL;

-- 4. Index sur field_name (filtrer par champ modifié)
CREATE INDEX IF NOT EXISTS idx_audit_log_field_name
    ON audit_log (entity_type, field_name, occurred_at DESC)
    WHERE field_name IS NOT NULL;

-- 5. Retirer la contrainte NOT NULL sur version si elle existe
-- (le champ @Version a été retiré de l'entité Java — l'audit trail est immutable
-- par design, pas besoin d'optimistic locking).
ALTER TABLE audit_log ALTER COLUMN version DROP NOT NULL;

COMMENT ON TABLE audit_log IS
    'v9.4 (V29_002) — Audit trail forensique. Columns added: execution_context (UI/API/import/cron), '
    'ip_address, user_agent, field_name. @Version removed (immutable by design, enforced by '
    'triggers trg_audit_log_no_update/trg_audit_log_no_delete in V27_002).';
