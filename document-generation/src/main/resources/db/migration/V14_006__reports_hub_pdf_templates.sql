-- V14_006 — reports hub pdf templates
-- V88 — Reports Hub v2.4.0 (step2-backend)
-- 0. Élargit document_type de VARCHAR(25) à VARCHAR(50) sur les 2 tables
-- (5 des 12 nouveaux DocumentType dépassent 25 caractères :
-- CASH_FLOW_STATEMENT_REPORT=26, AGED_BALANCE_PAYABLES_REPORT=28,
-- AGED_BALANCE_RECEIVABLES_REPORT=31, CORPORATE_TAX_PROJECTION_REPORT=31,
-- STATEMENT_OF_CHANGES_IN_EQUITY_REPORT=36).
-- 1. Élargit les CHECK chk_dt_document_type et chk_gd_document_type pour autoriser
-- les 12 nouveaux DocumentType ajoutés pour les exports PDF du Reports Hub mobile.
-- 2. Insère un template HTML Thymeleaf par défaut (company_id = NULL — gabarit global)
-- pour chacun de ces 12 nouveaux types.
-- Les 12 nouveaux types (suffixe _REPORT pour les distinguer des types historiques
-- utilisés par les URLs legacy /reporting/exports/{statement}?format=pdf) :
-- BALANCE_SHEET_REPORT, INCOME_STATEMENT_REPORT, CASH_FLOW_STATEMENT_REPORT,
-- STATEMENT_OF_CHANGES_IN_EQUITY_REPORT, TRIAL_BALANCE_REPORT, LEDGER_REPORT,
-- AGED_BALANCE_RECEIVABLES_REPORT, AGED_BALANCE_PAYABLES_REPORT,
-- CORPORATE_TAX_PROJECTION_REPORT, VAT_DECLARATION_REPORT, TCA_DECLARATION_REPORT,
-- PAYROLL_SUMMARY_REPORT.
-- NOTE 1 : Flyway placeholder-replacement est désactivé (application.yml) — les ${...}
-- Thymeleaf ne sont PAS interprétés par Spring.
-- NOTE 2 : Les templates sont des FRAGMENTS HTML (pas de <html>/<head>/<body>) car
-- DocumentGenerationService.renderPdf() enveloppe déjà le contenu dans une
-- structure complète <!DOCTYPE html><html>...</html>.
-- NOTE 3 : Caractères accentués évités pour compatibilité XML (openhtmltopdf).

-- ─── 0. Élargissement de la colonne document_type (VARCHAR(25) → VARCHAR(50)) ──
-- v2.4.2 fix : avant cette migration, plusieurs nouveaux DocumentType dépassaient
-- 25 caractères et l'INSERT échouait avec "value too long for type character
-- varying(25)". On élargit à 50 (largement suffisant pour tous les types actuels
-- + futurs).


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
    'PAYROLL_SUMMARY_REPORT'
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
    'PAYROLL_SUMMARY_REPORT'
));

-- ─── 2. Seed des 12 nouveaux templates HTML Thymeleaf ─────────────────────────

INSERT INTO document_template (company_id, document_type, html_template, active, is_default)
VALUES

