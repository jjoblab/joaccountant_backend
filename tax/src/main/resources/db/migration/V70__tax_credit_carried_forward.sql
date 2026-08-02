-- V59 — Lot B R-23 — Persistance du crédit de TVA reporté d'une période à l'autre.
--
-- AVANT V59 :
--   - TaxService.getDeclaration() ligne 247 avait :
--       BigDecimal taxCreditCarriedForward = BigDecimal.ZERO;  // TODO : récupérer depuis table dédiée
--   - Le crédit de TVA (quand TVA déductible > TVA collectée) était calculé en fin de
--     déclaration mais NON persisté. À la période suivante, l'entreprise devait le resaisir
--     manuellement — risque d'oubli (= perte fiscale) + aucune traçabilité pour audit DGI.
--
-- V59 crée la table `tax_credit_carried_forward` qui persiste le crédit à la fin de chaque
-- déclaration et le lit au début de la suivante.
--
-- Schéma :
--   id              UUID PK (généré par l'app via UUID.randomUUID())
--   company_id      UUID NOT NULL — tenant (RLS protège la table via policy V51)
--   tax_type        VARCHAR(20) NOT NULL — VAT, TCA, TURNOVER_TAX, EXCISE (cf. TaxType.java)
--   period_year     INT NOT NULL — année de la période (ex: 2024)
--   period_month    INT NOT NULL — mois 1-12 (pour déclaration mensuelle TVA/TCA)
--   credit_amount   NUMERIC(19,4) NOT NULL — montant du crédit (positif)
--   carried_to_next BOOLEAN NOT NULL DEFAULT TRUE — false si remboursement demandé
--   created_at      TIMESTAMPTZ NOT NULL
--   version         BIGINT NOT NULL DEFAULT 0 — optimistic locking
--
-- Contrainte unique (company_id, tax_type, period_year, period_month) : un seul crédit par
-- période et par type de taxe (TVA et TCA peuvent avoir des crédits séparés).
--
-- Note : pas de FK vers companies — la table est créée dans le module :tax qui ne dépend
-- pas directement de :company au niveau schéma (la FK logique est garantie par la RLS V51
-- sur company_id, et par l'application qui ne crée jamais de ligne avec un companyId inconnu).

CREATE TABLE IF NOT EXISTS tax_credit_carried_forward (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    tax_type        VARCHAR(20) NOT NULL,
    period_year     INT         NOT NULL,
    period_month    INT         NOT NULL,
    credit_amount   NUMERIC(19, 4) NOT NULL,
    carried_to_next BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    -- Une seule ligne de crédit par (company, tax_type, period_year, period_month)
    CONSTRAINT uc_tax_credit_period UNIQUE (company_id, tax_type, period_year, period_month),

    -- Validation : tax_type dans l'énumération (cf. TaxType.java)
    CONSTRAINT chk_tax_credit_tax_type CHECK (
        tax_type IN ('VAT','TCA','TURNOVER_TAX','EXCISE')
    ),
    -- Validation : period_month entre 1 et 12
    CONSTRAINT chk_tax_credit_period_month CHECK (period_month BETWEEN 1 AND 12),
    -- Validation : credit_amount >= 0 (un crédit ne peut pas être négatif)
    CONSTRAINT chk_tax_credit_amount CHECK (credit_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_tax_credit_company_period
    ON tax_credit_carried_forward (company_id, tax_type, period_year, period_month);

COMMENT ON TABLE tax_credit_carried_forward IS
    'V59 — Lot B R-23 : crédit de TVA reporté d''une période à l''autre. Remplace le BigDecimal.ZERO hardcoded TaxService:247.';
COMMENT ON COLUMN tax_credit_carried_forward.tax_type IS
    'VAT (TVA collectée - déductible), TCA (Haïti), TURNOVER_TAX, EXCISE — cf. TaxType.java.';
COMMENT ON COLUMN tax_credit_carried_forward.credit_amount IS
    'Montant du crédit positif (TVA déductible - TVA collectée quand > 0).';
COMMENT ON COLUMN tax_credit_carried_forward.carried_to_next IS
    'TRUE si reporté vers la période suivante (défaut). FALSE si remboursement demandé (art. 271 CGI / DGI Haïti).';
