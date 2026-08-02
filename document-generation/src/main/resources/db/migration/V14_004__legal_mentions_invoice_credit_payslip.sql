-- V14_004 — legal mentions invoice credit payslip
-- V39 — Audit v4.7 §4.2 Finding HAUT — Mentions légales factures conformes CGI art. 289 +
-- C. com. art. L441-10 + LPF art. L102B.
-- La v4.7 utilisait des templates INVOICE/CREDIT_NOTE/PAYSLIP très basiques (3-4 lignes par
-- template) qui omettaient les mentions légales obligatoires en France :
-- - SIRET de l'émetteur
-- - Numéro de TVA intracommunautaire (émetteur ET client si assujetti)
-- - Adresse de l'émetteur
-- - Date d'émission + date d'échéance
-- - Mention "Pénalités de retard: 3x taux d'intérêt légal" (C. com. L441-10)
-- - Indemnité forfaitaire pour frais de recouvrement: 40 EUR (C. com. L441-10)
-- - Mention "Dispensé d'immatriculation au RC/RM" si applicable (auto-entrepreneurs)
-- - Numéro de la facture + numéro de la facture d'origine (pour les avoirs)
-- - Désignation des biens/services + taux de TVA par ligne
-- - Total HT, total TVA, total TTC par taux
-- Sanctions : 15 EUR par mention manquante (LPF art. 1737 II) + amende fiscale pouvant
-- aller jusqu'à 50% du montant de la facture (CGI art. 1737). Pour un SaaS B2B destiné
-- aux clients français, c'est un redressement certain.
-- Pour le bulletin de paie (PAYSLIP), les mentions obligatoires sont C. trav. R3243-1 :
-- - Identification employeur (raison sociale, adresse, SIRET, code APE)
-- - Identification salarié (nom, emploi, classification, niveau/coefficient)
-- - Période + nombre d'heures (heures normales + HS à 25%/50%)
-- - Salaire brut, cotisations salariales (URSSAF, retraite, prévoyance, CSG/CRDS)
-- - Cotisations patronales (URSSAF, retraite, prévoyance, ASSEDIC)
-- - Net à payer avant impôt + PAS (Prélèvement à la Source)
-- - Net à payer après impôt
-- - Congés payés (cumul acquis, cumul pris, solde)
-- - Mention "Pour toute réclamation, contacter..." avec délai de prescription (1 an)
-- Approche : UPDATE les templates INVOICE, CREDIT_NOTE, PAYSLIP existants (V35) avec des
-- versions conformes. Les variables Thymeleaf sont résolues par DocumentGenerationService —
-- les nouvelles variables (companySiret, companyVatNumber, etc.) sont lues depuis
-- Company.extraAttributes ou depuis une source de données dédiée.
-- Pour ne pas casser les templates existants (qui peuvent avoir été customizés par les
-- utilisateurs), on UPDATE uniquement les templates par défaut (company_id IS NULL).
-- Les templates spécifiques à une company ne sont pas touchés.
-- Note : les caractères accentues sont evites pour compatibilite XML (voir NOTE 3 de V35).


UPDATE document_template
SET html_template =
'<h1>Facture <span th:text="${invoiceNumber}">FAC-001</span></h1>

<div style="display: flex; justify-content: space-between;">
  <div>
    <strong><span th:text="${companyName}">Emetteur</span></strong><br/>
    <span th:text="${companyAddress} ?: ''">Adresse emetteur</span><br/>
    <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
    <span th:if="${companyVatNumber}">TVA intracomm.: <span th:text="${companyVatNumber}">FR00000000000</span><br/></span>
    <span th:if="${companyRcs}">RCS: <span th:text="${companyRcs}">RCS</span><br/></span>
  </div>
  <div style="text-align: right;">
    <p>Date emission: <span th:text="${issueDate}">2026-01-01</span></p>
    <p>Date echeance: <span th:text="${dueDate}">2026-02-01</span></p>
  </div>
</div>

<hr/>

<h3>Client</h3>
<p><strong><span th:text="${clientName}">Client</span></strong></p>
<p><span th:text="${clientAddress} ?: ''">Adresse client</span></p>
<p th:if="${clientVatNumber}">TVA intracomm.: <span th:text="${clientVatNumber}">FR00000000000</span></p>

