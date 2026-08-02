-- V22_002 — expense category limits
-- V43 — (audit batch 1) — Plafonds paramétrables par catégorie de note de frais.
-- , ExpenseCategory était un simple enum Java {TRAVEL, MEALS, SUPPLIES, OTHER} et la
-- table expense_line stockait category comme un VARCHAR(20) avec CHECK constraint. Aucun
-- plafond n'était configurable → un employé pouvait soumettre 100 notes de repas à 200 EUR
-- le même jour sans qu'aucune alerte ne soit levée.
-- V43 crée la table expense_category (JPA entity ExpenseCategory) qui matérialise la
-- configuration par entreprise des plafonds journaliers et mensuels par catégorie. Les
-- contraintes :
-- - UC (company_id, code) : une seule configuration par catégorie par entreprise
-- - daily_limit / monthly_limit NULLABLES : null = pas de plafond (comportement historique)
-- - CHECK (daily_limit >= 0) : un plafond ne peut pas être négatif
-- - CHECK (monthly_limit >= 0) : idem
-- Le seed insère les 4 catégories standards (TRAVEL, MEALS, SUPPLIES, OTHER) pour CHAQUE
-- entreprise existante (company table) avec des plafonds NULL (rétro-compatibilité : aucune
-- validation n'est activée tant que l'admin ne configure pas les plafonds via l'API). Les
-- nouvelles entreprises devront créer leurs configurations via l'endpoint dédié (à venir en
-- v4.8 — pour l'instant, insertion directe en base ou via un script de seed).


CREATE TABLE IF NOT EXISTS expense_category (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    code            VARCHAR(20) NOT NULL,
    label           VARCHAR(100),
    daily_limit     NUMERIC(19, 4),
    monthly_limit   NUMERIC(19, 4),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_expense_category_company_code UNIQUE (company_id, code),
    CONSTRAINT chk_expense_category_daily_limit CHECK (daily_limit IS NULL OR daily_limit >= 0),
    CONSTRAINT chk_expense_category_monthly_limit CHECK (monthly_limit IS NULL OR monthly_limit >= 0)
);

CREATE INDEX IF NOT EXISTS idx_expense_category_company ON expense_category (company_id);

-- Seed initial : créer les 4 catégories standards pour chaque entreprise existante, avec
-- plafonds NULL (pas de validation tant que l'admin n'a pas configuré les plafonds).
-- On utilise ON CONFLICT DO NOTHING pour rendre le seed idempotent.
INSERT INTO expense_category (company_id, code, label, daily_limit, monthly_limit)
SELECT c.id, v.code, v.label, NULL, NULL
FROM companies c
CROSS JOIN (VALUES
    ('TRAVEL',   'Deplacements'),
    ('MEALS',    'Repas'),
    ('SUPPLIES', 'Fournitures'),
    ('OTHER',    'Autres')
) AS v(code, label)
ON CONFLICT (company_id, code) DO NOTHING;

COMMENT ON TABLE expense_category IS
    'V43 — Finding #19 (audit batch 1) : plafonds journaliers/mensuels paramétrables par catégorie de note de frais.';
