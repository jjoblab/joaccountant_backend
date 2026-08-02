-- V9_002 — financial statement type sce
-- V73 — v7-2 : IFRS Statement of Changes in Equity (IAS 1.106).
-- CONTEXTE : la validation PME4 (Caribbean Textiles, zone franche — filiale d'un groupe
-- international en IFRS_FULL) a identifié que IFRS_FULL déclare mandatory_statements = 'BS,CR,CF,SCE'
-- mais l'énumération FinancialStatementType ne contient que 3 valeurs (BALANCE_SHEET,
-- INCOME_STATEMENT, CASH_FLOW_STATEMENT). Le STATEMENT_OF_CHANGES_IN_EQUITY (SCE — IAS 1.106)
-- est manquant → non-conformité IFRS.
-- CORRECTION :
-- 1. Côté Java : énumération FinancialStatementType étendue avec STATEMENT_OF_CHANGES_IN_EQUITY.
-- 2. Côté DB : la contrainte CHECK chk_fss_type (V8_001) était initialement limitée à
-- ('BALANCE_SHEET','INCOME_STATEMENT') puis étendue à CASH_FLOW_STATEMENT en v4.7.
-- On la remplace pour autoriser STATEMENT_OF_CHANGES_IN_EQUITY en plus.
-- Le SCE est généré à la volée (pas de snapshot persistant) par la méthode
-- FinancialStatementsService.getStatementOfChangesInEquity(). Si un snapshot persistant
-- devient nécessaire (clôture annuelle), on pourra ajouter une ligne dans
-- financial_statement_snapshot avec type=STATEMENT_OF_CHANGES_IN_EQUITY.


ALTER TABLE financial_statement_snapshot DROP CONSTRAINT IF EXISTS chk_fss_type;

ALTER TABLE financial_statement_snapshot
    ADD CONSTRAINT chk_fss_type CHECK (type IN (
        'BALANCE_SHEET',
        'INCOME_STATEMENT',
        'CASH_FLOW_STATEMENT',
        'CLOSING_SNAPSHOT',
        'STATEMENT_OF_CHANGES_IN_EQUITY'
    ));

COMMENT ON TABLE financial_statement_snapshot IS
    'V73 — v7-2 : FinancialStatementType étendu avec STATEMENT_OF_CHANGES_IN_EQUITY (IAS 1.106). Type utilisé par les snapshots figés à la clôture pour le SCE.';
