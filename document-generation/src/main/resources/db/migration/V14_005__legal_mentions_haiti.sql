-- V14_005 — legal mentions haiti
-- V56 — Lot B — Mentions légales Haïti (Code Fiscal art. 196) + colonne country_code sur
-- document_template pour filtrer les templates par pays.
-- :
-- - Les templates INVOICE/CREDIT_NOTE/PAYSLIP (V35 + V39) étaient 100% français : CGI art. 289,
-- C. com. L441-10, "Pénalités 3× taux intérêt légal", "Indemnité 40 EUR".
-- - Une entreprise haïtienne qui générait une facture voyait donc des mentions légales
-- inapplicables (40 EUR au lieu de 5 000 HTG, 3× intérêt légal au lieu de 1.5%/mois, etc.)
-- et manquait les mentions OBLIGATOIRES en Haïti :
-- * NIF émetteur (Code Fiscal art. 196)
-- * Raison sociale, adresse, date, désignation biens/services
-- * Montant HT, taux TVA/TCA, montant taxes, montant TTC
-- * Pénalités de retard : 1.5% par mois (pratique haïtienne)
-- * Indemnité forfaitaire frais de recouvrement : 5 000 HTG (et non 40 EUR)
-- APPROCHE :
-- 1. Ajout d'une colonne `country_code VARCHAR(2)` NULLABLE sur document_template.
-- NULL = template international / France (comportement historique ).
-- 'HT' = template spécifique Haïti.
-- 'FR' = template spécifique France (utilisé si on veut explicitement filtrer).
-- Le service DocumentGenerationService peut utiliser cette colonne pour sélectionner le
-- template approprié en fonction du pays de la Company.
-- 2. Insertion de 3 nouveaux templates Haïti (INVOICE_HT, CREDIT_NOTE_HT, PAYSLIP_HT) avec
-- company_id IS NULL, country_code='HT', is_default=FALSE. Les templates FR (V39) restent
-- is_default=TRUE avec country_code=NULL pour préserver le comportement historique.
-- Note : pas de contrainte CHECK sur country_code (la liste des pays peut s'étendre — OHADA,
-- Canada, etc.). Pas d'index non plus (lookup par type + country, faible cardinalité).


ALTER TABLE document_template
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);

COMMENT ON COLUMN document_template.country_code IS
    'V56 — Lot B R-08 : code pays ISO 3166-1 alpha-2 pour filtrer les templates. NULL=international/FR (historique), HT=Haïti.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Template INVOICE Haïti — Code Fiscal art. 196
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO document_template (id, company_id, country_code, document_type, html_template,
                               active, is_default, created_at, updated_at, version)
