-- V10_003 — postgres rls third_party
-- Row-Level Security sur third_party (données commerciales sensibles).
-- (Historiquement dans V51 du module accounting-engine, déplacé ici car
--  third_party est créée en V10_001.)


ALTER TABLE third_party ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON third_party;
CREATE POLICY tenant_isolation ON third_party
    USING (company_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE third_party FORCE ROW LEVEL SECURITY;
