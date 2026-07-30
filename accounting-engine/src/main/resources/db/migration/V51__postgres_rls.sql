-- V51__postgres_rls — Row-Level Security PostgreSQL sur les 6 tables TenantAware critiques.
--
-- Cette migration étend le garde-fou RLS (initié en version simplifiée sur 3 tables) à
-- l'ensemble des tables financières multi-tenant à fort impact réglementaire :
--
--   1. journal_line       — lignes d'écritures comptables (données financières brutes)
--   2. journal_entry      — entêtes d'écritures comptables
--   3. sales_invoice      — factures clients (TVA, CA, recouvrement)
--   4. purchase_invoice   — factures fournisseurs (TVA déductible, trésorerie)
--   5. third_party        — tiers (clients / fournisseurs — données commerciales sensibles)
--   6. expense_report     — notes de frais (données employé + TVA déductible)
--
-- <b>Stratégie</b> :
-- <ol>
--   <li>Activation de RLS ({@code ALTER TABLE ... ENABLE ROW LEVEL SECURITY}).</li>
--   <li>Création d'une policy {@code tenant_isolation} sur chaque table :
--       {@code USING (company_id = current_setting('app.current_tenant', true)::uuid)}.
--       Le second argument {@code true} de {@code current_setting} indique "missing OK" —
--       retourne NULL si la GUC n'est pas posée, ce qui rend la policy évaluée à FALSE
--       (aucune ligne retournée). Comportement fail-closed.</li>
--   <li>{@code FORCE ROW LEVEL SECURITY} : même le owner de la table (postgres, app role)
--       est soumis aux policies. Sans FORCE, le owner bypasserait RLS — ce qui annulerait
--       l'intérêt du garde-fou pour les requêtes émises par l'app.</li>
-- </ol>
--
-- <p><b>Câblage côté Java</b> : le {@code TenantContextFilter} (jo.accountant.core.tenant)
-- doit exécuter {@code SET app.current_tenant = ?} sur la connexion JDBC au début de chaque
-- requête HTTP, à partir de {@code TenantContext.getCompanyId()}. Tant que ce câblage n'est
-- pas en place, RLS reste "armed but inactive" (la GUC vaut NULL → policy FALSE → toutes les
-- lignes filtrées). Voir V51__tenant_rls.sql (module :app) pour le détail du câblage à venir.
--
-- <p><b>Flyway</b> : le rôle Flyway doit disposer de {@code BYPASSRLS} (ou exécuter
-- {@code SET LOCAL row_security = off}) pour que les migrations futures puissent
-- {@code INSERT}/{@code UPDATE} ces tables sans être bloquées par RLS.

-- ============================================================================
-- journal_line — lignes d'écritures comptables (données financières brutes)
-- ============================================================================
ALTER TABLE journal_line ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON journal_line;
CREATE POLICY tenant_isolation ON journal_line
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE journal_line FORCE ROW LEVEL SECURITY;

-- ============================================================================
-- journal_entry — entêtes d'écritures comptables
-- ============================================================================
ALTER TABLE journal_entry ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON journal_entry;
CREATE POLICY tenant_isolation ON journal_entry
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE journal_entry FORCE ROW LEVEL SECURITY;

-- ============================================================================
-- sales_invoice — factures clients (données fiscales et commerciales sensibles)
-- ============================================================================
ALTER TABLE sales_invoice ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON sales_invoice;
CREATE POLICY tenant_isolation ON sales_invoice
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE sales_invoice FORCE ROW LEVEL SECURITY;

-- ============================================================================
-- purchase_invoice — factures fournisseurs (TVA déductible, trésorerie)
-- ============================================================================
ALTER TABLE purchase_invoice ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON purchase_invoice;
CREATE POLICY tenant_isolation ON purchase_invoice
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE purchase_invoice FORCE ROW LEVEL SECURITY;

-- ============================================================================
-- third_party — tiers (clients / fournisseurs — données commerciales sensibles)
-- ============================================================================
ALTER TABLE third_party ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON third_party;
CREATE POLICY tenant_isolation ON third_party
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE third_party FORCE ROW LEVEL SECURITY;

-- ============================================================================
-- expense_report — notes de frais (données employé + TVA déductible)
-- ============================================================================
ALTER TABLE expense_report ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON expense_report;
CREATE POLICY tenant_isolation ON expense_report
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE expense_report FORCE ROW LEVEL SECURITY;
