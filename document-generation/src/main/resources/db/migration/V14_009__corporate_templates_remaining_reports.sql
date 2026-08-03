-- V14_009 — Extension Corporate sobre aux rapports restants (audit PDF v9.4)
-- Applique le design "Corporate sobre" aux 5 templates restants :
--   TRIAL_BALANCE_REPORT, LEDGER_REPORT, VAT_DECLARATION_REPORT,
--   TCA_DECLARATION_REPORT, CORPORATE_TAX_PROJECTION_REPORT


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. TRIAL_BALANCE_REPORT (Balance générale)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET html_template = $TPL$
<div class="doc-header">
    <div><img th:if="${companyLogoBase64}" class="company-logo" th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/><h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1></div>
    <div class="company-info"><div class="name" th:text="${companyName}">Nom</div><div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div></div>
</div>
<div class="doc-title-block"><h1>BALANCE GÉNÉRALE</h1><div class="subtitle"><span th:text="${period}">Période</span><span th:if="${generationDate}"> · Généré le <span th:text="${generationDate}"></span></span></div></div>
<table class="avoid-break">
    <thead><tr><th>Compte</th><th>Libellé</th><th class="text-right">Débit</th><th class="text-right">Crédit</th><th class="text-right">Solde</th></tr></thead>
    <tbody><tr th:each="line : ${lines}"><td th:text="${line.accountCode}">101</td><td th:text="${line.accountLabel}">Capital</td><td class="amount" th:text="${line.totalDebit}">0</td><td class="amount" th:text="${line.totalCredit}">0</td><td class="amount" th:text="${line.balance}">0</td></tr></tbody>
    <tfoot><tr class="totals-row"><td colspan="2" class="text-right">Totaux</td><td class="amount" th:text="${totalDebit}">0</td><td class="amount" th:text="${totalCredit}">0</td><td class="amount" th:text="${totalBalance}">0</td></tr></tfoot>
</table>
<div class="doc-footer">Balance générale — JOAccountant v9.4</div>
$TPL$
WHERE document_type = 'TRIAL_BALANCE_REPORT' AND company_id IS NULL AND country_code IS NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. LEDGER_REPORT (Grand livre)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET html_template = $TPL$
<div class="doc-header">
    <div><img th:if="${companyLogoBase64}" class="company-logo" th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/><h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1></div>
    <div class="company-info"><div class="name" th:text="${companyName}">Nom</div><div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div></div>
</div>
<div class="doc-title-block"><h1>GRAND LIVRE</h1><div class="subtitle">Compte: <strong th:text="${accountCode} + ' — ' + ${accountLabel}">101 — Capital</strong><span th:if="${period}"> · <span th:text="${period}"></span></span></div></div>
<table class="avoid-break">
    <thead><tr><th>Date</th><th>Référence</th><th>Description</th><th class="text-right">Débit</th><th class="text-right">Crédit</th><th class="text-right">Solde</th></tr></thead>
    <tbody><tr th:each="line : ${lines}"><td th:text="${line.entryDate}">2026-01-15</td><td th:text="${line.reference}">VT-001</td><td th:text="${line.description}">Desc</td><td class="amount" th:text="${line.debit}">0</td><td class="amount" th:text="${line.credit}">0</td><td class="amount" th:text="${line.runningBalance}">0</td></tr></tbody>
</table>
<div class="doc-footer">Grand livre — JOAccountant v9.4</div>
$TPL$
WHERE document_type = 'LEDGER_REPORT' AND company_id IS NULL AND country_code IS NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. VAT_DECLARATION_REPORT (Déclaration TVA)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET html_template = $TPL$
<div class="doc-header">
    <div><img th:if="${companyLogoBase64}" class="company-logo" th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/><h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1></div>
    <div class="company-info"><div class="name" th:text="${companyName}">Nom</div><div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div></div>
</div>
<div class="doc-title-block"><h1>DÉCLARATION TVA</h1><div class="subtitle">Du <strong th:text="${from}"></strong> au <strong th:text="${to}"></strong><span th:if="${generationDate}"> · Généré le <span th:text="${generationDate}"></span></span></div></div>
<h2>TVA collectée</h2>
<table class="avoid-break">
    <thead><tr><th>Taux</th><th>Base HT</th><th class="text-right">TVA</th></tr></thead>
    <tbody><tr th:each="line : ${collectedLines}"><td th:text="${line.label}">TVA 10%</td><td class="amount" th:text="${line.base}">0</td><td class="amount" th:text="${line.tax}">0</td></tr></tbody>
    <tfoot><tr class="totals-row"><td colspan="2" class="text-right">Total collecté</td><td class="amount" th:text="${totalTaxCollected}">0</td></tr></tfoot>
</table>
<h2>TVA déductible</h2>
<table class="avoid-break">
    <thead><tr><th>Taux</th><th>Base HT</th><th class="text-right">TVA</th></tr></thead>
    <tbody><tr th:each="line : ${deductibleLines}"><td th:text="${line.label}">TVA 10%</td><td class="amount" th:text="${line.base}">0</td><td class="amount" th:text="${line.tax}">0</td></tr></tbody>
    <tfoot><tr class="totals-row"><td colspan="2" class="text-right">Total déductible</td><td class="amount" th:text="${totalTaxDeductible}">0</td></tr></tfoot>
