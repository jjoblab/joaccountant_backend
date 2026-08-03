-- V1_004 — flyway bypassrls
-- V53__flyway_bypassrls — Accorde BYPASSRLS au user Flyway pour permettre les migrations
-- DML (INSERT/UPDATE/DELETE) sur les tables protégées par RLS.
-- (journal_line, journal_entry, invoice, invoice, third_party, expense_report).
-- Sans BYPASSRLS, le user Flyway (qui est OWNER de ces tables) est soumis aux policies RLS
-- lors des DML futurs :
-- - INSERT : OK car la policy V51 n'a que USING (pas de WITH CHECK) — les INSERT ne sont
-- pas filtrés par RLS.
-- - UPDATE / DELETE : SILENCIEUSEMENT filtrés (0 lignes affectées si la GUC
-- app.current_tenant n'est pas posée). Cela casserait toute migration future qui tente
-- de modifier ou supprimer des lignes existantes.
-- - SELECT : retourne 0 lignes — casserait les migrations de backfill conditionnel
-- (ex: "UPDATE journal_line SET col = ... WHERE col IS NULL").
-- <p>Solution : accorder BYPASSRLS au user Flyway. Les DML des migrations futures ne sont
-- alors plus filtrés par RLS — ils voient toutes les lignes, tous tenants confondus. C'est
-- acceptable car les migrations Flyway sont validées en code review et appliquées hors
-- contexte requête HTTP (pas de tenant context).
-- <p><b>Prérequis</b> — BYPASSRLS est un attribut SUPERUSER-only : seul un superuser peut
-- l'accorder. En dev/test avec Zonky embedded-postgres, le user est superuser → OK.
-- En prod, si le user DB applicatif n'est pas superuser, cette migration lèvera une erreur
-- insufficient_privilege (SQLSTATE 42501). On l'attrape dans le bloc DO $$ pour ne pas
-- casser le pipeline Flyway — le DBA devra accorder BYPASSRLS manuellement via un superuser.
-- <p>Alternative si BYPASSRLS n'est pas accordable : les migrations futures qui touchent
-- aux tables RLS doivent positionner explicitement la GUC :
-- SET LOCAL app.current_tenant = '00000000-0000-0000-0000-000000000000';
-- avant chaque DML, ou utiliser `SET row_security = off;` (qui requiert BYPASSRLS).
-- ============================================================================


DO $$
BEGIN
    -- current_user retourne le nom du rôle actuellement actif (le user Flyway).
    -- On utilise format('%I') pour échapper correctement le nom (ex: avec caractères spéciaux
    -- ou mots réservés comme "user"). L'alternative `ALTER ROLE CURRENT_USER BYPASSRLS` est
    -- valide en PG 14+ mais moins lisible dans les logs.
    EXECUTE format('ALTER ROLE %I BYPASSRLS', current_user);
    RAISE NOTICE 'R-03 : BYPASSRLS accordé à % — les migrations Flyway futures pourront faire DML sur les tables RLS-protégées sans être filtrées.',
        current_user;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'R-03 : BYPASSRLS NON accordé à % — manque le privilège SUPERUSER. Les migrations futures contenant UPDATE/DELETE sur les tables RLS-protégées (journal_line, journal_entry, invoice, invoice, third_party, expense_report) peuvent silencieusement échouer (0 lignes affectées). Faire accorder BYPASSRLS manuellement par un DBA superuser.',
            current_user;
END
$$;