<table border="1" cellpadding="4" cellspacing="0" style="width: 100%; border-collapse: collapse;">
<thead style="background: #f0f0f0;">
<tr>
  <th>Description</th>
  <th>Qte</th>
  <th>P.U. HT</th>
  <th>TVA %</th>
  <th>Total HT</th>
  <th>Total TVA</th>
</tr>
</thead>
<tbody>
<tr th:each="line : ${lines}">
  <td th:text="${line.description}">Description</td>
  <td th:text="${line.quantity}">1</td>
  <td th:text="${line.unitPrice}">100.00</td>
  <td th:text="${line.taxRate}">0</td>
  <td th:text="${line.lineTotalHt}">100.00</td>
  <td th:text="${line.lineTotalTax}">0.00</td>
</tr>
</tbody>
<tfoot>
<tr style="font-weight: bold;">
  <td colspan="4" style="text-align: right;">Total HT</td>
  <td th:text="${subtotal}">0.00</td>
  <td></td>
</tr>
<tr style="font-weight: bold;">
  <td colspan="4" style="text-align: right;">Total TVA</td>
  <td></td>
  <td th:text="${taxAmount}">0.00</td>
</tr>
<tr style="font-weight: bold; background: #f8f8f8;">
  <td colspan="5" style="text-align: right;">Total TTC</td>
  <td th:text="${totalAmount}">0.00</td>
</tr>
</tfoot>
</table>

<p><strong>Montant a regler:</strong> <span th:text="${totalAmount}">0.00</span>
   <span th:text="${currency}">EUR</span>
   (echeance: <span th:text="${dueDate}">2026-02-01</span>)</p>

<hr/>

<p style="font-size: 0.8em; color: #666;">
  <strong>Mentions legales (CGI art. 289 + C. com. art. L441-10):</strong><br/>
  Penalites de retard: 3 fois le taux d''interet legal en vigueur, exigibles sans rappel des
  le lendemain de la date d''echeance (C. com. art. L441-10).<br/>
  Indemnite forfaitaire pour frais de recouvrement: 40 EUR due en cas de retard de paiement
  (C. com. art. L441-10).<br/>
  <span th:if="${companyVatNumber}">TVA intracommunautaire emetteur: <span th:text="${companyVatNumber}">FR00000000000</span><br/></span>
  <span th:if="${escompte}">Escompte pour paiement anticipe: <span th:text="${escompte}">0%</span><br/></span>
  <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
</p>

<p style="font-size: 0.8em; color: #999;">
  Facture conservee 10 ans (LPF art. L102B). Facturation electronique obligatoire en B2B France
  depuis le 1er septembre 2026 (Loi 2023-314).
</p>'
WHERE document_type = 'INVOICE' AND company_id IS NULL;

-- Mise à jour du template CREDIT_NOTE avec les memes mentions legales + reference facture origine
UPDATE document_template
SET html_template =
'<h1>Avoir <span th:text="${creditNoteNumber}">AV-001</span></h1>

<div style="display: flex; justify-content: space-between;">
  <div>
    <strong><span th:text="${companyName}">Emetteur</span></strong><br/>
    <span th:text="${companyAddress} ?: ''">Adresse emetteur</span><br/>
    <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
    <span th:if="${companyVatNumber}">TVA intracomm.: <span th:text="${companyVatNumber}">FR00000000000</span><br/></span>
  </div>
  <div style="text-align: right;">
    <p>Date emission: <span th:text="${issueDate}">2026-01-01</span></p>
    <p>Facture origine: <strong><span th:text="${originalInvoiceNumber}">FAC-001</span></strong></p>
  </div>
</div>

<hr/>

<h3>Client</h3>
<p><strong><span th:text="${clientName}">Client</span></strong></p>
<p><span th:text="${clientAddress} ?: ''">Adresse client</span></p>

<table border="1" cellpadding="4" cellspacing="0" style="width: 100%; border-collapse: collapse;">
<thead style="background: #f0f0f0;">
<tr>
  <th>Description</th>
  <th>Qte</th>
  <th>P.U. HT</th>
  <th>TVA %</th>
  <th>Total HT</th>
  <th>Total TVA</th>
