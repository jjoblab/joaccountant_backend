-- V23_002 — employees overtime absences
-- V49 — — Gestion HS / absences / congés paie.
-- :
-- - Le module :payroll calculait le salaire brut à partir du seul `baseSalary` sans tenir
-- compte des heures supplémentaires (HS +25% / +50%), des absences non rémunérées ni des
-- congés payés pris sur la période. Or, ces 3 éléments sont des composants essentiels du
-- bulletin de paie :
-- * HS +25% / +50% : majoration légale des heures sup (Code du travail France/OHADA).
-- Sans cette majoration, l'employé est payé au taux normal pour des heures sup →
-- sous-paiement illégal.
-- * Absences : un employé absent non justifié doit voir son salaire amputé au prorata.
-- Sans cette déduction, l'employeur paie des journées non travaillées → surfacturation.
-- * Congés payés pris : bien que l'indemnité de CP soit calculée séparément (au MVP
-- on simplifie en déduisant du baseSalary comme les absences), il faut tracer le
-- nombre de jours pris pour le solde des congés.
-- V49 ajoute 4 colonnes NOT NULL DEFAULT 0 à la table employee (NULL interdit pour ne pas
-- casser les calculs PayrollCalculator si une colonne est oubliée à l'INSERT).


ALTER TABLE employee
    ADD COLUMN IF NOT EXISTS overtime_hours_25 NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS overtime_hours_50 NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS absence_days      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS paid_leave_days   NUMERIC(19, 4) NOT NULL DEFAULT 0;

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE employee SET overtime_hours_25 = 0 WHERE overtime_hours_25 IS NULL;
UPDATE employee SET overtime_hours_50 = 0 WHERE overtime_hours_50 IS NULL;
UPDATE employee SET absence_days      = 0 WHERE absence_days      IS NULL;
UPDATE employee SET paid_leave_days   = 0 WHERE paid_leave_days   IS NULL;

COMMENT ON COLUMN employee.overtime_hours_25 IS
    'V49 — Finding #18 : heures supplémentaires majorées à +25% (taux horaire × 1.25).';
COMMENT ON COLUMN employee.overtime_hours_50 IS
    'V49 — Finding #18 : heures supplémentaires majorées à +50% (taux horaire × 1.50).';
COMMENT ON COLUMN employee.absence_days IS
    'V49 — Finding #18 : jours d''absence non rémunérés. Déduits du baseSalary au prorata.';
COMMENT ON COLUMN employee.paid_leave_days IS
    'V49 — Finding #18 : jours de congés payés pris sur la période. Déduits du baseSalary au prorata (indemnité CP séparée prévue en v4.8).';
