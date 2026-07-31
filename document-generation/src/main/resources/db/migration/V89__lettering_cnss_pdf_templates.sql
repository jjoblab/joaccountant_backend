-- V89 — Reports Hub v2.5.0 (step7-backend)
--
-- 1. Élargit les CHECK chk_dt_document_type et chk_gd_document_type pour autoriser
--    les 2 nouveaux DocumentType ajoutés pour les exports PDF LETTERING et CNSS_RETURN.
-- 2. Insère un template HTML Thymeleaf par défaut (company_id = NULL — gabarit global)
--    pour chacun de ces 2 nouveaux types.
--
-- Les 2 nouveaux types (suffixe _REPORT pour les distinguer des types historiques
-- utilisés par les URLs legacy /reporting/exports/{statement}?format=pdf) :
--   LETTERING_REPORT — liste des lettrages d'un tiers (endpoint /third-parties/lettrage/pdf)
--   CNSS_RETURN_REPORT — bordereau CNSS/OFATMA/AST agrégé par employé sur une période
--                       (endpoint /payroll/cnss-return/pdf)
--
-- NOTE 1 : Flyway placeholder-replacement est désactivé (application.yml) — les ${...}
--          Thymeleaf ne sont PAS interprétés par Spring.
-- NOTE 2 : Les templates sont des FRAGMENTS HTML (pas de <html>/<head>/<body>) car
--          DocumentGenerationService.renderPdf() enveloppe déjà le contenu dans une
--          structure complète <!DOCTYPE html><html>...</html>.
-- NOTE 3 : Caractères accentués évités pour compatibilité XML (openhtmltopdf).
-- NOTE 4 : ON CONFLICT DO NOTHING pour idempotency (même pattern que V88).
-- NOTE 5 : v2.4.2 fix — on re-élargit la colonne à VARCHAR(50) au cas où V88
--          n'aurait pas été appliquée (ALTER COLUMN TYPE est idempotent).

-- ─── 0. Élargissement défensif de document_type (idempotent) ─────────────────
ALTER TABLE document_template ALTER COLUMN document_type TYPE VARCHAR(50);
ALTER TABLE generated_document ALTER COLUMN document_type TYPE VARCHAR(50);

-- ─── 1. Mise à jour des CHECK constraints ────────────────────────────────────

ALTER TABLE document_template DROP CONSTRAINT IF EXISTS chk_dt_document_type;
ALTER TABLE document_template ADD CONSTRAINT chk_dt_document_type CHECK (document_type IN (
    'INVOICE','CREDIT_NOTE','DONATION_RECEIPT','BALANCE_SHEET',
    'INCOME_STATEMENT','GENERAL_LEDGER','DONOR_REPORT','PAYSLIP',
    -- step2-backend — Reports Hub v2.4.0
    'BALANCE_SHEET_REPORT','INCOME_STATEMENT_REPORT','CASH_FLOW_STATEMENT_REPORT',
    'STATEMENT_OF_CHANGES_IN_EQUITY_REPORT','TRIAL_BALANCE_REPORT','LEDGER_REPORT',
    'AGED_BALANCE_RECEIVABLES_REPORT','AGED_BALANCE_PAYABLES_REPORT',
    'CORPORATE_TAX_PROJECTION_REPORT','VAT_DECLARATION_REPORT','TCA_DECLARATION_REPORT',
    'PAYROLL_SUMMARY_REPORT',
    -- step7-backend — Reports Hub v2.5.0
    'LETTERING_REPORT','CNSS_RETURN_REPORT'
));