VALUES (
    uuidv7(),
    NULL,
    'HT',
    'INVOICE',
    '<h1>Facture <span th:text="${invoiceNumber}">FAC-001</span></h1>

<div style="display: flex; justify-content: space-between;">
  <div>
    <strong><span th:text="${companyName}">Emetteur</span></strong><br/>
    <span th:text="${companyAddress} ?: ''">Adresse emetteur</span><br/>
    <span th:if="${companyNif}">NIF: <span th:text="${companyNif}">NIF emetteur</span><br/></span>
    <span th:if="${companySiret}">SIRET: <span th:text="${companySiret}">SIRET</span><br/></span>
    <span th:if="${companyVatNumber}">TVA intracomm.: <span th:text="${companyVatNumber}">FR00000000000</span><br/></span>
  </div>
  <div style="text-align: right;">
    <p>Date emission: <span th:text="${issueDate}">2024-01-01</span></p>
    <p>Date echeance: <span th:text="${dueDate}">2024-02-01</span></p>
  </div>
</div>

<hr/>

<h3>Client</h3>
<p><strong><span th:text="${clientName}">Client</span></strong></p>
<p><span th:text="${clientAddress} ?: ''">Adresse client</span></p>
<p th:if="${clientNif}">NIF: <span th:text="${clientNif}">NIF client</span></p>

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
  <td colspan="4" style="text-align: right;">Total TVA/TCA</td>
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
   <span th:text="${currency}">HTG</span>
   (echeance: <span th:text="${dueDate}">2024-02-01</span>)</p>

<hr/>

<p style="font-size: 0.8em; color: #666;">
  <strong>Mentions legales (Code Fiscal haïtien art. 196):</strong><br/>
  <span th:if="${companyNif}">NIF emetteur: <span th:text="${companyNif}">NIF</span><br/></span>
  Penalites de retard: 1.5% par mois de retard sur le montant exigible (pratique DGI Haïti).<br/>
  Indemnite forfaitaire pour frais de recouvrement: 5 000 HTG due en cas de retard de paiement.<br/>
  <span th:if="${companyVatNumber}">TVA intracommunautaire emetteur: <span th:text="${companyVatNumber}">FR00000000000</span><br/></span>
  <span th:if="${escompte}">Escompte pour paiement anticipe: <span th:text="${escompte}">0%</span><br/></span>
</p>

<p style="font-size: 0.8em; color: #999;">
  Facture conservee 10 ans (Code Fiscal art. 196). La facturation electronique est admise par
  la DGI Haïti sous reserve de respecter les mentions obligatoires ci-dessus.
</p>',
    TRUE,
    FALSE,
    now(),
    now(),
    0
) ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- Template CREDIT_NOTE Haïti
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO document_template (id, company_id, country_code, document_type, html_template,
                               active, is_default, created_at, updated_at, version)
VALUES (
    uuidv7(),
    NULL,
    'HT',
    'CREDIT_NOTE',
    '<h1>Avoir <span th:text="${creditNoteNumber}">AV-001</span></h1>

<div style="display: flex; justify-content: space-between;">
  <div>
    <strong><span th:text="${companyName}">Emetteur</span></strong><br/>
    <span th:text="${companyAddress} ?: ''">Adresse emetteur</span><br/>
    <span th:if="${companyNif}">NIF: <span th:text="${companyNif}">NIF emetteur</span><br/></span>
  </div>
  <div style="text-align: right;">
    <p>Date emission: <span th:text="${issueDate}">2024-01-01</span></p>
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
  <td colspan="4" style="text-align: right;">Total TVA/TCA</td>
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
   <span th:text="${currency}">HTG</span></p>

<hr/>

<p style="font-size: 0.8em; color: #666;">
  <strong>Mentions legales (Code Fiscal haïtien art. 196):</strong>
  Avoir relatif a la facture <span th:text="${originalInvoiceNumber}">FAC-001</span>.<br/>
  Penalites de retard: 1.5% par mois (pratique DGI Haïti).<br/>
  Indemnite forfaitaire pour frais de recouvrement: 5 000 HTG en cas de retard.<br/>
  <span th:if="${companyNif}">NIF emetteur: <span th:text="${companyNif}">NIF</span><br/></span>
</p>',
    TRUE,
    FALSE,
    now(),
    now(),
    0
) ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- Template PAYSLIP Haïti — bulletin de paie DGT Haïti
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO document_template (id, company_id, country_code, document_type, html_template,
                               active, is_default, created_at, updated_at, version)
