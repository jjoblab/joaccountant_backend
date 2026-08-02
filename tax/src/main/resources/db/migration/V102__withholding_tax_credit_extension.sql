-- V102 — Extension de la contrainte CHECK sur tax_credit_carried_forward pour accepter
-- le type 'WITHHOLDING' (retenue à la source).
--
-- AVANT V102 : la table tax_credit_carried_forward (créée en V70 — anciennement V59 avant
-- restructuration Task 3) acceptait uniquement les valeurs 'VAT','TCA','TURNOVER_TAX','EXCISE'.
-- La méthode TaxService.getWithholdingDeclaration calculait donc correctement le crédit RS
-- à reporter (quand avoirs > factures sur la période) mais ne pouvait pas le persister —
-- insertion refusée par la contrainte CHECK. Le crédit était silencieusement perdu d'une
-- période à l'autre (cf. commentaire "TODO v6.3" dans TaxService.getWithholdingDeclaration).
--
-- V102 étend la contrainte pour accepter 'WITHHOLDING' (valeur ajoutée à l'enum TaxType.java).
-- Aucune donnée à migrer — la table est vide en environnement dev/démo/CI (pas de prod).
--
-- Note : on DROP puis CREATE la contrainte car PostgreSQL ne supporte pas ALTER CONSTRAINT
-- pour modifier le prédicat d'un CHECK (il faut le supprimer puis le recréer).

ALTER TABLE tax_credit_carried_forward DROP CONSTRAINT IF EXISTS chk_tax_credit_tax_type;

ALTER TABLE tax_credit_carried_forward ADD CONSTRAINT chk_tax_credit_tax_type CHECK (
    tax_type IN ('VAT','TCA','TURNOVER_TAX','EXCISE','WITHHOLDING')
);

COMMENT ON COLUMN tax_credit_carried_forward.tax_type IS
    'VAT (TVA collectée - déductible), TCA (Haïti), TURNOVER_TAX, EXCISE, WITHHOLDING (retenue à la source — art. 156-1 Code Fiscal Haïti) — cf. TaxType.java.';
