-- V5_004 — cta account
-- V74 — v7-3 : Cumulative Translation Adjustment (CTA) en capitaux propres.
-- CONTEXTE : la v6.4 (devise de présentation HTG) convertit les soldes mais n'isole pas
-- l'écart de conversion en capitaux propres. Conformément à IAS 21, l'écart de conversion
-- (CTA) doit être isolé dans une rubrique distincte des capitaux propres (« Cumulative
-- Translation Adjustment » ou « Écart de conversion »).
-- CORRECTION :
-- 1. Étendre l'énumération ReportingSubcategory avec une valeur CTA (côté Java).
-- 2. Étendre la contrainte CHECK chk_account_subcategory côté DB pour autoriser 'CTA'.
-- 3. Créer le compte 108 « Écart de conversion (CTA) » pour les entreprises existantes
-- en PCN_HAITI / SYSCOHADA_REVISED / PCG_FRANCE (s'il n'existe pas déjà).
-- Pour les nouvelles entreprises, le compte sera créé via l'initializeMandated étendu
-- (côté Java — pas de migration supplémentaire nécessaire, le compte est créé au runtime
-- lors de l'initialisation du plan comptable).

-- Étendre la contrainte CHECK pour autoriser 'CTA'


ALTER TABLE account DROP CONSTRAINT IF EXISTS chk_account_subcategory;

ALTER TABLE account
    ADD CONSTRAINT chk_account_subcategory CHECK (
        reporting_subcategory IS NULL
        OR reporting_subcategory IN ('COURANT', 'NON_COURANT', 'N_A', 'CTA')
    );

-- Pour les entreprises existantes en PCN_HAITI / SYSCOHADA_REVISED / PCG_FRANCE,
-- créer le compte 108 « Écart de conversion (CTA) » s'il n'existe pas déjà.
-- Ce compte est créé en classe 1 (capitaux propres), reporting_subcategory = 'CTA'.
INSERT INTO account (id, company_id, code, label, level, reporting_class, reporting_subcategory,
                     normal_balance, locked, active, is_collective, path, tax_mapping_code, version,
                     created_at, updated_at)
SELECT uuidv7(), c.id, '108', 'Écart de conversion (CTA)', 2,
       'CAPITAUX_PROPRES', 'CTA', 'CREDIT', FALSE, TRUE, FALSE, '1/108', NULL, 0,
       NOW(), NOW()
FROM companies c
JOIN accounting_framework af ON af.id = c.accounting_framework_id
WHERE af.code IN ('PCN_HAITI', 'SYSCOHADA_REVISED', 'PCG_FRANCE')
  AND NOT EXISTS (
      SELECT 1 FROM account a
      WHERE a.company_id = c.id AND a.code = '108'
  );

COMMENT ON COLUMN account.reporting_subcategory IS
    'V74 — v7-3 : ajout valeur CTA pour Cumulative Translation Adjustment (IAS 21).';
