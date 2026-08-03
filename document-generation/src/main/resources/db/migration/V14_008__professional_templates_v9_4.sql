-- V14_008 — templates professionnels "Corporate sobre" (audit PDF v9.4)
-- Remplace les templates Haïti existants par un design moderne :
--   - Bleu marine (#1a3a5c) + gris (#6c757d) + espace blanc
--   - Tableaux zébrés + en-tête bleu marine
--   - @page : numéros de page + date génération
--   - Filigrane statut (PAYÉ, EN ATTENTE, etc.)
--   - QR-code de paiement (si paymentUrl fourni)
--   - Logo entreprise (si companyLogoBase64 fourni)
--   - Mentions légales DGI art. 196 (NIF, pénalités 1.5%/mois, indemnité 5000 HTG)
--
-- Les templates sont des FRAGMENTS HTML (pas de <html>/<head>/<body>) — le wrapper
-- et le CSS sont injectés par DocumentGenerationService.renderPdf().
--
-- Approche : UPDATE des templates existants (country_code='HT') avec is_default=TRUE
-- pour qu'ils soient sélectionnés par le fix Dim 3 C1 (lecture country_code).


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. INVOICE (Facture client) — country_code='HT', is_default=TRUE
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET
    is_default = TRUE,
    html_template = $TPL$<!-- Template INVOICE v9.4 — Corporate sobre, DGI art. 196 -->
<div th:if="${watermarkText}" class="watermark" th:text="${watermarkText}"></div>

<!-- En-tête entreprise -->
<div class="doc-header">
    <div>
        <img th:if="${companyLogoBase64}" class="company-logo"
             th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/>
        <h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1>
    </div>
    <div class="company-info">
        <div class="name" th:text="${companyName}">Nom Entreprise</div>
        <div th:if="${companyAddress}" th:text="${companyAddress}"></div>
        <div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div>
        <div th:if="${companySiret}">SIRET: <span th:text="${companySiret}"></span></div>
    </div>
</div>

<!-- Titre document -->
<div class="doc-title-block">
    <h1>FACTURE</h1>
    <div class="subtitle">
        N° <strong th:text="${invoiceNumber}">FAC-2026-0001</strong>
        <span th:if="${issueDate}"> · Date: <span th:text="${issueDate}"></span></span>
        <span th:if="${dueDate}"> · Échéance: <span th:text="${dueDate}"></span></span>
    </div>
</div>

<!-- Client -->
<div class="info-box info-box-primary">
    <strong>Facturé à :</strong><br/>
    <span th:text="${clientName}">Nom Client</span><br/>
    <span th:if="${clientAddress}" th:text="${clientAddress}"></span>
    <span th:if="${clientNif}"><br/>NIF: <span th:text="${clientNif}"></span></span>
</div>

<!-- Lignes de facture -->
<table class="avoid-break">
    <thead>
        <tr>
            <th style="width:50%">Description</th>
            <th class="text-center">Qté</th>
            <th class="text-right">Prix unit. HT</th>
            <th class="text-right">Total HT</th>
        </tr>
    </thead>
    <tbody>
        <tr th:each="line : ${lines}">
            <td th:text="${line.description}">Description ligne</td>
            <td class="text-center" th:text="${line.quantity}">1</td>
            <td class="amount" th:text="${line.unitPrice}">0.00</td>
            <td class="amount" th:text="${line.amount}">0.00</td>
        </tr>
    </tbody>
    <tfoot>
        <tr>
            <td colspan="3" class="text-right">Sous-total HT</td>
            <td class="amount" th:text="${subtotal}">0.00</td>
        </tr>
        <tr th:if="${taxAmount}">
            <td colspan="3" class="text-right">TVA (10%)</td>
            <td class="amount" th:text="${taxAmount}">0.00</td>
        </tr>
        <tr class="totals-row">
            <td colspan="3" class="text-right">Total TTC</td>
            <td class="amount" th:text="${totalAmount}">0.00</td>
        </tr>
    </tfoot>
</table>

<!-- QR-code de paiement (si paymentUrl fourni) -->
<div th:if="${qrCodeBase64}" class="qr-section">
    <img class="qr-code" th:src="'data:image/png;base64,' + ${qrCodeBase64}" alt="QR paiement"/>
    <div class="qr-text">
        <strong>Scannez pour payer</strong><br/>
        Utilisez votre app bancaire ou mobile money<br/>
        <span th:if="${invoiceNumber}">Réf: <span th:text="${invoiceNumber}"></span></span>
    </div>
</div>

<!-- Mentions légales DGI art. 196 -->
<div class="mention-legal">
    <strong>Mentions légales (Code Fiscal art. 196) :</strong><br/>
    • NIF émetteur: <span th:text="${companyNif ?: 'Non communiqué'}"></span><br/>
    • Pénalités de retard: 1,5% par mois (art. 196 al. 3)<br/>
    • Indemnité de recouvrement: 5 000 HTG (art. 196 al. 4)<br/>
    • Escompte pour paiement anticipé: <span th:text="${escompte ?: 'Aucun'}"></span><br/>
    <span th:if="${isReverseCharge}">
        <strong>Autoliquidation :</strong> <span th:text="${reverseChargeMention}"></span>
    </span>
</div>

<div class="doc-footer">
    Document généré électroniquement — JOAccountant v9.4
</div>
$TPL$
WHERE document_type = 'INVOICE' AND country_code = 'HT' AND company_id IS NULL;

-- Si aucun template HT n'existait, on l'insère
INSERT INTO document_template (id, company_id, country_code, document_type, html_template, active, is_default, created_at, updated_at, version)
SELECT uuidv7(), NULL, 'HT', 'INVOICE',
    $TPL$<h1>FACTURE</h1><p>Template minimal (le template complet n'a pas pu être chargé)</p>$TPL$,
    TRUE, TRUE, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM document_template WHERE document_type = 'INVOICE' AND country_code = 'HT' AND company_id IS NULL);


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. PAYSLIP (Bulletin de paie) — country_code='HT', is_default=TRUE
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET
    is_default = TRUE,
    html_template = $TPL$<!-- Template PAYSLIP v9.4 — Corporate sobre -->
<div th:if="${watermarkText}" class="watermark" th:text="${watermarkText}"></div>

<!-- En-tête entreprise -->
<div class="doc-header">
    <div>
        <img th:if="${companyLogoBase64}" class="company-logo"
             th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/>
        <h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1>
    </div>
    <div class="company-info">
        <div class="name" th:text="${companyName}">Nom Entreprise</div>
        <div th:if="${companyAddress}" th:text="${companyAddress}"></div>
        <div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div>
    </div>
</div>

<!-- Titre bulletin -->
<div class="doc-title-block">
    <h1>BULLETIN DE PAIE</h1>
    <div class="subtitle">
        <span th:if="${period}">Période: <strong th:text="${period}"></strong></span>
        <span th:if="${payslipNumber}"> · N° <span th:text="${payslipNumber}"></span></span>
    </div>
</div>

<!-- Employé -->
<div class="info-box info-box-primary">
    <strong>Employé :</strong>
    <span th:text="${employeeName}">Nom Employé</span>
    <span th:if="${employeeNumber}"> · Matricule: <span th:text="${employeeNumber}"></span></span>
</div>

<!-- Rémunération brute -->
<table class="avoid-break">
    <thead>
        <tr><th colspan="2">Rémunération brute</th></tr>
    </thead>
    <tbody>
        <tr>
            <td>Salaire de base</td>
            <td class="amount" th:text="${grossSalary}">0.00</td>
        </tr>
    </tbody>
</table>

<!-- Retenues salariales -->
<table th:if="${deductions}" class="avoid-break">
    <thead>
        <tr>
            <th>Cotisations / Retenues salariales</th>
            <th class="text-right">Montant</th>
        </tr>
    </thead>
    <tbody>
        <tr th:each="ded : ${deductions}">
            <td th:text="${ded.code + ' — ' + (ded.label != null ? ded.label : '')}">Retenue</td>
            <td class="amount" th:text="${ded.amount}">0.00</td>
        </tr>
    </tbody>
    <tfoot>
        <tr>
            <td class="text-right">Total retenues</td>
            <td class="amount" th:text="${totalDeductions != null ? totalDeductions : #aggregates.sum(deductions.![amount])}">0.00</td>
        </tr>
    </tfoot>
</table>

<!-- Charges patronales -->
<table th:if="${employerContributions}" class="avoid-break">
    <thead>
        <tr>
            <th>Charges patronales</th>
            <th class="text-right">Montant</th>
        </tr>
    </thead>
    <tbody>
        <tr th:each="con : ${employerContributions}">
            <td th:text="${con.code + ' — ' + (con.label != null ? con.label : '')}">Charge</td>
            <td class="amount" th:text="${con.amount}">0.00</td>
        </tr>
    </tbody>
</table>

<!-- Net à payer -->
<table class="avoid-break">
    <tfoot>
        <tr class="totals-row">
            <td class="text-right">NET À PAYER</td>
            <td class="amount" th:text="${netPay}">0.00</td>
        </tr>
    </tfoot>
</table>

<!-- Mentions légales -->
<div class="mention-legal">
    <strong>Bulletin de paie — Code Travail Haïti art. 152-153 :</strong><br/>
    • CNSS: 6% salarial capé / OFATMA: 1% santé salarial / AST: barème progressif<br/>
    • ITS: barème progressif mensuel (Code Fiscal art. 156)<br/>
    • 13e mois: prorata mois travaillés (Code Travail art. 153)<br/>
    • Congés payés: 15 jours par an (Code Travail art. 156)
</div>

<div class="signature-block">
    <div class="sig">
        <div class="sig-line">Employé</div>
    </div>
    <div class="sig">
        <div class="sig-line">Employeur</div>
    </div>
</div>

<div class="doc-footer">
    Bulletin généré électroniquement — JOAccountant v9.4
</div>
$TPL$
WHERE document_type = 'PAYSLIP' AND country_code = 'HT' AND company_id IS NULL;

-- Si aucun template HT n'existait, on l'insère
INSERT INTO document_template (id, company_id, country_code, document_type, html_template, active, is_default, created_at, updated_at, version)
SELECT uuidv7(), NULL, 'HT', 'PAYSLIP',
    $TPL$<h1>BULLETIN DE PAIE</h1><p>Template minimal</p>$TPL$,
    TRUE, TRUE, now(), now(), 0
WHERE NOT EXISTS (SELECT 1 FROM document_template WHERE document_type = 'PAYSLIP' AND country_code = 'HT' AND company_id IS NULL);


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. BALANCE_SHEET_REPORT (Bilan) — Design "Corporate sobre" 2 colonnes
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET
    html_template = $TPL$<!-- Template BILAN v9.4 — Corporate sobre, 2 colonnes Actif/Passif -->
<div class="doc-header">
    <div>
        <img th:if="${companyLogoBase64}" class="company-logo"
             th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/>
        <h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1>
    </div>
    <div class="company-info">
        <div class="name" th:text="${companyName}">Nom Entreprise</div>
        <div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div>
    </div>
</div>

<div class="doc-title-block">
    <h1>BILAN</h1>
    <div class="subtitle">
        Au <strong th:text="${asOf}">31/12/2026</strong>
        <span th:if="${generationDate}"> · Généré le <span th:text="${generationDate}"></span></span>
    </div>
</div>

<div class="two-column">
    <!-- ACTIF -->
    <div class="col">
        <h2>ACTIF</h2>
        <table>
            <thead>
                <tr>
                    <th>Compte</th>
                    <th class="text-right">Montant</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="line : ${assets}">
                    <td th:text="${line.accountLabel}">Compte</td>
                    <td class="amount" th:text="${line.amount}">0.00</td>
                </tr>
            </tbody>
            <tfoot>
                <tr class="totals-row">
                    <td class="text-right">Total Actif</td>
                    <td class="amount" th:text="${totalAssets}">0.00</td>
                </tr>
            </tfoot>
        </table>
    </div>

    <!-- PASSIF + CAPITAUX PROPRES -->
    <div class="col">
        <h2>PASSIF &amp; CAPITAUX PROPRES</h2>
        <table>
            <thead>
                <tr>
                    <th>Compte</th>
                    <th class="text-right">Montant</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="line : ${liabilities}">
                    <td th:text="${line.accountLabel}">Compte</td>
                    <td class="amount" th:text="${line.amount}">0.00</td>
                </tr>
                <tr th:each="line : ${equity}">
                    <td th:text="${line.accountLabel}">Compte</td>
                    <td class="amount" th:text="${line.amount}">0.00</td>
                </tr>
            </tbody>
            <tfoot>
                <tr class="totals-row">
                    <td class="text-right">Total Passif + CP</td>
                    <td class="amount" th:text="${totalLiabilities + totalEquity}">0.00</td>
                </tr>
            </tfoot>
        </table>
    </div>
</div>

<!-- Équilibre -->
<div class="info-box" th:classappend="${balanced ? 'info-box-success' : 'info-box-warning'}">
    <strong th:text="${balanced ? '✓ Bilan équilibré' : '⚠ Bilan déséquilibré'}"></strong>
    <span th:if="${!balanced}"> — L'écart correspond au résultat de l'exercice</span>
</div>

<div class="doc-footer">
    Bilan généré électroniquement — JOAccountant v9.4 · Page générée automatiquement
</div>
$TPL$
WHERE document_type = 'BALANCE_SHEET_REPORT' AND company_id IS NULL AND country_code IS NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. DONATION_RECEIPT (Reçu de don ONG) — Design "Corporate sobre"
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET
    html_template = $TPL$<!-- Template DONATION_RECEIPT v9.4 — Corporate sobre ONG -->
<div th:if="${watermarkText}" class="watermark" th:text="${watermarkText}"></div>

<div class="doc-header">
    <div>
        <img th:if="${companyLogoBase64}" class="company-logo"
             th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/>
        <h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom ONG</h1>
    </div>
    <div class="company-info">
        <div class="name" th:text="${companyName}">Nom ONG</div>
        <div th:if="${companyAddress}" th:text="${companyAddress}"></div>
        <div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div>
    </div>
</div>

<div class="doc-title-block">
    <h1>REÇU DE DON</h1>
    <div class="subtitle">
        N° <strong th:text="${receiptNumber}">REC-2026-0001</strong>
        <span th:if="${receiptDate}"> · Date: <span th:text="${receiptDate}"></span></span>
    </div>
</div>

<div class="info-box info-box-success">
    <p><strong>Donateur :</strong> <span th:text="${donorName}">Nom Donateur</span></p>
    <p th:if="${donorAddress}"><strong>Adresse :</strong> <span th:text="${donorAddress}"></span></p>
    <p th:if="${grantCode}"><strong>Projet / Subvention :</strong> <span th:text="${grantCode}"></span></p>
</div>

<table class="avoid-break">
    <thead>
        <tr>
            <th>Description</th>
            <th class="text-right">Montant</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td>Don reçu en <span th:text="${currency ?: 'HTG'}">HTG</span></td>
            <td class="amount totals-row" th:text="${amount}">0.00</td>
        </tr>
    </tbody>
</table>

<!-- QR-code de vérification (si verificationUrl fourni) -->
<div th:if="${qrCodeBase64}" class="qr-section">
    <img class="qr-code" th:src="'data:image/png;base64,' + ${qrCodeBase64}" alt="QR vérification"/>
    <div class="qr-text">
        <strong>Vérifiez l'authenticité</strong><br/>
        Scannez ce QR-code pour vérifier ce reçu<br/>
        sur notre site officiel
    </div>
</div>

<div class="mention-legal">
    <strong>Mentions légales :</strong><br/>
    • Ce reçu est émis conformément au Code Fiscal Haïti art. 195 (exonération ONG)<br/>
    • L'organisation est agréée par la DGI comme ONG exonérée d'IS<br/>
    • Ce reçu doit être conservé pour justifier le don auprès des autorités fiscales
</div>

<div class="signature-block">
    <div class="sig">
        <div>Le Trésorier</div>
        <div class="sig-line">Signature &amp; cachet</div>
    </div>
    <div class="sig">
        <div>Le Directeur</div>
        <div class="sig-line">Signature &amp; cachet</div>
    </div>
</div>

<div class="doc-footer">
    Reçu généré électroniquement — JOAccountant v9.4
</div>
$TPL$
WHERE document_type = 'DONATION_RECEIPT' AND company_id IS NULL AND country_code IS NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. INCOME_STATEMENT_REPORT (Compte de résultat) — Design "Corporate sobre"
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE document_template SET
    html_template = $TPL$<!-- Template CR v9.4 — Corporate sobre -->
<div class="doc-header">
    <div>
        <img th:if="${companyLogoBase64}" class="company-logo"
             th:src="'data:image/png;base64,' + ${companyLogoBase64}" alt="Logo"/>
        <h1 th:if="${!companyLogoBase64}" th:text="${companyName}">Nom Entreprise</h1>
    </div>
    <div class="company-info">
        <div class="name" th:text="${companyName}">Nom Entreprise</div>
        <div th:if="${companyNif}">NIF: <strong th:text="${companyNif}"></strong></div>
    </div>
</div>

<div class="doc-title-block">
    <h1>COMPTE DE RÉSULTAT</h1>
    <div class="subtitle">
        Du <strong th:text="${from}"></strong> au <strong th:text="${to}"></strong>
        <span th:if="${generationDate}"> · Généré le <span th:text="${generationDate}"></span></span>
    </div>
</div>

<h2>PRODUITS</h2>
<table class="avoid-break">
    <thead>
        <tr>
            <th>Compte</th>
            <th class="text-right">Montant</th>
        </tr>
    </thead>
    <tbody>
        <tr th:each="line : ${productsLines}">
            <td th:text="${line.accountLabel}">Compte</td>
            <td class="amount" th:text="${line.amount}">0.00</td>
        </tr>
    </tbody>
    <tfoot>
        <tr class="totals-row">
            <td class="text-right">Total Produits</td>
            <td class="amount" th:text="${totalProducts}">0.00</td>
        </tr>
    </tfoot>
</table>

<h2>CHARGES</h2>
<table class="avoid-break">
    <thead>
        <tr>
            <th>Compte</th>
            <th class="text-right">Montant</th>
        </tr>
    </thead>
    <tbody>
        <tr th:each="line : ${chargesLines}">
            <td th:text="${line.accountLabel}">Compte</td>
            <td class="amount" th:text="${line.amount}">0.00</td>
        </tr>
    </tbody>
    <tfoot>
        <tr class="totals-row">
            <td class="text-right">Total Charges</td>
            <td class="amount" th:text="${totalCharges}">0.00</td>
        </tr>
    </tfoot>
</table>

<div class="info-box" th:classappend="${netSign >= 0 ? 'info-box-success' : 'info-box-warning'}">
    <strong>RÉSULTAT NET :</strong>
    <span class="amount" style="font-size: 14pt; font-weight: 700;" th:text="${netResult}">0.00</span>
    <span th:text="${netSign >= 0 ? ' (BÉNÉFICE)' : ' (PERTE)'}"></span>
</div>

<div class="doc-footer">
    Compte de résultat généré électroniquement — JOAccountant v9.4
</div>
$TPL$
WHERE document_type = 'INCOME_STATEMENT_REPORT' AND company_id IS NULL AND country_code IS NULL;