ALTER TABLE generated_document DROP CONSTRAINT IF EXISTS chk_gd_document_type;
ALTER TABLE generated_document ADD CONSTRAINT chk_gd_document_type CHECK (document_type IN (
    'INVOICE','CREDIT_NOTE','DONATION_RECEIPT','BALANCE_SHEET',
    'INCOME_STATEMENT','GENERAL_LEDGER','DONOR_REPORT','PAYSLIP',
    -- step2-backend — Reports Hub v2.4.0
    'BALANCE_SHEET_REPORT','INCOME_STATEMENT_REPORT','CASH_FLOW_STATEMENT_REPORT',
    'STATEMENT_OF_CHANGES_IN_EQUITY_REPORT','TRIAL_BALANCE_REPORT','LEDGER_REPORT',
    'AGED_BALANCE_RECEIVABLES_REPORT','AGED_BALANCE_PAYABLES_REPORT',
    'CORPORATE_TAX_PROJECTION_REPORT','VAT_DECLARATION_REPORT','TCA_DECLARATION_REPORT',
    'PAYROLL_SUMMARY_REPORT',
    -- step7-backend — Reports Hub v2.5.0
    'LETTERING_REPORT','CNSS_RETURN_REPORT'
));

-- ─── 2. Seed des 2 nouveaux templates HTML Thymeleaf ─────────────────────────

INSERT INTO document_template (company_id, document_type, html_template, active, is_default)
VALUES

(NULL, 'LETTERING_REPORT',
'<h1>Lettrage - rapprochement d ecritures</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">-</span> - <span th:text="${to}">-</span></p>
<h2>Synthese</h2>
<table>
<tr><td>Nombre total de lettrages</td><td th:text="${totalLettrages}">0</td></tr>
<tr><td>Lettrages equilibres (FULL)</td><td th:text="${totalFull}">0</td></tr>
<tr><td>Lettrages partiels (PARTIAL)</td><td th:text="${totalPartial}">0</td></tr>
<tr><td>Montant total lettre</td><td th:text="${totalMatchedAmount}">0</td></tr>
</table>
<h2>Detail des lettrages</h2>
<table>
<thead><tr><th>Tiers</th><th>Compte</th><th>Code lettrage</th><th>Date</th><th>Montant</th><th>Statut</th><th>Nombre de lignes</th></tr></thead>
<tbody>
<tr th:each="line : ${lines}">
<td th:text="${line.thirdPartyName}"></td>
<td th:text="${line.accountCode}"></td>
<td th:text="${line.matchCode}"></td>
<td th:text="${line.matchedAt}"></td>
<td th:text="${line.matchedAmount}"></td>
<td th:text="${line.status}"></td>
<td th:text="${line.entryCount}"></td>
</tr>
</tbody>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'CNSS_RETURN_REPORT',
'<h1>Bordereau CNSS / OFATMA / AST</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${period}">-</span></p>
<p th:if="${fiscalYearLabel}"><small>Exercice: <span th:text="${fiscalYearLabel}">-</span></small></p>
<p>Devise: <span th:text="${currency}">-</span></p>
<h2>Totaux</h2>
<table>
<tr><td>Total salaires bruts</td><td th:text="${totalGross}">0</td></tr>
<tr><td>Total assiette imposable</td><td th:text="${totalTaxableBase}">0</td></tr>
<tr><td>Total cotisations salariales (CNSS + OFATMA + AST)</td><td th:text="${totalEmployeeContribution}">0</td></tr>
<tr><td>Total cotisations patronales (CNSS + OFATMA + AST)</td><td th:text="${totalEmployerContribution}">0</td></tr>
</table>
<h2>Detail par employe</h2>
<table>
<thead><tr><th>Employe</th><th>Numero CNSS</th><th>Salaire brut</th><th>Assiette imposable</th><th>Cotisations salariales</th><th>Cotisations patronales</th></tr></thead>
<tbody>
<tr th:each="line : ${lines}">
<td th:text="${line.employeeName}"></td>
<td th:text="${line.cnssNumber}"></td>
<td th:text="${line.grossSalary}"></td>
<td th:text="${line.taxableBase}"></td>
<td th:text="${line.employeeContribution}"></td>
<td th:text="${line.employerContribution}"></td>
</tr>
</tbody>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true)

ON CONFLICT DO NOTHING;
