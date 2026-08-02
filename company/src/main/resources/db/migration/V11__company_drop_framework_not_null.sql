-- V3_006 — Levée des contraintes NOT NULL sur accounting_framework_id et fiscal_year_start_month
-- sur companies (restructuration 2026-07-24 §5).
--
-- Ces colonnes sont désormais positionnées à l'étape 6 du wizard (et non plus à l'étape 1).
-- Elles doivent rester NULLABLE jusqu'à ce que la complétion du wizard impose leur présence
-- (vérification applicative dans CompanyService.completeWizard).
--
-- La contrainte NOT NULL sur fiscal_year_start_month est conservée (la valeur par défaut 1
-- reste acceptable pendant tout le wizard — c'est un mois de clôture provisoire).

ALTER TABLE companies ALTER COLUMN accounting_framework_id DROP NOT NULL;
