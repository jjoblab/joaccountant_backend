-- V24_002 — payroll run thirteenth month
-- V75 — v7-4 : 13e mois (Code du Travail Haïti art. 153).
-- CONTEXTE : la v5.5 a ajouté le champ Employee.thirteenthMonthEligible mais le calcul
-- effectif du 13e mois n'est pas implémenté. Le Code du Travail haïtien art. 153 impose
-- le versement d'un 13e mois (« mois bonus ») en décembre pour tout employé ayant au
-- moins 1 an d'ancienneté au 31 décembre. Pour les employés avec moins d'un an, prorata
-- temporis.
-- CORRECTION :
-- 1. Ajouter une colonne run_type à payroll_run (VARCHAR avec défaut 'REGULAR' pour
-- préserver les campagnes existantes).
-- 2. Étendre la contrainte d'unicité uc_pr_company_period pour inclure run_type —
-- sinon une campagne REGULAR et une campagne THIRTEENTH_MONTH en décembre de la
-- même année pour la même entreprise entreraient en conflit.
-- 3. Documenter la nouvelle valeur via COMMENT.

-- Étape 1 : ajout de la colonne run_type (VARCHAR pour éviter la gestion d'enum PostgreSQL)


ALTER TABLE payroll_run
    ADD COLUMN IF NOT EXISTS run_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR';

-- Étape 2 : étendre la contrainte d'unicité (V27 l'a créée comme INDEX UNIQUE, pas contrainte)
DROP INDEX IF EXISTS uc_pr_company_period;
CREATE UNIQUE INDEX IF NOT EXISTS uc_pr_company_period
    ON payroll_run (company_id, period_year, period_month, run_type);

-- Étape 3 : CHECK constraint pour valider les valeurs autorisées
ALTER TABLE payroll_run
    DROP CONSTRAINT IF EXISTS chk_payroll_run_type;
ALTER TABLE payroll_run
    ADD CONSTRAINT chk_payroll_run_type CHECK (run_type IN ('REGULAR', 'THIRTEENTH_MONTH'));

COMMENT ON COLUMN payroll_run.run_type IS
    'V75 — v7-4 : valeurs possibles = REGULAR (paie mensuelle normale), THIRTEENTH_MONTH (13e mois Code Travail art. 153).';

-- Vérification : la contrainte chk_payroll_run_type doit accepter les nouvelles valeurs.
-- Pour les campagnes existantes, run_type est defaulted à 'REGULAR' — pas de migration
-- de données nécessaire.
