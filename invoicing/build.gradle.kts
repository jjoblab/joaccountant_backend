// :invoicing — facturation, avoirs (§13 Phase 12).
// Premier consommateur réel de :document-generation, :document-numbering, :third-parties,
// :accounting-engine, :inventory (COGS si itemId renseigné).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":document-numbering"))
    implementation(project(":approval-workflow"))
    implementation(project(":third-parties"))
    implementation(project(":inventory"))
    implementation(project(":document-generation"))
    implementation(project(":company"))  // Audit v4.7 §4.2 — CompanyRepository pour SIRET/VAT/TVA intracomm. (Factur-X)
    // V8-4 — Dépendance vers :time-billing pour marquer les TimesheetEntry comme invoiced
    // à l'émission d'une facture qui consomme du WIP (InvoiceLine.timesheetEntryId non-null).
    // Pas de dépendance circulaire : :time-billing ne dépend PAS de :invoicing.
    implementation(project(":time-billing"))

    // v8-2 — openpdf (LGPL, fork LibreOffice/iText 2.x) pour l'embarquement du XML Factur-X
    // comme /EmbeddedFile dans le PDF généré (cf. FacturXExporter.embedFacturXInPdf).
    // openpdf ne fournit pas de PDF/A-3 strict (iText 7 + add-on PDF/A nécessaire pour cela),
    // mais permet l'ajout d'une pièce jointe + métadonnées catalogue /AF + /AFRelationship=/Data
    // qui couvrent le contrat Factur-X : un PDF unique contenant le XML CII D16B embarqué.
    implementation("com.github.librepdf:openpdf:1.4.2")
}
