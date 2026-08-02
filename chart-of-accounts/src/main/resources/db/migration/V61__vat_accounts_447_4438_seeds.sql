-- V50 — Finding #10 — Seed comptes 447 + 4438 dans les plans SYSCOHADA et PCG_FRANCE.
--
-- Avant V50, les comptes 447 (TVA autoliquidation / reverse-charge) et 4438 (TVA différée non
-- encaissée) étaient référencés dans InvoicingService (audit v4.7 §4.1 — Findings #6 et #7)
-- via le AccountResolver avec fallback codes "447" / "4438" — mais ces comptes n'étaient JAMAIS
-- créés dans le seed SectorAccountTemplate, ni par aucune migration. Conséquence : pour toute
-- entreprise nouvellement initialisée en SYSCOHADA ou PCG_FRANCE, la première facture en
-- autoliquidation (intra-UE B2B, Article 283, 2 nonies CGI) ou la première facture en TVA sur
-- encaissement (Finding #6, VatMode.ENCAISSEMENT) levait l'erreur
-- `VAT_REVERSE_CHARGE_ACCOUNT_NOT_FOUND` / `VAT_DEFERRED_ACCOUNT_NOT_FOUND`, bloquant l'émission.
-- L'utilisateur devait créer manuellement le compte manquant.
--
-- V50 corrige cela en ajoutant les comptes 447 et 4438 à TOUS les plans existants utilisant
-- un référentiel SYSCOHADA_REVISED ou PCG_FRANCE (les deux référentiels où ces codes sont
-- standard — cf. V1_002 pour les IDs de frameworks).
--
-- Format : cf. V5_001__chart_of_accounts.sql (table `account` — colonnes company_id, parent_id,
-- code, label, level, reporting_class, reporting_subcategory, normal_balance, locked, active,
-- is_collective, path, tax_mapping_code).
--
-- Convention :
--   - parent_code = "44" (compte "État" — classe 4 / Tiers, déjà créé par SectorAccountTemplate).
--     On n'insère QUE si "44" existe pour la company (LEFT JOIN + WHERE a44.id IS NOT NULL),
--     sinon on skippe — l'entreprise n'a pas de plan comptable complet.
--   - level = 3 (parent "44" = level 2).
--   - reporting_class = 'PASSIF' (TVA = compte de tiers passif).
--   - reporting_subcategory = 'COURANT' (TVA = court terme).
--   - normal_balance = 'CREDIT' (TVA collectée/différée = crédit).
--   - locked = false (l'utilisateur peut le renommer / désactiver).
--   - is_collective = false (compte détaillé, pas de regroupement de tiers).
--   - path = a44.path || '.' || code  (ex. "4.44.447" si path de 44 = "4.44").
--
-- Idempotent : ON CONFLICT (company_id, code) DO NOTHING (uc_account_company_code déjà existant).

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
    AND f.code IN ('SYSCOHADA_REVISED', 'PCG_FRANCE')
LEFT JOIN account a44 ON a44.company_id = c.id AND a44.code = '44'
CROSS JOIN (VALUES
    ('447',  'TVA autoliquidation (reverse charge)', 'VAT_REVERSE_CHARGE'),
    ('4438', 'TVA différée non encaissée',           'VAT_DEFERRED_UNCOLLECTED')
) AS seed(code, label, tax_mapping_code)
WHERE a44.id IS NOT NULL
ON CONFLICT (company_id, code) DO NOTHING;