(NULL, 'BALANCE_SHEET_REPORT',
'<h1>Bilan</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Au <span th:text="${asOf}">2024-12-31</span></p>
<h2>Actif</h2>
<table>
<thead><tr><th>Compte</th><th>Libelle</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="sec : ${assets}">
<tr><td colspan="3"><strong th:text="''+${sec.reportingClass}+'' - ''+${sec.reportingSubcategory}+'' (''+${sec.subtotal}+'')''"></strong></td></tr>
<tr th:each="line : ${sec.lines}">
<td th:text="${line.accountCode}"></td>
<td th:text="${line.accountLabel}"></td>
<td th:text="${line.amount}"></td>
</tr>
</tr>
</tbody>
<tfoot><tr><td colspan="2">Total Actif</td><td th:text="${totalAssets}">0</td></tr></tfoot>
</table>
<h2>Passif</h2>
<table>
<thead><tr><th>Compte</th><th>Libelle</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="sec : ${liabilities}">
<tr><td colspan="3"><strong th:text="''+${sec.reportingClass}+'' - ''+${sec.reportingSubcategory}+'' (''+${sec.subtotal}+'')''"></strong></td></tr>
<tr th:each="line : ${sec.lines}">
<td th:text="${line.accountCode}"></td>
<td th:text="${line.accountLabel}"></td>
<td th:text="${line.amount}"></td>
</tr>
</tr>
</tbody>
<tfoot><tr><td colspan="2">Total Passif</td><td th:text="${totalLiabilities}">0</td></tr></tfoot>
</table>
<h2>Capitaux propres</h2>
<table>
<thead><tr><th>Compte</th><th>Libelle</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="sec : ${equity}">
<tr><td colspan="3"><strong th:text="''+${sec.reportingClass}+'' - ''+${sec.reportingSubcategory}+'' (''+${sec.subtotal}+'')''"></strong></td></tr>
<tr th:each="line : ${sec.lines}">
<td th:text="${line.accountCode}"></td>
<td th:text="${line.accountLabel}"></td>
<td th:text="${line.amount}"></td>
</tr>
</tr>
</tbody>
<tfoot><tr><td colspan="2">Total Capitaux propres</td><td th:text="${totalEquity}">0</td></tr></tfoot>
</table>
<p th:if="${balanced}">Bilan equilibre.</p>
<p th:unless="${balanced}">Bilan desequilibre (exercice non cloture).</p>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'INCOME_STATEMENT_REPORT',
'<h1>Compte de resultat</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">2024-01-01</span> - <span th:text="${to}">2024-12-31</span></p>
<h2>Produits</h2>
<table>
<thead><tr><th>Compte</th><th>Libelle</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="line : ${productsLines}">
<td th:text="${line.accountCode}"></td>
<td th:text="${line.accountLabel}"></td>
<td th:text="${line.amount}"></td>
</tr>
</tbody>
<tfoot><tr><td colspan="2">Total Produits</td><td th:text="${totalProducts}">0</td></tr></tfoot>
</table>
<h2>Charges</h2>
<table>
<thead><tr><th>Compte</th><th>Libelle</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="line : ${chargesLines}">
<td th:text="${line.accountCode}"></td>
<td th:text="${line.accountLabel}"></td>
<td th:text="${line.amount}"></td>
</tr>
</tbody>
<tfoot><tr><td colspan="2">Total Charges</td><td th:text="${totalCharges}">0</td></tr></tfoot>
</table>
<p><strong>Resultat net: <span th:text="${netResult}">0</span></strong></p>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'CASH_FLOW_STATEMENT_REPORT',
'<h1>Tableau de flux de tresorerie</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">2024-01-01</span> - <span th:text="${to}">2024-12-31</span></p>
<h2>Activites d exploitation</h2>
<table>
<tr><td>Resultat net</td><td th:text="${operating.netIncome}">0</td></tr>
<tr><td>Amortissements et depreciations</td><td th:text="${operating.depreciationAmortization}">0</td></tr>
<tr><td>Variation creances clients</td><td th:text="${operating.accountsReceivableVariation}">0</td></tr>
<tr><td>Variation stocks</td><td th:text="${operating.inventoryVariation}">0</td></tr>
<tr><td>Variation fournisseurs</td><td th:text="${operating.accountsPayableVariation}">0</td></tr>
<tr><td>Autres variations BFR</td><td th:text="${operating.otherWorkingCapitalVariation}">0</td></tr>
<tr><td><strong>Flux net d exploitation</strong></td><td th:text="${operating.total}">0</td></tr>
</table>
<h2>Activites d investissement</h2>
<table>
<tr><td>Acquisitions immobilisations</td><td th:text="${investing.fixedAssetsAcquisitions}">0</td></tr>
<tr><td>Cessions immobilisations</td><td th:text="${investing.fixedAssetsDisposals}">0</td></tr>
<tr><td>Autres flux investissement</td><td th:text="${investing.otherInvestingFlows}">0</td></tr>
<tr><td><strong>Flux net d investissement</strong></td><td th:text="${investing.total}">0</td></tr>
</table>
<h2>Activites de financement</h2>
<table>
<tr><td>Variation capital</td><td th:text="${financing.capitalVariation}">0</td></tr>
<tr><td>Variation emprunts</td><td th:text="${financing.loansVariation}">0</td></tr>
<tr><td>Dividendes verses</td><td th:text="${financing.dividendsPaid}">0</td></tr>
<tr><td>Autres flux financement</td><td th:text="${financing.otherFinancingFlows}">0</td></tr>
<tr><td><strong>Flux net de financement</strong></td><td th:text="${financing.total}">0</td></tr>
</table>
<h2>Synthese</h2>
<table>
<tr><td>Variation de tresorerie</td><td th:text="${netCashFlow}">0</td></tr>
<tr><td>Tresorerie ouverture</td><td th:text="${openingCash}">0</td></tr>
<tr><td>Tresorerie clôture</td><td th:text="${closingCash}">0</td></tr>
</table>
<p th:if="${balanced}">Flux equilibre.</p>
<p th:unless="${balanced}">Flux desequilibre.</p>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'STATEMENT_OF_CHANGES_IN_EQUITY_REPORT',
'<h1>Tableau de variation des capitaux propres (IAS 1.106)</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">2024-01-01</span> - <span th:text="${to}">2024-12-31</span></p>
<table>
<tr><td>Capitaux propres d ouverture</td><td th:text="${openingEquity}">0</td></tr>
<tr><td>+ Resultat net de l exercice</td><td th:text="${netIncome}">0</td></tr>
<tr><td>+ Autres elements globaux (OCI)</td><td th:text="${otherComprehensiveIncome}">0</td></tr>
<tr><td>+ Emissions de capital</td><td th:text="${capitalIssued}">0</td></tr>
<tr><td>- Rachats d actions (treasury)</td><td th:text="${treasurySharesPurchased}">0</td></tr>
<tr><td>- Dividendes distribues</td><td th:text="${dividendsDistributed}">0</td></tr>
<tr><td>+/- Autres mouvements</td><td th:text="${otherMovements}">0</td></tr>
<tr><td><strong>= Capitaux propres de cloture</strong></td><td th:text="${closingEquity}">0</td></tr>
</table>
<h2>Detail des mouvements</h2>
<table>
<thead><tr><th>Date</th><th>Compte</th><th>Description</th><th>Categorie</th><th>Debit</th><th>Credit</th></tr></thead>
<tbody>
<tr th:each="mvt : ${movements}">
<td th:text="${mvt.date}"></td>
<td th:text="${mvt.accountCode}"></td>
<td th:text="${mvt.description}"></td>
<td th:text="${mvt.category}"></td>
<td th:text="${mvt.debit}"></td>
<td th:text="${mvt.credit}"></td>
</tr>
</tbody>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'TRIAL_BALANCE_REPORT',
'<h1>Balance generale</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${period}">-</span></p>
<table>
<thead><tr><th>Code compte</th><th>Libelle</th><th>Total debit</th><th>Total credit</th><th>Solde</th></tr></thead>
<tbody>
<tr th:each="line : ${lines}">
<td th:text="${line.accountCode}"></td>
<td th:text="${line.accountLabel}"></td>
<td th:text="${line.totalDebit}"></td>
<td th:text="${line.totalCredit}"></td>
<td th:text="${line.balance}"></td>
</tr>
</tbody>
<tfoot>
<tr>
<td colspan="2">Totaux</td>
<td th:text="${totalDebit}">0</td>
<td th:text="${totalCredit}">0</td>
<td th:text="${totalBalance}">0</td>
</tr>
</tfoot>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'LEDGER_REPORT',
'<h1>Grand livre</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${period}">-</span></p>
<p>Compte: <span th:text="${accountLabel}">-</span> (<span th:text="${accountCode}">-</span>)</p>
<table>
<thead><tr><th>Date</th><th>Reference</th><th>Description</th><th>Debit</th><th>Credit</th><th>Solde cumule</th></tr></thead>
<tbody>
<tr th:each="line : ${lines}">
<td th:text="${line.entryDate}"></td>
<td th:text="${line.reference}"></td>
<td th:text="${line.description}"></td>
<td th:text="${line.debit}"></td>
<td th:text="${line.credit}"></td>
<td th:text="${line.runningBalance}"></td>
</tr>
</tbody>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'AGED_BALANCE_RECEIVABLES_REPORT',
'<h1>Balance agee clients</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Au <span th:text="${asOf}">-</span></p>
<table>
<thead><tr><th>Tranche</th><th>Montant</th></tr></thead>
<tbody>
<tr><td>Non echu (courant)</td><td th:text="${current}">0</td></tr>
<tr><td>0 a 30 jours</td><td th:text="${d0_30}">0</td></tr>
<tr><td>31 a 60 jours</td><td th:text="${d31_60}">0</td></tr>
<tr><td>61 a 90 jours</td><td th:text="${d61_90}">0</td></tr>
<tr><td>Plus de 90 jours</td><td th:text="${d90_plus}">0</td></tr>
</tbody>
<tfoot>
<tr><td><strong>Total du</strong></td><td th:text="${totalBalanceDue}">0</td></tr>
<tr><td>Nombre de factures</td><td th:text="${invoiceCount}">0</td></tr>
</tfoot>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'AGED_BALANCE_PAYABLES_REPORT',
'<h1>Balance agee fournisseurs</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Au <span th:text="${asOf}">-</span></p>
<table>
<thead><tr><th>Tranche</th><th>Montant</th></tr></thead>
<tbody>
<tr><td>Non echu (courant)</td><td th:text="${current}">0</td></tr>
<tr><td>0 a 30 jours</td><td th:text="${d0_30}">0</td></tr>
<tr><td>31 a 60 jours</td><td th:text="${d31_60}">0</td></tr>
<tr><td>61 a 90 jours</td><td th:text="${d61_90}">0</td></tr>
<tr><td>Plus de 90 jours</td><td th:text="${d90_plus}">0</td></tr>
</tbody>
<tfoot>
<tr><td><strong>Total du</strong></td><td th:text="${totalBalanceDue}">0</td></tr>
<tr><td>Nombre de factures</td><td th:text="${invoiceCount}">0</td></tr>
</tfoot>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'CORPORATE_TAX_PROJECTION_REPORT',
'<h1>Projection Impot sur les Societes (IS)</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Exercice: <span th:text="${from}">-</span> - <span th:text="${to}">-</span></p>
<h2>Resultat fiscal</h2>
<table>
<tr><td>Resultat comptable</td><td th:text="${accountingResult}">0</td></tr>
<tr><td>+ Reintegrations totales</td><td th:text="${adjustments.totalAdditions}">0</td></tr>
<tr><td>- Deductions totales</td><td th:text="${adjustments.totalDeductions}">0</td></tr>
<tr><td><strong>= Resultat fiscal</strong></td><td th:text="${taxableResult}">0</td></tr>
</table>
<h2>Calcul de l IS</h2>
<table>
<tr><td>Taux applique</td><td th:text="${appliedRate}">0</td></tr>
<tr><td>IS brut</td><td th:text="${corporateTaxBrut}">0</td></tr>
<tr><td>- Credits d impot</td><td th:text="${taxCredits}">0</td></tr>
<tr><td><strong>= IS net</strong></td><td th:text="${corporateTaxNet}">0</td></tr>
</table>
<h2>Acomptes et solde</h2>
<table>
<thead><tr><th>Echeance</th><th>Libelle</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="inst : ${installments}">
<td th:text="${inst.dueDate}"></td>
<td th:text="${inst.label}"></td>
<td th:text="${inst.amount}"></td>
</tr>
</tbody>
<tfoot><tr><td colspan="2">Solde a verser (15 mai N+1)</td><td th:text="${balanceDue}">0</td></tr></tfoot>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'VAT_DECLARATION_REPORT',
'<h1>Declaration TVA</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">-</span> - <span th:text="${to}">-</span></p>
<h2>TVA collectee</h2>
<table>
<thead><tr><th>Code</th><th>Libelle</th><th>Taux</th><th>Base imposable</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="line : ${collectedLines}">
<td th:text="${line.taxCode}"></td>
<td th:text="${line.taxLabel}"></td>
<td th:text="${line.rate}"></td>
<td th:text="${line.taxableBase}"></td>
<td th:text="${line.taxAmount}"></td>
</tr>
</tbody>
<tfoot><tr><td colspan="4">Total TVA collectee</td><td th:text="${totalTaxCollected}">0</td></tr></tfoot>
</table>
<h2>TVA deductible</h2>
<table>
<thead><tr><th>Code</th><th>Libelle</th><th>Taux</th><th>Base imposable</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="line : ${deductibleLines}">
<td th:text="${line.taxCode}"></td>
<td th:text="${line.taxLabel}"></td>
<td th:text="${line.rate}"></td>
<td th:text="${line.taxableBase}"></td>
<td th:text="${line.taxAmount}"></td>
</tr>
</tbody>
<tfoot><tr><td colspan="4">Total TVA deductible</td><td th:text="${totalTaxDeductible}">0</td></tr></tfoot>
</table>
<h2>Synthese</h2>
<table>
<tr><td>TVA due (collectee - deductible)</td><td th:text="${taxDue}">0</td></tr>
<tr><td>Credit reporte (M-1)</td><td th:text="${taxCreditCarriedForward}">0</td></tr>
<tr><td>Credit a reporter (M+1)</td><td th:text="${taxCreditToCarryForward}">0</td></tr>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'TCA_DECLARATION_REPORT',
'<h1>Declaration TCA (Taxe sur le Chiffre d Affaires - Haiti art. 196)</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">-</span> - <span th:text="${to}">-</span></p>
<h2>TCA collectee</h2>
<table>
<thead><tr><th>Code</th><th>Libelle</th><th>Taux</th><th>Base imposable</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="line : ${collectedLines}">
<td th:text="${line.taxCode}"></td>
<td th:text="${line.taxLabel}"></td>
<td th:text="${line.rate}"></td>
<td th:text="${line.taxableBase}"></td>
<td th:text="${line.taxAmount}"></td>
</tr>
</tbody>
<tfoot><tr><td colspan="4">Total TCA collectee</td><td th:text="${totalTaxCollected}">0</td></tr></tfoot>
</table>
<h2>Synthese</h2>
<table>
<tr><td>TCA due</td><td th:text="${taxDue}">0</td></tr>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true),