</table>
<div class="info-box info-box-primary">
    <p><strong>TVA due :</strong> <span class="amount" th:text="${taxDue}">0.00</span></p>
    <p th:if="${taxCreditCarriedForward}"><strong>Crédit reporté (période précédente) :</strong> <span class="amount" th:text="${taxCreditCarriedForward}">0.00</span></p>
    <p th:if="${taxCreditToCarryForward}"><strong>Crédit à reporter (période suivante) :</strong> <span class="amount" th:text="${taxCreditToCarryForward}">0.00</span></p>
</div>
<div class="mention-legal">Déclaration TVA — Code Fiscal Haïti art. 191 (TVA 10%). TVA collectée - TVA déductible - crédit reporté = TVA due.</div>
<div class="doc-footer">Déclaration TVA — JOAccountant v9.4</div>
$TPL$
WHERE document_type = 'VAT_DECLARATION_REPORT' AND company_id IS NULL AND country_code IS NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. TCA_DECLARATION_REPORT (Déclaration TCA Haïti)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET html_template = $TPL$
<div class="doc-header">
    <div><img th:if="${companyLogoBase64}" class="company-logo" th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/><h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1></div>
    <div class="company-info"><div class="name" th:text="${companyName}">Nom</div><div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div></div>
</div>
<div class="doc-title-block"><h1>DÉCLARATION TCA</h1><div class="subtitle">Du <strong th:text="${from}"></strong> au <strong th:text="${to}"></strong></div></div>
<h2>TCA collectée par taux</h2>
<table class="avoid-break">
    <thead><tr><th>Taux</th><th>Base HT</th><th class="text-right">TCA</th></tr></thead>
    <tbody><tr th:each="line : ${collectedLines}"><td th:text="${line.label}">TCA 2% banque</td><td class="amount" th:text="${line.base}">0</td><td class="amount" th:text="${line.tax}">0</td></tr></tbody>
    <tfoot><tr class="totals-row"><td colspan="2" class="text-right">Total TCA due</td><td class="amount" th:text="${taxDue}">0</td></tr></tfoot>
</table>
<div class="mention-legal">Déclaration TCA — Code Fiscal Haïti art. 196/197 (TCA 2% banque, 5% telecom, 10% services). La TCA est cumulative avec la TVA sur une même opération.</div>
<div class="doc-footer">Déclaration TCA — JOAccountant v9.4</div>
$TPL$
WHERE document_type = 'TCA_DECLARATION_REPORT' AND company_id IS NULL AND country_code IS NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. CORPORATE_TAX_PROJECTION_REPORT (Projection IS)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET html_template = $TPL$
<div class="doc-header">
    <div><img th:if="${companyLogoBase64}" class="company-logo" th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/><h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1></div>
    <div class="company-info"><div class="name" th:text="${companyName}">Nom</div><div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div></div>
</div>
<div class="doc-title-block"><h1>PROJECTION IMPÔT SUR LES SOCIÉTÉS</h1><div class="subtitle">Du <strong th:text="${from}"></strong> au <strong th:text="${to}"></strong></div></div>
<table class="avoid-break">
    <tbody>
        <tr><td>Résultat comptable</td><td class="amount" th:text="${accountingResult}">0</td></tr>
        <tr><td>Total réintégrations</td><td class="amount" th:text="${adjustments.totalAdditions}">0</td></tr>
        <tr><td>Total déductions</td><td class="amount" th:text="${adjustments.totalDeductions}">0</td></tr>
        <tr class="totals-row"><td>Résultat fiscal</td><td class="amount" th:text="${taxableResult}">0</td></tr>
        <tr><td>Taux appliqué</td><td class="amount" th:text="${appliedRate} + '%'">30%</td></tr>
        <tr class="totals-row"><td>IS brut</td><td class="amount" th:text="${corporateTaxBrut}">0</td></tr>
        <tr><td>Crédits d'impôt</td><td class="amount" th:text="${taxCredits}">0</td></tr>
        <tr class="totals-row"><td>IS net</td><td class="amount" th:text="${corporateTaxNet}">0</td></tr>
    </tbody>
</table>
<h2 th:if="${installments}">Acomptes</h2>
<table th:if="${installments}" class="avoid-break">
    <thead><tr><th>Échéance</th><th>Libellé</th><th class="text-right">Montant</th></tr></thead>
    <tbody><tr th:each="inst : ${installments}"><td th:text="${inst.dueDate}">2026-03-15</td><td th:text="${inst.label}">Acompte</td><td class="amount" th:text="${inst.amount}">0</td></tr></tbody>
    <tfoot><tr class="totals-row"><td colspan="2" class="text-right">Solde à verser</td><td class="amount" th:text="${balanceDue}">0</td></tr></tfoot>
</table>
<div class="mention-legal">Projection IS — Code Fiscal Haïti art. 195 (IS 30% standard, 15% zone franche, 0% ONG). Acomptes mensuels 1% sur encaissements (art. 5).</div>
<div class="doc-footer">Projection IS — JOAccountant v9.4</div>
$TPL$
WHERE document_type = 'CORPORATE_TAX_PROJECTION_REPORT' AND company_id IS NULL AND country_code IS NULL;
