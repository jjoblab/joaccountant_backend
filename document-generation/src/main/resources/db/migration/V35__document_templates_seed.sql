-- V35 — Gabarits de documents par defaut (BALANCE_SHEET, INCOME_STATEMENT,
--        GENERAL_LEDGER, DONOR_REPORT, INVOICE, CREDIT_NOTE, DONATION_RECEIPT, PAYSLIP).
--
-- Avant V35, la table document_template etait creee (V13_001) mais aucun template
-- n'etait insere en base. Le service DocumentGenerationService.generateDocument()
-- levait alors l'exception TEMPLATE_NOT_FOUND (HTTP 422) a chaque appel.
--
-- V35 corrige ce trou fonctionnel en inserant un template HTML Thymeleaf par defaut
-- pour chaque DocumentType, avec company_id = NULL (gabarit global).
--
-- NOTE 1 : Flyway placeholder-replacement est desactive (application.yml) pour eviter
--          que les ${...} Thymeleaf ne soient interpretes comme des placeholders Spring.
-- NOTE 2 : Les templates sont des FRAGMENTS HTML (pas de <html>/<head>/<body>) car
--          DocumentGenerationService.renderPdf() enveloppe deja le contenu dans une
--          structure complete <!DOCTYPE html><html>...</html>. Mettre un second
--          <!DOCTYPE> casserait le parsing XML de openhtmltopdf.
-- NOTE 3 : Les caracteres accentues sont evites pour assurer la compatibilite XML.

INSERT INTO document_template (company_id, document_type, html_template, active, is_default)
VALUES
(NULL, 'BALANCE_SHEET',
'<h1>Bilan</h1>
<p>Au <span th:text="${asOf}">2024-12-31</span></p>
<h2>Actif</h2>
<table><tr><td>Total Actif</td><td th:text="${totalAssets}">0</td></tr></table>
<h2>Passif</h2>
<table>
<tr><td>Total Passif</td><td th:text="${totalLiabilities}">0</td></tr>
<tr><td>Capitaux propres</td><td th:text="${totalEquity}">0</td></tr>
</table>
<p th:if="${balanced}">Bilan equilibre</p>
<p th:unless="${balanced}">Bilan desequilibre</p>', true, true),

(NULL, 'INCOME_STATEMENT',
'<h1>Compte de resultat</h1>
<p>Periode: <span th:text="${from}">2024-01-01</span> - <span th:text="${to}">2024-12-31</span></p>
<table>
<tr><td>Total Produits</td><td th:text="${totalProducts}">0</td></tr>
<tr><td>Total Charges</td><td th:text="${totalCharges}">0</td></tr>
<tr><td>Resultat net</td><td th:text="${netResult}">0</td></tr>
</table>', true, true),

(NULL, 'GENERAL_LEDGER',
'<h1>Grand livre</h1>
<table>
<thead><tr><th>Date</th><th>Reference</th><th>Description</th><th>Compte</th><th>Debit</th><th>Credit</th></tr></thead>
<tbody>
<tr th:each="line : ${lines}">
<td th:text="${line.date}"></td>
<td th:text="${line.reference}"></td>
<td th:text="${line.description}"></td>
<td th:text="${line.accountCode}"></td>
<td th:text="${line.debit}"></td>
<td th:text="${line.credit}"></td>
</tr>
</tbody>
</table>', true, true),

(NULL, 'DONOR_REPORT',
'<h1>Rapport bailleur</h1>
<p><strong>Projet:</strong> <span th:text="${grantLabel}">Projet</span></p>
<p><strong>Bailleur:</strong> <span th:text="${donorName}">Bailleur</span></p>
<table>
<tr><td>Budget total</td><td th:text="${totalBudget}">0</td></tr>
<tr><td>Depenses engagees</td><td th:text="${totalExpenses}">0</td></tr>
<tr><td>Solde restant</td><td th:text="${balanceRemaining}">0</td></tr>
</table>
<table>
<thead><tr><th>Date</th><th>Description</th><th>Compte</th><th>Montant</th></tr></thead>
<tbody>
<tr th:each="line : ${expenseLines}">
<td th:text="${line.date}"></td>
<td th:text="${line.description}"></td>
<td th:text="${line.accountCode}"></td>
<td th:text="${line.amount}"></td>
</tr>
</tbody>
</table>', true, true),

(NULL, 'INVOICE',
'<h1>Facture <span th:text="${invoiceNumber}">FAC-001</span></h1>
<p>Date: <span th:text="${issueDate}">2024-01-01</span></p>
<p>Client: <span th:text="${clientName}">Client</span></p>
<table>
<thead><tr><th>Description</th><th>Qte</th><th>PU</th><th>Total</th></tr></thead>
<tbody>
<tr th:each="line : ${lines}">
<td th:text="${line.description}"></td>
<td th:text="${line.quantity}"></td>
<td th:text="${line.unitPrice}"></td>
<td th:text="${line.total}"></td>
</tr>
</tbody>
</table>
<p>Total: <span th:text="${total}">0</span> HTG</p>', true, true),

(NULL, 'CREDIT_NOTE',
'<h1>Avoir <span th:text="${creditNoteNumber}">AV-001</span></h1>
<p>Date: <span th:text="${issueDate}">2024-01-01</span></p>
<p>Facture origine: <span th:text="${originalInvoiceNumber}">FAC-001</span></p>
<p>Client: <span th:text="${clientName}">Client</span></p>
<p>Total: <span th:text="${total}">0</span> HTG</p>', true, true),

(NULL, 'DONATION_RECEIPT',
'<h1>Recu de don <span th:text="${receiptNumber}">REC-001</span></h1>
<p>Date: <span th:text="${receiptDate}">2024-01-01</span></p>
<p>Donateur: <span th:text="${donorName}">Donateur</span></p>
<p>Montant: <span th:text="${amount}">0</span> <span th:text="${currency}">HTG</span></p>', true, true),

(NULL, 'PAYSLIP',
'<h1>Bulletin de paie <span th:text="${paySlipNumber}">BUL-001</span></h1>
<p>Employe: <span th:text="${employeeName}">Employe</span></p>
<p>Periode: <span th:text="${periodLabel}">2025-07</span></p>
<table>
<tr><td>Salaire brut</td><td th:text="${grossSalary}">0</td></tr>
<tr><td>Retenues salariales</td><td th:text="${deductions}">0</td></tr>
<tr><td>Salaire net a payer</td><td th:text="${netSalary}">0</td></tr>
<tr><td>Charges patronales</td><td th:text="${employerContributions}">0</td></tr>
</table>', true, true)
ON CONFLICT DO NOTHING;
