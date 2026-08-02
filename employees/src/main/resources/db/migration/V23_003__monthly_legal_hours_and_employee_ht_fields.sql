-- V23_003 — monthly legal hours and employee ht fields
-- V58 — Lot B — Rendre MONTHLY_LEGAL_HOURS configurable + overtimeHours100 + cnssNumber +
-- ofatmaSectorCode sur Employee.
-- :
-- - PayrollCalculator hardcodait MONTHLY_LEGAL_HOURS = 173.33h (France 35h/sem × 52/12).
-- Une entreprise haïtienne (48h/sem × 52/12 ≈ 208h) voyait donc son taux horaire calculé
-- à baseSalary/173.33 au lieu de baseSalary/208 — sur-estimation de 17% du taux horaire,
-- sous-évaluation corrélative des majorations HS et des cotisations sociales.
-- - Employee ne supportait que HS +25% et +50% (). Or le Code du travail
-- haïtien prévoit aussi des HS à +100% (au-delà de 56h/sem, dimanches/jours fériés).
-- - Pas de matricule CNSS ni de code secteur OFATMA sur Employee — or ces champs sont
-- obligatoires pour la déclaration mensuelle CNSS/OFATMA Haïti.
-- APPROCHE :
-- 1. ALTER companies ADD COLUMN monthly_legal_hours DECIMAL(5,2) NULLABLE.
-- NULL = fallback 173.33 (France, comportement historique) dans PayrollCalculator.
-- Pour Haïti : positionner à 208 (48h/sem × 52/12).
-- 2. ALTER employee ADD COLUMN overtime_hours_100 NUMERIC(19,4) NOT NULL DEFAULT 0.
-- Défaut 0 pour préserver le comportement historique français (HS +25%/+50% uniquement).
-- 3. ALTER employee ADD COLUMN cnss_number VARCHAR(20) NULLABLE — matricule CNSS Haïti.
-- 4. ALTER employee ADD COLUMN ofatma_sector_code VARCHAR(10) NULLABLE — code secteur
-- OFATMA (0.5%-6% selon secteur d'activité).
-- Backward compat : toutes les colonnes ajoutées ont des valeurs par défaut qui préservent
-- le comportement historique. Aucune migration de données nécessaire.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. companies.monthly_legal_hours
-- ─────────────────────────────────────────────────────────────────────────────


ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS monthly_legal_hours DECIMAL(5, 2);

-- Backfill : toutes les entreprises existantes sont considérées comme françaises par défaut
-- (rétro-compat). Les entreprises haïtiennes existantes devront être mises à jour avec
-- UPDATE companies SET monthly_legal_hours = 208 WHERE country = 'HT'.
UPDATE companies
SET monthly_legal_hours = 208.00
WHERE country = 'HT' AND monthly_legal_hours IS NULL;

COMMENT ON COLUMN companies.monthly_legal_hours IS
    'V58 — Lot B R-20 : durée légale mensuelle (173.33 France, 208 Haïti). NULL=fallback 173.33 (rétro-compat).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. employee.overtime_hours_100, cnss_number, ofatma_sector_code
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE employee
    ADD COLUMN IF NOT EXISTS overtime_hours_100   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cnss_number          VARCHAR(20),
    ADD COLUMN IF NOT EXISTS ofatma_sector_code   VARCHAR(10);

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE employee SET overtime_hours_100 = 0 WHERE overtime_hours_100 IS NULL;

COMMENT ON COLUMN employee.overtime_hours_100 IS
    'V58 — Lot B R-20 : heures supp. majorées à +100% (coefficient 2.0). Haïti (au-delà 56h/sem, dimanches/jours fériés).';
COMMENT ON COLUMN employee.cnss_number IS
    'V58 — Lot B R-20 : matricule CNSS Haïti (12 chiffres). Null pour employés français.';
COMMENT ON COLUMN employee.ofatma_sector_code IS
    'V58 — Lot B R-20 : code secteur OFATMA (taux Accidents variable 0.5%-6%).';
