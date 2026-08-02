-- V19_010 — its contribution rule seed
-- V76 — v7-5 : Seed ITS Haïti (Impôt Traitements/Salaires, Code Fiscal art. 156).
-- CONTEXTE : l'expert-comptable a identifié que le bulletin de paie V56 affiche
-- ${incomeTaxWithheld} mais que cette variable n'est jamais alimentée. L'ITS (Impôt sur
-- Traitements et Salaires) est une retenue à la source sur les salaires en Haïti
-- (Code Fiscal art. 156), calculée selon un barème progressif mensuel.
-- APPROCHE : seed d'une ContributionRule globale 'ITS_HT' (company_id IS NULL) avec
-- bracket_type=PROGRESSIVE et brackets_json documentant le barème mensuel.
-- Barème indicatif 2024 — À VALIDER AVEC DGI / EXPERT-COMPTABLE :
-- Tranche 0-50k HTG : 0%
-- Tranche 50k-100k HTG : 1%
-- Tranche 100k-150k HTG : 2%
-- Tranche 150k-200k HTG : 3%
-- Tranche 200k-300k HTG : 4%
-- Tranche >300k HTG : 5%
-- NOTES :
-- - regime=HT_GENERAL (même régime que CNSS/OFATMA/AST — V57).
-- - contribution_type=EMPLOYEE (retenue à la source sur le salaire — payée par l'employé).
-- - base_type=GROSS_ABATED (assiette = brut - cotisations sociales CNSS/OFATMA/AST).
-- L'abattement est calculé en Java par PayrollCalculator.computeTaxableBaseForIts,
-- qui soustrait les cotisations avant d'appliquer le barème. L'abatement_rate est
-- laissé à 99.9999 (quasi nul) car l'abattement est fait en amont par le service.
-- - tax_mapping_code='INCOME_TAX_WITHHELD' (compte 442 État-ITS à reverser).
-- Pour les ONG exonérées (Company.taxExemptionStatus = NGO_EXEMPT), l'ITS n'est pas
-- appliqué — l'appelant (PayrollService) doit filtrer la règle ITS_HT pour ces entreprises.


INSERT INTO contribution_rule (id, company_id, code, label, regime, contribution_type, rate,
                                base_type, abatement_rate, monthly_ceiling, ceiling_multiplier,
                                bracket_type, brackets_json, active, tax_mapping_code, version)
VALUES
    (uuidv7(), NULL, 'ITS_HT',
     'ITS Haïti — Impôt sur Traitements et Salaires (Code Fiscal art. 156) — barème progressif mensuel',
     'HT_GENERAL', 'EMPLOYEE', 0.0000,  -- rate ignoré en mode PROGRESSIVE
     'GROSS_ABATED', 99.9999, NULL, NULL,
     'PROGRESSIVE',
     '[
        {"threshold": 0,      "rate": 0.00},
        {"threshold": 50000,  "rate": 1.00},
        {"threshold": 100000, "rate": 2.00},
        {"threshold": 150000, "rate": 3.00},
        {"threshold": 200000, "rate": 4.00},
        {"threshold": 300000, "rate": 5.00}
     ]'::jsonb,
     TRUE, 'INCOME_TAX_WITHHELD', 0)
ON CONFLICT DO NOTHING;

COMMENT ON CONSTRAINT chk_contribution_rule_bracket_type ON contribution_rule IS
    'V76 — v7-5 : type FLAT ou PROGRESSIVE. PROGRESSIVE utilisé par AST (V57) et ITS (V76).';

COMMENT ON COLUMN contribution_rule.tax_mapping_code IS
    'V76 — v7-5 : valeurs possibles incluent SOCIAL_SECURITY_PAYABLE, INCOME_TAX_WITHHELD, OTHER_TAX_PAYABLE.';
