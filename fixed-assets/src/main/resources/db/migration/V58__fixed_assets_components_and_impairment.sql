-- V47 — Finding #11 — Amortissement par composant IAS 16 + test de dépréciation IAS 36.
--
-- AVANT V47 :
--   - L'amortissement d'une immobilisation était calculé globalement sur le coût d'acquisition
--     total, sans décomposition. Or IAS 16 §43 impose que chaque partie d'une immobilisation
--     ayant une durée de vie utile différente soit comptabilisée et amortie séparément.
--     Exemple typique : un bâtiment (structure 50 ans + toiture 20 ans + installations 10 ans)
--     ne peut pas être amorti sur une seule durée moyenne — l'amortissement serait faux et
--     le bilan surestimerait la VNC des composants à courte durée de vie.
--   - Aucun test de dépréciation IAS 36 n'était possible. Or IAS 36 impose qu'à chaque clôture,
--     une immobilisation soit testée pour déterminer si sa VNC dépasse son montant recouvrable
--     (valeur d'utilité ou juste valeur nette). Si oui, une dépréciation doit être enregistrée
--     (D 6816 Charges pour dépréciation / C 291 Dépréciation des immobilisations).
--
-- V47 apporte :
--
-- 1. Nouvelle table asset_component — composants IAS 16 d'une immobilisation.
--    Chaque composant a son propre coût d'acquisition, sa propre durée de vie (en années),
--    sa propre valeur résiduelle et sa propre méthode d'amortissement.
--    Clé : (asset_id, code) unique. FK logique vers asset.id (pas de FK dur car asset est dans
--    un schéma multi-tenant et le onDelete CASCADE est géré par l'application).
--
-- 2. Nouvelle colonne depreciation_schedule_line.component_id — rattache une ligne d'échéancier
--    à un composant IAS 16 (nullable : null = amortissement global sur l'asset).
--    La contrainte unique uc_dsl_asset_period (asset_id, period_id) est REMPLACÉE par
--    uc_dsl_asset_period_component (asset_id, period_id, component_id) pour permettre à
--    plusieurs composants d'avoir chacun une ligne par période. PostgreSQL traite les NULL comme
--    distincts dans UNIQUE → rétro-compatible avec le mode "pas de composants" (component_id NULL).
--
-- 3. Nouvelles colonnes sur asset pour le test de dépréciation IAS 36 :
--    - impairment_amount : dépréciation IAS 36 cumulée (0 par défaut, NOT NULL).
--    - impairment_expense_account_id : compte de CHARGES (ex. 6816). NULL = fallback sur
--      depreciation_expense_account_id.
--    - accumulated_impairment_account_id : compte d'ACTIF (ex. 291). NULL = fallback sur
--      accumulated_depreciation_account_id.

-- ── 1. Table asset_component ──────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS asset_component (
    id                      UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id              UUID        NOT NULL,
    asset_id                UUID        NOT NULL,
    code                    VARCHAR(50) NOT NULL,
    label                   VARCHAR(200) NOT NULL,
    acquisition_cost        NUMERIC(19, 4) NOT NULL,
    useful_life_years       INT         NOT NULL,
    residual_value          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    depreciation_method     VARCHAR(25) NOT NULL DEFAULT 'STRAIGHT_LINE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_ac_method CHECK (depreciation_method IN ('STRAIGHT_LINE','DECLINING_BALANCE')),
    CONSTRAINT chk_ac_useful_life CHECK (useful_life_years >= 1),
    CONSTRAINT chk_ac_residual CHECK (residual_value >= 0 AND residual_value <= acquisition_cost),
    CONSTRAINT chk_ac_cost CHECK (acquisition_cost > 0)
);

CREATE INDEX IF NOT EXISTS idx_ac_asset ON asset_component (asset_id);
CREATE INDEX IF NOT EXISTS idx_ac_company ON asset_component (company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uc_asset_component_asset_code ON asset_component (asset_id, code);

COMMENT ON TABLE asset_component IS
    'V47 — Finding #11 : composants IAS 16 d''une immobilisation. Chaque composant a sa propre durée de vie et méthode d''amortissement.';
COMMENT ON COLUMN asset_component.useful_life_years IS
    'V47 — durée de vie utile en années (IAS 16 usage). Convertie en mois (×12) à la génération de l''échéancier.';

-- ── 2. Colonne component_id sur depreciation_schedule_line + remplacement contrainte unique ─────────

ALTER TABLE depreciation_schedule_line
    ADD COLUMN IF NOT EXISTS component_id UUID;

COMMENT ON COLUMN depreciation_schedule_line.component_id IS
    'V47 — Finding #11 : composant IAS 16 auquel se rattache cette ligne. NULL = amortissement global sur l''asset.';

-- Remplacer la contrainte unique (asset_id, period_id) par (asset_id, period_id, component_id)
-- pour autoriser plusieurs lignes par période quand l'asset a plusieurs composants.
-- PostgreSQL : les NULL sont traités comme distincts → rétro-compatible avec le mode sans composants.
ALTER TABLE depreciation_schedule_line DROP CONSTRAINT IF EXISTS uc_dsl_asset_period;
ALTER TABLE depreciation_schedule_line
    ADD CONSTRAINT uc_dsl_asset_period_component UNIQUE (asset_id, period_id, component_id);

CREATE INDEX IF NOT EXISTS idx_dsl_component ON depreciation_schedule_line (component_id) WHERE component_id IS NOT NULL;

-- ── 3. Colonnes IAS 36 sur asset ──────────────────────────────────────────────────────────

ALTER TABLE asset
    ADD COLUMN IF NOT EXISTS impairment_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS impairment_expense_account_id UUID,
    ADD COLUMN IF NOT EXISTS accumulated_impairment_account_id UUID;

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE asset SET impairment_amount = 0 WHERE impairment_amount IS NULL;

COMMENT ON COLUMN asset.impairment_amount IS
    'V47 — Finding #11 : dépréciation IAS 36 cumulée. 0 tant qu''aucun test n''a constaté de perte de valeur.';
COMMENT ON COLUMN asset.impairment_expense_account_id IS
    'V47 — Finding #11 : compte de CHARGES pour la dépréciation IAS 36 (ex. 6816). NULL = fallback sur depreciation_expense_account_id.';
COMMENT ON COLUMN asset.accumulated_impairment_account_id IS
    'V47 — Finding #11 : compte d''ACTIF pour la dépréciation IAS 36 cumulée (ex. 291). NULL = fallback sur accumulated_depreciation_account_id.';
