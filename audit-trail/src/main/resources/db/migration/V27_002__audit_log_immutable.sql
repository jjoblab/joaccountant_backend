-- V27_002 — audit log immutable
-- V54__audit_log_immutable — Rend audit_log immuable (UPDATE / DELETE interdits).
-- Problème : la table audit_log (créée antérieurement) n'a ni trigger BEFORE UPDATE/DELETE
-- ni REVOKE UPDATE/DELETE. Un attaquant (ou un admin DB compromis via injection SQL)
-- peut altérer ou supprimer des lignes d'audit pour effacer les traces d'une fraude.
-- Correctif : installer deux triggers BEFORE UPDATE / BEFORE DELETE qui lèvent une
-- exception bloquante. PostgreSQL annule l'opération et la transaction est rollbackée.
-- Le trigger sur UPDATE existe aussi pour empêcher la "correction" rétroactive
-- (ex: changer actor_user_id d'une ligne suspecte).
-- Note sur REVOKE : la section REVOKE est volontairement commentée car, sur la plupart
-- des déploiements, le user DB applicatif est OWNER de audit_log (créée par ses migrations
-- Flyway). REVOKE sur le owner n'a aucun effet. Pour durcir davantage, il faudrait :
-- 1. Créer audit_log avec un owner dédié (ex: audit_owner) ;
-- 2. Donner uniquement INSERT/SELECT au user applicatif ;
-- 3. Le user applicatif ne serait alors pas OWNER et ne pourrait plus appliquer
-- les migrations Flyway sur cette table — ce qui casserait le workflow actuel.
-- Le trigger plpgsql est donc la défense principale (suffisante contre injection SQL
-- et erreurs applicatives), REVOKE est laissé en commentaire pour référence future.
-- ============================================================================


CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS trigger AS $$
BEGIN
    -- TG_OP vaut 'UPDATE' ou 'DELETE' selon le trigger appelant.
    -- OLD.id est l'identifiant de la ligne ciblée (toujours disponible dans un trigger BEFORE
    -- UPDATE/DELETE). Lever une exception provoque le ROLLBACK de la transaction courante
    -- et remonte une erreur SQLSTATE P0001 (raise_exception) au client JDBC.
    RAISE EXCEPTION 'audit_log is immutable: % operation not allowed on row %',
        TG_OP, OLD.id
        USING ERRCODE = 'raise_exception';
END;
$$ LANGUAGE plpgsql;

-- Idempotence : DROP TRIGGER IF EXISTS avant CREATE pour permettre la ré-application
-- (Flyway ne rejoue jamais un script déjà appliqué, mais on garde cette robustesse
-- au cas où la migration serait rejouée manuellement par un DBA sur un environnement
-- de secours).
DROP TRIGGER IF EXISTS trg_audit_log_no_update ON audit_log;
CREATE TRIGGER trg_audit_log_no_update
BEFORE UPDATE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();

DROP TRIGGER IF EXISTS trg_audit_log_no_delete ON audit_log;
CREATE TRIGGER trg_audit_log_no_delete
BEFORE DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();

-- REVOKE UPDATE, DELETE sur audit_log — voir commentaire en tête de migration.
-- Décommenter UNIQUEMENT si le user DB applicatif n'est pas OWNER de audit_log
-- (configuration avec owner dédié) :
-- REVOKE UPDATE, DELETE ON audit_log FROM PUBLIC;
