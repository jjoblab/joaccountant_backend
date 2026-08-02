-- V5_003 — pcn haiti specific accounts
-- V60 — (lot-F1-code-arch) — PCN_HAITI : comptes spécifiques + contrainte chk_account_reporting_class étendue.
-- Problème : avant , le PCN Haïtien était traité comme un SYSCOHADA renommé. La classe 8
-- haïtienne (Comptes spéciaux = engagements hors bilan + comptes de régularisation) était mappée
-- à HAO (CHARGES/PRODUITS) comme en SYSCOHADA — ce qui faisait remonter les engagements hors bilan
-- dans le compte de résultat et faussait le résultat net.
-- V60 corrige cela en deux temps :
-- 1. Étend la contrainte CHECK `chk_account_reporting_class` pour autoriser la valeur 'OTHER'
-- (qui sera utilisée pour la classe 8 PCN_HAITI via ChartOfAccountsService.inferReportingClass).
-- 2. Seed les comptes 442 (État-RS), 446 (État-TCA), 447 (État-IS), 448 (État-taxes diverses),
-- 4438 (TVA différée) pour toutes les entreprises PCN_HAITI — en plus de V50 qui les avait
-- déjà créés pour SYSCOHADA_REVISED / PCG_FRANCE.
-- Spécificités PCN_HAITI vs V50 :
-- - 447 a un libellé différent ("État-IS — Impôt sur les sociétés") vs V50 ("TVA autoliquidation")
-- car en PCN_HAITI, le code 447 désigne l'impôt sur les sociétés (et non la TVA reverse-charge).
-- - 442 / 446 / 448 sont NOUVEAUX (V50 ne les créait pas) — spécifiques au contexte fiscal haïtien :
-- * 442 = État-RS (Retenues à la source — impôt retenu à la source sur paiements à tiers)
-- * 446 = État-TCA (Taxe sur le Chiffre d'Affaires — spécifique Haïti)
-- * 448 = État-taxes diverses (autres taxes haïtiennes non couvertes par 442/446/447)
-- Format : cf. V50__vat_accounts_447_4438_seeds.sql — table `account` (cols : company_id, parent_id,
-- code, label, level, reporting_class, reporting_subcategory, normal_balance, locked, active,
-- is_collective, path, tax_mapping_code, created_at, updated_at, version).
-- Convention :
-- - parent_code = "44" (compte "État - IS" en PCN_HAITI — classe 4 / Tiers). On n'insère QUE si
-- "44" existe pour la company (LEFT JOIN + WHERE a44.id IS NOT NULL), sinon on skippe.
-- - level = 3 (parent "44" = level 2).
-- - reporting_class = 'PASSIF' (tous les comptes "État xxx" sont des dettes fiscales = passif CT).
-- NB: la classe 8 PCN_HAITI est mappée à 'OTHER' (via inferReportingClass), mais les comptes
-- 44x sont des sous-comptes de la classe 4, donc PASSIF (cohérent avec V50).
-- - reporting_subcategory = 'COURANT' (dettes fiscales CT).
-- - normal_balance = 'CREDIT' (dettes = crédit).
-- - locked = false (l'utilisateur peut les renommer / désactiver).
-- - is_collective = false (comptes détaillés, pas de regroupement de tiers).
-- - path = a44.path || '.' || code (ex. "4.44.442" si path de 44 = "4.44").
-- Idempotent : ON CONFLICT (company_id, code) DO NOTHING (uc_account_company_code déjà existant).

-- ════════════════════════════════════════════════════════════════════════
-- 1. Étendre la contrainte CHECK pour autoriser 'OTHER'
-- ════════════════════════════════════════════════════════════════════════
-- La contrainte chk_account_reporting_class a été créée antérieurement avec seulement 5 valeurs
-- (ACTIF, PASSIF, CAPITAUX_PROPRES, PRODUITS, CHARGES). ajoute OTHER pour les Comptes
-- spéciaux PCN_HAITI (classe 8). On drop et recrée la contrainte avec la valeur OTHER ajoutée.


ALTER TABLE account DROP CONSTRAINT IF EXISTS chk_account_reporting_class;

ALTER TABLE account
    ADD CONSTRAINT chk_account_reporting_class
    CHECK (reporting_class IN ('ACTIF','PASSIF','CAPITAUX_PROPRES','PRODUITS','CHARGES','OTHER'));

-- ════════════════════════════════════════════════════════════════════════
--  2. Seed comptes 442 / 446 / 447 / 448 / 4438 pour PCN_HAITI
-- ════════════════════════════════════════════════════════════════════════
-- On ne seed QUE pour les entreprises dont le référentiel est PCN_HAITI (V1_002 — framework
-- 00000000-0000-0000-0000-000000000005). Pour SYSCOHADA/PCG, V50 a déjà créé 447 et 4438 (avec
-- un libellé différent — ne pas écraser).

INSERT INTO account (
    id, company_id, parent_id, code, label, level,
    reporting_class, reporting_subcategory, normal_balance,
    locked, active, is_collective, path, tax_mapping_code,
    created_at, updated_at, version
)
SELECT
    uuidv7(),
    c.id,
    a44.id,
    seed.code,
    seed.label,
    3,
    'PASSIF',
    'COURANT',
    'CREDIT',
    FALSE,
    TRUE,
    FALSE,
    a44.path || '.' || seed.code,
    seed.tax_mapping_code,
    now(),
    now(),
    0
FROM companies c
JOIN accounting_framework f ON c.accounting_framework_id = f.id
    AND f.code = 'PCN_HAITI'
LEFT JOIN account a44 ON a44.company_id = c.id AND a44.code = '44'
CROSS JOIN (VALUES
    ('442',  'État-RS — Retenues à la source',                NULL),
    ('446',  'État-TCA — Taxe sur chiffre d''affaires',       NULL),
    ('447',  'État-IS — Impôt sur les sociétés',              NULL),
    ('448',  'État-taxes diverses',                           NULL),
    ('4438', 'TVA différée non encaissée',                    'VAT_DEFERRED_UNCOLLECTED')
) AS seed(code, label, tax_mapping_code)
WHERE a44.id IS NOT NULL
ON CONFLICT (company_id, code) DO NOTHING;