VALUES (
    uuidv7(),
    NULL,
    'HT',
    'PAYSLIP',
    '<h1>Bulletin de paie <span th:text="${paySlipNumber}">BUL-001</span></h1>

<div style="display: flex; justify-content: space-between;">
  <div>
    <strong>Employeur</strong><br/>
    <span th:text="${companyName}">Employeur</span><br/>
    <span th:text="${companyAddress} ?: ''">Adresse</span><br/>
    <span th:if="${companyNif}">NIF: <span th:text="${companyNif}">NIF</span><br/></span>
    <span th:if="${companyUrssafNumber}">N. CNSS: <span th:text="${companyUrssafNumber}">CNSS</span><br/></span>
  </div>
  <div style="text-align: right;">
    <p><strong>Periode:</strong> <span th:text="${periodLabel}">2024-07</span></p>
    <p><strong>Date de paiement:</strong> <span th:text="${paymentDate} ?: ''">2024-07-31</span></p>
  </div>
</div>

<hr/>

<h3>Salarie</h3>
<table style="width: 100%;">
<tr><td>Nom:</td><td th:text="${employeeName}">Nom</td>
    <td>Emploi:</td><td th:text="${employeePosition} ?: ''">Poste</td></tr>
<tr><td>Matricule:</td><td th:text="${employeeNumber} ?: ''">MAT-001</td>
    <td>N. CNSS:</td><td th:text="${employeeSocialSecurityNumber} ?: ''">123456789</td></tr>
<tr><td>Date entree:</td><td th:text="${employeeHireDate} ?: ''">2020-01-01</td>
    <td>Contrat:</td><td th:text="${employeeContractType} ?: ''">CDI</td></tr>
</table>

<h3>Heures et salaire de base</h3>
<table border="1" cellpadding="4" cellspacing="0" style="width: 100%; border-collapse: collapse;">
<tr><th></th><th>Taux</th><th>Nb heures</th><th>Montant</th></tr>
<tr><td>Heures normales</td><td th:text="${hourlyRate} ?: ''">15.00</td>
    <td th:text="${standardHours} ?: ''">208.00</td>
    <td th:text="${baseSalary}">0.00</td></tr>
<tr th:if="${overtimeHours25}"><td>Heures supp. +25%</td>
    <td th:text="${overtimeRate25} ?: ''">18.75</td>
    <td th:text="${overtimeHours25}">0</td>
    <td th:text="${overtimeAmount25}">0.00</td></tr>
<tr th:if="${overtimeHours50}"><td>Heures supp. +50%</td>
    <td th:text="${overtimeRate50} ?: ''">22.50</td>
    <td th:text="${overtimeHours50}">0</td>
    <td th:text="${overtimeAmount50}">0.00</td></tr>
<tr th:if="${overtimeHours100}"><td>Heures supp. +100%</td>
    <td th:text="${overtimeRate100} ?: ''">30.00</td>
    <td th:text="${overtimeHours100}">0</td>
    <td th:text="${overtimeAmount100}">0.00</td></tr>
<tr style="font-weight: bold; background: #f0f0f0;"><td colspan="3">Salaire brut</td>
    <td th:text="${grossSalary}">0.00</td></tr>
</table>

<h3>Cotisations salariales (CNSS + OFATMA + AST)</h3>
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
<tr><td>- Impot sur le revenu (ITS)</td><td>-<span th:text="${incomeTaxWithheld} ?: ''">0.00</span></td></tr>
<tr style="font-weight: bold; font-size: 1.2em; background: #f8f8f8;">
  <td>NET A PAYER</td><td th:text="${netSalary}">0.00</td></tr>
</table>

<hr/>

<p style="font-size: 0.8em; color: #666;">
  <strong>Mentions legales (Code du travail haïtien + Code Fiscal):</strong><br/>
  Pour toute reclamation, contacter l''employeur dans un delai d''1 an a compter du versement.<br/>
  Conservez ce bulletin de paie sans limitation de duree.<br/>
  <span th:if="${companyNif}">NIF employeur: <span th:text="${companyNif}">NIF</span><br/></span>
  <span th:if="${companyUrssafNumber}">N. CNSS employeur: <span th:text="${companyUrssafNumber}">CNSS</span><br/></span>
</p>',
    TRUE,
    FALSE,
    now(),
    now(),
    0
) ON CONFLICT DO NOTHING;

COMMENT ON TABLE document_template IS
    'V56 — Lot B R-08 : ajout country_code + templates Haïti (INVOICE/CREDIT_NOTE/PAYSLIP) avec mentions Code Fiscal art. 196.';