</tr>
</thead>
<tbody>
<tr th:each="line : ${lines}">
  <td th:text="${line.description}">Description</td>
  <td th:text="${line.quantity}">1</td>
  <td th:text="${line.unitPrice}">100.00</td>
  <td th:text="${line.taxRate}">0</td>
  <td th:text="${line.lineTotalHt}">100.00</td>
  <td th:text="${line.lineTotalTax}">0.00</td>
</tr>
</tbody>
<tfoot>
<tr style="font-weight: bold;">
  <td colspan="4" style="text-align: right;">Total HT</td>
  <td th:text="${subtotal}">0.00</td>
  <td></td>
</tr>
<tr style="font-weight: bold;">
  <td colspan="4" style="text-align: right;">Total TVA</td>
  <td></td>
  <td th:text="${taxAmount}">0.00</td>
</tr>
<tr style="font-weight: bold; background: #f8f8f8;">
  <td colspan="5" style="text-align: right;">Total TTC</td>
  <td th:text="${totalAmount}">0.00</td>
</tr>
</tfoot>
</table>

<p><strong>Montant de l''avoir:</strong> <span th:text="${totalAmount}">0.00</span>
   <span th:text="${currency}">EUR</span></p>

<hr/>

<p style="font-size: 0.8em; color: #666;">
  <strong>Mentions legales:</strong> Avoir relatif a la facture <span th:text="${originalInvoiceNumber}">FAC-001</span>.<br/>
  Penalites de retard: 3 fois le taux d''interet legal en vigueur (C. com. art. L441-10).<br/>
  Indemnite forfaitaire pour frais de recouvrement: 40 EUR en cas de retard (C. com. art. L441-10).<br/>
  <span th:if="${companyVatNumber}">TVA intracommunautaire: <span th:text="${companyVatNumber}">FR00000000000</span><br/></span>
  <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
</p>'
WHERE document_type = 'CREDIT_NOTE' AND company_id IS NULL;

-- Mise à jour du template PAYSLIP avec mentions obligatoires C. trav. R3243-1
UPDATE document_template
SET html_template =
'<h1>Bulletin de paie <span th:text="${paySlipNumber}">BUL-001</span></h1>

<div style="display: flex; justify-content: space-between;">
  <div>
    <strong>Employeur</strong><br/>
    <span th:text="${companyName}">Employeur</span><br/>
    <span th:text="${companyAddress} ?: ''">Adresse</span><br/>
    <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
    <span th:if="${companyApeCode}">Code APE: <span th:text="${companyApeCode}">NAF</span><br/></span>
    <span th:if="${companySiret}">N. URSSAF: <span th:text="${companyUrssafNumber} ?: ''">URSSAF</span><br/></span>
  </div>
  <div style="text-align: right;">
    <p><strong>Periode:</strong> <span th:text="${periodLabel}">2026-07</span></p>
    <p><strong>Date de paiement:</strong> <span th:text="${paymentDate} ?: ''">2026-07-31</span></p>
  </div>
</div>

<hr/>

<h3>Salarie</h3>
<table style="width: 100%;">
<tr><td>Nom:</td><td th:text="${employeeName}">Nom</td>
    <td>Emploi:</td><td th:text="${employeePosition} ?: ''">Poste</td></tr>
<tr><td>Matricule:</td><td th:text="${employeeNumber} ?: ''">MAT-001</td>
    <td>N. Secu:</td><td th:text="${employeeSocialSecurityNumber} ?: ''">123456789</td></tr>
<tr><td>Classification:</td><td th:text="${employeeClassification} ?: ''">Niveau/coefficient</td>
    <td>Coefficient:</td><td th:text="${employeeCoefficient} ?: ''">100</td></tr>
<tr><td>Date entree:</td><td th:text="${employeeHireDate} ?: ''">2020-01-01</td>
    <td>Contrat:</td><td th:text="${employeeContractType} ?: ''">CDI</td></tr>
</table>

<h3>Heures et salaire de base</h3>
<table border="1" cellpadding="4" cellspacing="0" style="width: 100%; border-collapse: collapse;">
<tr><th></th><th>Taux</th><th>Nb heures</th><th>Montant</th></tr>
<tr><td>Heures normales</td><td th:text="${hourlyRate} ?: ''">15.00</td>
    <td th:text="${standardHours} ?: ''">151.67</td>
    <td th:text="${baseSalary}">0.00</td></tr>
