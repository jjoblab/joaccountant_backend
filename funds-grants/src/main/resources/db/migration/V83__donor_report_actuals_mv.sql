-- V72 — v7-1 : vue matérialisée agrégeant les charges par grant + cost_category + période.
--
-- CONTEXTE : alimentation automatique de donor_report_line. La vue matérialisée
-- pré-calcule, pour chaque (company, grant, period_year, period_quarter, cost_category),
-- la somme des charges (debit - credit) sur comptes de classe 6 (PCN) tagués analytiquement
-- avec un valeur analytique correspondant à un grant.
--
-- La vue est rafraîchie mensuellement par DonorReportFeedingService (cron ShedLock
-- 1er du mois à 02:00 UTC) ou manuellement via endpoint admin POST /donor-reports/refresh.
-- REFRESH MATERIALIZED VIEW CONCURRENTLY nécessite un index UNIQUE (créé ci-dessous).
--
-- JOINs :
--   journal_entry (statut POSTED, date dans la période)
--   journal_line (compte de charge : account_code LIKE '6%')
--   journal_line_analytical_tag (value_id pointant vers un grant.analytical_value_id)
--   grant (récupération du donor_type, company_id)
--   cost_category_mapping (résolution account_code → cost_category, par framework de l'entreprise)
--   accounting_framework + companies (pour identifier le code framework de l'entreprise)
--
-- NOTE : on privilégie journal_line.account_code plutôt qu'un JOIN sur account, car
-- account_code est dénormalisé dans journal_line (performance : évite un JOIN supplémentaire).
-- Le pattern matching se fait par LIKE account_code_pattern.

CREATE MATERIALIZED VIEW IF NOT EXISTS donor_report_actuals_mv AS
SELECT
    je.company_id,
    fg.id AS grant_id,
    -- V72 — v7-1 : donor_type dérivé du code du grant (CASE WHEN).
    -- La table fg_grant ne stocke pas donor_type directement ; on le déduit du code
    -- (USAID-2026-WASH → USAID, EU-2026-HEALTH → EU, etc.). Cohérent avec
    -- DonorReportFeedingService.resolveDonorType (côté Java).
    CASE
        WHEN fg.code LIKE 'USAID%' THEN 'USAID'
        WHEN fg.code LIKE 'EU%' THEN 'EU'
        WHEN fg.code LIKE 'BM%' OR fg.code LIKE 'WB%' OR fg.code LIKE 'WORLD%' THEN 'WORLD_BANK'
        WHEN fg.code LIKE 'CRS%' THEN 'CRS'
        ELSE 'OTHER'
    END AS donor_type,
    EXTRACT(YEAR FROM je.entry_date)::INT AS period_year,
    EXTRACT(QUARTER FROM je.entry_date)::INT AS period_quarter,
    EXTRACT(MONTH FROM je.entry_date)::INT AS period_month,
    ccm.cost_category,
    SUM(
        (jl.debit - jl.credit)
        * COALESCE(jlat.allocation_percentage, 100) / 100.0
    ) AS actual_amount
FROM journal_entry je
JOIN journal_line jl ON jl.journal_entry_id = je.id
-- Tag analytique rattaché à la ligne : on ne garde que les lignes taguées avec un value_id
-- qui correspond à un grant.analytical_value_id. Si plusieurs plans analytiques existent
-- (par exemple "Fonds/Projets" et "Centres de coût"), on prend tous les tags dont la value
-- correspond à un grant.
JOIN journal_line_analytical_tag jlat
    ON jlat.journal_line_id = jl.id
   AND jlat.company_id = je.company_id
JOIN fg_grant fg
    ON fg.analytical_value_id = jlat.value_id
   AND fg.company_id = je.company_id
-- Résolution de la cost_category par mapping (global par défaut + surcharge company).
LEFT JOIN cost_category_mapping ccm
    ON ccm.accounting_framework_code = (
        SELECT af.code FROM accounting_framework af
        JOIN companies c ON c.accounting_framework_id = af.id
        WHERE c.id = je.company_id
    )
    AND jl.account_code LIKE ccm.account_code_pattern
    AND ccm.active = TRUE
WHERE je.status = 'POSTED'
  AND jl.account_code LIKE '6%'  -- comptes de charges PCN
GROUP BY je.company_id, fg.id,
    CASE
        WHEN fg.code LIKE 'USAID%' THEN 'USAID'
        WHEN fg.code LIKE 'EU%' THEN 'EU'
        WHEN fg.code LIKE 'BM%' OR fg.code LIKE 'WB%' OR fg.code LIKE 'WORLD%' THEN 'WORLD_BANK'
        WHEN fg.code LIKE 'CRS%' THEN 'CRS'
        ELSE 'OTHER'
    END,
    EXTRACT(YEAR FROM je.entry_date),
    EXTRACT(QUARTER FROM je.entry_date),
    EXTRACT(MONTH FROM je.entry_date),
    ccm.cost_category;

-- Index UNIQUE — requis pour REFRESH MATERIALIZED VIEW CONCURRENTLY.
-- La PK de la MV est (company_id, grant_id, period_year, period_month, cost_category).
CREATE UNIQUE INDEX IF NOT EXISTS idx_donor_report_actuals_mv_unique
    ON donor_report_actuals_mv (company_id, grant_id, period_year, period_month, cost_category);

CREATE INDEX IF NOT EXISTS idx_donor_report_actuals_mv_grant_period
    ON donor_report_actuals_mv (grant_id, period_year, period_quarter);

CREATE INDEX IF NOT EXISTS idx_donor_report_actuals_mv_company_period
    ON donor_report_actuals_mv (company_id, period_year, period_quarter);

COMMENT ON MATERIALIZED VIEW donor_report_actuals_mv IS
    'V72 — v7-1 : vue matérialisée agrégeant les charges par grant/cost_category/période. À rafraîchir mensuellement via REFRESH MATERIALIZED VIEW CONCURRENTLY. L''agrégation par (period_year, period_quarter) est déduite de period_month côté service Java.';
