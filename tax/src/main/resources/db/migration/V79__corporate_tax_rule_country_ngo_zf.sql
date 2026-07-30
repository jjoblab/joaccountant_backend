-- =====================================================================
-- V79 — v8-1 : Extension corporate_tax_rule pour zone franche + ONG
-- =====================================================================
-- Découlé des écarts P0 signalés par les validateurs PME3 (ONG Espwa pou
-- Ayiti) et PME4 (Caribbean Textiles ZF) :
--   - PME3 : IS calculé à 30% au lieu de 0% (ONG exonérée — art. 195)
--   - PME4 : IS calculé à 30% au lieu de 15% (zone franche — art. 195)
--
-- Actions :
--   1. Ajouter la colonne is_ngo_exempt_rate (country_code et
--      is_free_zone_rate existent déjà depuis V65).
--   2. Étendre la contrainte CHECK chk_corporate_tax_rule_eligibility
--      pour accepter la valeur 'FREE_ZONE' (manquante dans V65).
--   3. Recréer la contrainte UNIQUE uc_corporate_tax_rule en incluant
--      is_ngo_exempt_rate — sinon la règle ONG HT et la règle standard HT
--      (qui partagent (NULL,'HT',FALSE,TRUE)) ne peuvent coexister.
--      NOTE : la V65 insérait la règle ONG avec ON CONFLICT DO NOTHING
--      qui l'a silencieusement skippée (conflit unique avec la règle HT
--      standard). On la recrée ici.
--   4. Backfill : marquer eligibility='FREE_ZONE' sur la règle ZF existante
--      (V65 l'avait créée avec eligibility='UNKNOWN').
--   5. Backfill : marquer is_ngo_exempt_rate=TRUE sur la règle ONG si elle
--      existe, sinon l'insérer.
--   6. Créer / recréer les règles globales ZF (FREE_ZONE) et ONG (NGO_EXEMPT)
--      avec eligibility explicite — utilisées par TaxService.
-- =====================================================================

-- 1. Ajout de la colonne is_ngo_exempt_rate
ALTER TABLE corporate_tax_rule
    ADD COLUMN IF NOT EXISTS is_ngo_exempt_rate BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Étendre la contrainte CHECK sur eligibility
ALTER TABLE corporate_tax_rule
    DROP CONSTRAINT IF EXISTS chk_corporate_tax_rule_eligibility;
ALTER TABLE corporate_tax_rule
    DROP CONSTRAINT IF EXISTS chk_ctr_eligibility;
ALTER TABLE corporate_tax_rule
    ADD CONSTRAINT chk_corporate_tax_rule_eligibility CHECK (
        eligibility IS NULL OR eligibility IN (
            'SME', 'LARGE', 'UNKNOWN', 'FREE_ZONE', 'NGO_EXEMPT'
        )
    );

-- 3. Recréer la contrainte UNIQUE en incluant is_ngo_exempt_rate
--    (permet de coexister : règle HT standard + règle HT ONG, qui ont
--    toutes deux (NULL, 'HT', FALSE, TRUE) mais des is_ngo_exempt_rate différents)
ALTER TABLE corporate_tax_rule
    DROP CONSTRAINT IF EXISTS uc_corporate_tax_rule;
ALTER TABLE corporate_tax_rule
    ADD CONSTRAINT uc_corporate_tax_rule UNIQUE (
        company_id, country_code, is_free_zone_rate, is_ngo_exempt_rate, active
    );

-- 4. Backfill de la règle ZF existante (V65 l'avait créée avec eligibility='UNKNOWN')
UPDATE corporate_tax_rule
SET eligibility = 'FREE_ZONE',
    reduced_rate = standard_rate,
    is_free_zone_rate = TRUE
WHERE company_id IS NULL
  AND country_code = 'HT'
  AND is_free_zone_rate = TRUE
  AND standard_rate = 15;

-- 5. Backfill de la règle ONG existante (si elle a réussi à s'insérer malgré V65)
UPDATE corporate_tax_rule
SET is_ngo_exempt_rate = TRUE,
    eligibility = 'NGO_EXEMPT',
    standard_rate = 0,
    reduced_rate = 0
WHERE company_id IS NULL
  AND country_code = 'HT'
  AND eligibility = 'NGO_EXEMPT';

-- 6. Backfill country_code sur les règles existantes (V65 l'avait en NOT NULL,
--    mais par sécurité on force HT pour les règles pré-V65 sans country_code)
UPDATE corporate_tax_rule
SET country_code = 'HT'
WHERE country_code IS NULL OR country_code = '';

-- 7. UPSERT règle ZF (FREE_ZONE, 15%, art. 195) — créer si absente
INSERT INTO corporate_tax_rule (id, company_id, country_code, standard_rate, reduced_rate,
                                 reduced_rate_threshold, is_free_zone_rate, is_ngo_exempt_rate,
                                 eligibility, active, version, applicable_from)
VALUES
    (uuidv7(), NULL, 'HT', 15.00, 15.00, NULL, TRUE, FALSE, 'FREE_ZONE', TRUE, 0, '2010-01-01')
ON CONFLICT DO NOTHING;

-- 8. UPSERT règle ONG (NGO_EXEMPT, 0%, art. 195) — créer (V65 l'avait silencieusement
--    skippée à cause du conflit unique avec la règle HT standard)
INSERT INTO corporate_tax_rule (id, company_id, country_code, standard_rate, reduced_rate,
                                 reduced_rate_threshold, is_free_zone_rate, is_ngo_exempt_rate,
                                 eligibility, active, version, applicable_from)
VALUES
    (uuidv7(), NULL, 'HT', 0.00, 0.00, NULL, FALSE, TRUE, 'NGO_EXEMPT', TRUE, 0, '2010-01-01')
ON CONFLICT DO NOTHING;

-- 9. Commentaires documentaires
COMMENT ON COLUMN corporate_tax_rule.country_code IS
    'V79 — v8-1 : code pays ISO 3166-1 alpha-2 (HT/FR/CA). Existait depuis V65, exposé côté Java en v8-1.';
COMMENT ON COLUMN corporate_tax_rule.is_free_zone_rate IS
    'V79 — v8-1 : TRUE si règle IS zone franche (15% Haïti, Code Fiscal art. 195).';
COMMENT ON COLUMN corporate_tax_rule.is_ngo_exempt_rate IS
    'V79 — v8-1 : TRUE si règle IS ONG exonérée (0% Haïti, Code Fiscal art. 195).';

-- =====================================================================
-- NOTE DE CONFLIT DE VERSION (PROMPT_MODULE_DEMOS)
-- =====================================================================
-- v8-1 utilise V79 (tax) + V80 (company). Le module :demo-data prévoit
-- d'utiliser V80 — pour éviter tout conflit Flyway (duplicate version),
-- le module :demo-data doit renuméroter sa migration V80 → V82 (et
-- ajuster PROMPT_MODULE_DEMOS.md en conséquence). V81 reste libre pour
-- d'éventuelles migrations intermédiaires.
-- =====================================================================