<tr th:if="${overtimeHours25}"><td>Heures supp. +25%</td>
    <td th:text="${overtimeRate25} ?: ''">18.75</td>
    <td th:text="${overtimeHours25}">0</td>
    <td th:text="${overtimeAmount25}">0.00</td></tr>
<tr th:if="${overtimeHours50}"><td>Heures supp. +50%</td>
    <td th:text="${overtimeRate50} ?: ''">22.50</td>
    <td th:text="${overtimeHours50}">0</td>
    <td th:text="${overtimeAmount50}">0.00</td></tr>
<tr style="font-weight: bold; background: #f0f0f0;"><td colspan="3">Salaire brut</td>
    <td th:text="${grossSalary}">0.00</td></tr>
</table>

<h3>Cotisations salariales</h3>
<table border="1" cellpadding="4" cellspacing="0" style="width: 100%; border-collapse: collapse;">
<tr><th>Libelle</th><th>Base</th><th>Taux</th><th>Montant</th></tr>
<tr th:each="ded : ${employeeDeductions}">
  <td th:text="${ded.label}">Cotisation</td>
  <td th:text="${ded.base}">0.00</td>
  <td th:text="${ded.rate}">0</td>
  <td th:text="${ded.amount}">0.00</td>
</tr>
<tr style="font-weight: bold; background: #f0f0f0;">
  <td colspan="3">Total cotisations salariales</td>
  <td th:text="${totalDeductions}">0.00</td>
</tr>
</table>

<h3>Cotisations patronales</h3>
<table border="1" cellpadding="4" cellspacing="0" style="width: 100%; border-collapse: collapse;">
<tr th:each="con : ${employerContributions}">
  <td th:text="${con.label}">Cotisation patronale</td>
  <td th:text="${con.base}">0.00</td>
  <td th:text="${con.rate}">0</td>
  <td th:text="${con.amount}">0.00</td>
</tr>
<tr style="font-weight: bold; background: #f0f0f0;">
  <td colspan="3">Total cotisations patronales</td>
  <td th:text="${totalEmployerContributions}">0.00</td>
</tr>
</table>

<h3>Net a payer</h3>
<table style="width: 100%;">
<tr><td>Salaire brut</td><td th:text="${grossSalary}">0.00</td></tr>
<tr><td>- Cotisations salariales</td><td>-<span th:text="${totalDeductions}">0.00</span></td></tr>
<tr><td>= Net imposable</td><td th:text="${netTaxable}">0.00</td></tr>
<tr><td>- Prelevement a la source (PAS)</td><td>-<span th:text="${incomeTaxWithheld} ?: ''">0.00</span></td></tr>
<tr style="font-weight: bold; font-size: 1.2em; background: #f8f8f8;">
  <td>NET A PAYER</td><td th:text="${netSalary}">0.00</td></tr>
</table>

<h3>Conges payes</h3>
<p>Cumul acquis: <span th:text="${paidLeaveAccrued} ?: ''">0</span> jours |
   Cumul pris: <span th:text="${paidLeaveTaken} ?: ''">0</span> jours |
   Solde: <span th:text="${paidLeaveBalance} ?: ''">0</span> jours</p>

<hr/>

<p style="font-size: 0.8em; color: #666;">
  <strong>Mentions legales (C. trav. R3243-1):</strong><br/>
  Pour toute reclamation, contacter l''employeur dans un delai d''1 an a compter du versement
  (prescription C. trav. L3245-1).<br/>
  Conservez ce bulletin de paie sans limitation de duree.<br/>
  <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
  <span th:if="${companyUrssafNumber}">N. URSSAF: <span th:text="${companyUrssafNumber}">URSSAF</span><br/></span>
</p>'
WHERE document_type = 'PAYSLIP' AND company_id IS NULL;

COMMENT ON TABLE document_template IS
    'V39 — Templates INVOICE/CREDIT_NOTE/PAYSLIP mis a jour avec mentions legales obligatoires (CGI art. 289, C. com. L441-10, C. trav. R3243-1).';