(NULL, 'PAYROLL_SUMMARY_REPORT',
'<h1>Synthese de paie</h1>
<p>Entreprise: <span th:text="${companyName}">-</span></p>
<p>Periode: <span th:text="${from}">-</span> - <span th:text="${to}">-</span></p>
<h2>Totaux</h2>
<table>
<tr><td>Nombre de campagnes</td><td th:text="${runCount}">0</td></tr>
<tr><td>Nombre de bulletins</td><td th:text="${payslipCount}">0</td></tr>
<tr><td>Total salaires bruts</td><td th:text="${totalGross}">0</td></tr>
<tr><td>Total salaires nets</td><td th:text="${totalNet}">0</td></tr>
<tr><td>Total charges patronales</td><td th:text="${totalEmployerContributions}">0</td></tr>
</table>
<h2>Detail par campagne</h2>
<table>
<thead><tr><th>Periode</th><th>Statut</th><th>Bulletins</th><th>Brut</th><th>Net</th><th>Charges patronales</th></tr></thead>
<tbody>
<tr th:each="run : ${runs}">
<td th:text="''+${run.periodYear}+''-''+${run.periodMonth}"></td>
<td th:text="${run.status}"></td>
<td th:text="${run.payslipCount}"></td>
<td th:text="${run.totalGross}"></td>
<td th:text="${run.totalNet}"></td>
<td th:text="${run.totalEmployerContributions}"></td>
</tr>
</tbody>
</table>
<p class="footer">Genere le <span th:text="${generationDate}">-</span>.</p>', true, true)

ON CONFLICT DO NOTHING;
