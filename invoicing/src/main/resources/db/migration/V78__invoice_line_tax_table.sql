-- V67 — v6-1-multi-tax-invoice-line — Table invoice_line_tax
--
-- Contexte (lot-G validation-pme-expert) :
--   En Haïti, sur une facture de prestation de services, la TVA 10% (art. 191 Code Fiscal)
--   et la TCA 10% (art. 196) sont CUMULATIVES sur la même ligne. Avant V67, InvoiceLine ne
--   portait qu'un seul champ `tax_rate` (BigDecimal 5,2) → une seule taxe par ligne, et la
--   TVA 10% + TCA 10% étaient fusionnées dans la déclaration TaxService.getDeclaration (agrégation
--   par taux sans filtrer par taxType).
--
--   V67 introduit la table `invoice_line_tax` qui permet à chaque ligne de facture de porter
--   plusieurs taxes (TVA + TCA + autres taxes sur chiffre d'affaires + accises). Le champ
--   `invoice_line.tax_rate` est CONSERVÉ pour la rétro-compatibilité : si une ligne n'a aucune
--   entrée dans `invoice_line_tax`, InvoicingService fallback sur `tax_rate` comme TVA seule
--   (comportement historique v5.x).
--
-- Colonnes :
--   - id                 : UUID v7 (PK, ordonné temporellement — cf. TenantAwareEntity).
--   - invoice_line_id    : FK vers invoice_line.id (logical FK — pas de CONSTRAINT FK physique
--                          car la table invoice_line est dans le même module :invoicing et le
--                          pattern du projet est de gérer l'intégrité référentielle via le code
--                          applicatif + ON DELETE CASCADE non nécessaire car on delete manuellement).
--   - tax_type           : VAT | TCA | TURNOVER_TAX | EXCISE (CHECK constraint — aligné sur l'enum
--                          jo.accountant.tax.entity.TaxType créée en R-07).
--   - tax_code           : code optionnel de la TaxRule appliquée (ex: TVA_HT_10, TCA_HT_10_SERVICES).
--   - tax_label          : libellé optionnel (ex: "TVA 10% (art. 191)", "TCA 10% services (art. 196)").
--   - rate               : taux en pourcentage (0 à 100, NUMERIC 5,2 — cohérent avec invoice_line.tax_rate).
--   - taxable_base       : base HT soumise à la taxe (NUMERIC 19,4 — cohérent avec line_total_ht).
--   - tax_amount         : montant de la taxe = taxable_base × rate / 100 (NUMERIC 19,4).
--   - display_order      : ordre d'affichage sur la facture (TVA avant TCA avant EXCISE, etc.).
--                          défaut 0 pour rétro-compat.
--   - version            : optimistic locking (@Version — cohérent avec TenantAwareEntity).
--
-- Pas de company_id : la table n'est PAS tenant-aware directement (hérite du tenant via
-- InvoiceLine parent). Cela évite la redondance et les bugs de désynchronisation. Les requêtes
-- de tenant-isolation se font en JOIN sur invoice_line.company_id.
--
-- Index :
--   - idx_invoice_line_tax_line   : recherche par ligne (loadInvoiceResponse, génération écriture).
--   - idx_invoice_line_tax_type   : agrégation par taxType dans TaxService.getDeclaration(taxType).
--
-- Backward compatibility :
--   - Aucune modification de la table invoice_line existante.
--   - Aucune donnée seedée : les factures existantes restent mono-taxe (tax_rate) et le
--     InvoicingService les traite comme avant (fallback taxes=null → taxRate).

CREATE TABLE IF NOT EXISTS invoice_line_tax (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    invoice_line_id UUID            NOT NULL,
    tax_type        VARCHAR(20)     NOT NULL,  -- VAT, TCA, TURNOVER_TAX, EXCISE
    tax_code        VARCHAR(30),               -- ex: TVA_HT_10, TCA_HT_10_SERVICES
    tax_label       VARCHAR(200),
    rate            NUMERIC(5, 2)   NOT NULL,
    taxable_base    NUMERIC(19, 4)  NOT NULL,
    tax_amount      NUMERIC(19, 4)  NOT NULL,
    display_order   INT             NOT NULL DEFAULT 0,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_invoice_line_tax_type CHECK (tax_type IN ('VAT', 'TCA', 'TURNOVER_TAX', 'EXCISE')),
    CONSTRAINT chk_invoice_line_tax_rate CHECK (rate >= 0 AND rate <= 100)
);

CREATE INDEX IF NOT EXISTS idx_invoice_line_tax_line
    ON invoice_line_tax (invoice_line_id);

CREATE INDEX IF NOT EXISTS idx_invoice_line_tax_type
    ON invoice_line_tax (tax_type);
