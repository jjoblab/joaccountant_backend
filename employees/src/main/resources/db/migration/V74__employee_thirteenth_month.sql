-- =====================================================================
-- V63 — R-F-validation (lot-G) — Colonne thirteenth_month_eligible sur employee
-- =====================================================================
-- Découlé des validations PME/expert-comptable (29 juillet 2026) :
--   - Expert-comptable Maître Pierre-Louis : 13ᵉ mois non implémenté (P0)
--   - PME4 Caribbean Textiles (zone franche, 1200 employés) : 13ᵉ mois bloquant
--
-- Cette migration (placée dans :employees) ajoute la colonne
-- thirteenth_month_eligible sur la table employee.
-- =====================================================================

ALTER TABLE employee
    ADD COLUMN IF NOT EXISTS thirteenth_month_eligible BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN employee.thirteenth_month_eligible IS
    'V63 — R-F-validation : éligibilité 13ᵉ mois (Code Travail Haïti art. 153). Défaut TRUE.';
