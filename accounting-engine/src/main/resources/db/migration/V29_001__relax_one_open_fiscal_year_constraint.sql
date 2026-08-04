-- V29_001 — Relaxation de la contrainte "1 exercice OPEN max" par entreprise
-- ===========================================================================
--
-- MOTIVATION (audit v9.4, 2026-08-04) :
-- La contrainte uc_one_open_per_company (V8_009) interdisait à une entreprise
-- d'avoir plus d'un exercice fiscal OPEN simultanément. Cette contrainte est
-- TROP RIGIDE par rapport aux standards de l'industrie :
--
--   - Odoo       : plusieurs exercices OPEN simultanés (création libre)
--   - Sage 50/100 : plusieurs exercices coexistent (création N+1 avant clôture N)
--   - QuickBooks  : pas de contrainte "1 OPEN" (year-end closing est une opération séparée)
--
-- En pratique, les comptables travaillent souvent dans l'exercice N+1 AVANT
-- d'avoir finalisé la clôture de N (rapprochements bancaires tardifs, notes de
-- frais reçues après le 30/09, etc.). La contrainte "1 OPEN" bloquait ce
-- workflow légitime.
--
-- Le champ companies.active_fiscal_year_id (déjà existant depuis V8_009)
-- indique quel exercice est "actif" pour les nouvelles écritures par défaut.
-- findPeriodForDate() résout déjà la bonne période par date, indépendamment
-- du statut OPEN/LOCKED/CLOSED de l'exercice — seul le statut de la PÉRIODE
-- (FiscalPeriodStatus.OPEN vs LOCKED) compte pour autoriser une écriture.
--
-- La clôture d'exercice (FiscalYearClosingService.closeFiscalYear) génère :
--   1. L'écriture de clôture (produits/charges → résultat)
--   2. L'écriture d'ouverture N+1 (à-nouveau = soldes bilan reportés)
--   3. Verrouille l'exercice (CLOSED) + ses périodes (LOCKED)
--   4. Auto-switch l'exercice actif vers le prochain OPEN
--
-- Cette migration supprime l'index partiel uc_one_open_per_company. Le guard
-- applicatif dans AccountingEngineService.createFiscalYear est aussi retiré.

-- 1. Supprimer l'index partiel uc_one_open_per_company
DROP INDEX IF EXISTS uc_one_open_per_company;

-- 2. Commentaire de migration (remplace le COMMENT ON INDEX ci-dessus)
COMMENT ON TABLE fiscal_year IS
    'Exercices fiscaux d''une entreprise. V29_001 (2026-08-04) : la contrainte "1 OPEN max" a été supprimée pour aligner le comportement sur Odoo/Sage/QuickBooks (plusieurs exercices OPEN simultanés autorisés). Le champ companies.active_fiscal_year_id indique l''exercice actif. La clôture (FiscalYearClosingService.closeFiscalYear) génère les écritures de clôture + ouverture N+1 + verrouille l''exercice.';
