-- V23_005 — employee termination reason
-- =====================================================================
-- V83 — Task 17 (v2.4.0) — Colonne termination_reason sur employee
-- =====================================================================
-- Découlé de la tâche 17 (Amélioration de EmployeeDetail) :
-- - Le mobile affiche désormais le motif de fin de contrat sur la fiche
-- employé (carte « Contrat »). Il faut donc que le backend expose ce
-- champ via EmployeeResponse.
-- - Le champ est nullable : il reste NULL tant que l'employé n'est pas
-- TERMINATED. Renseigné lors de l'appel à changeStatus(TERMINATED) ou
-- via un endpoint d'édition ultérieur.
-- Aucune valeur par défaut : NULL jusqu'à ce qu'un motif soit explicite.
-- =====================================================================


ALTER TABLE employee
    ADD COLUMN IF NOT EXISTS termination_reason VARCHAR(500);

COMMENT ON COLUMN employee.termination_reason IS
    'V83 — Task 17 : motif de fin de contrat (texte libre, ex. Démission, Licenciement, Fin de CDD). NULL tant que status != TERMINATED.';
