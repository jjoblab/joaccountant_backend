// :purchase-orders — commandes fournisseur (Purchase Orders) + 3-way match (Finding #10).
//
// Module minimal de gestion des bons de commande fournisseurs. Les commandes ne génèrent PAS
// d'écriture comptable au MVP (l'écriture est générée à la réception de la facture fournisseur
// dans :invoicing, direction=PURCHASE). L'intérêt principal est le 3-way match
// (PurchaseOrder ↔ Invoice PURCHASE) implémenté dans ThreeWayMatchService.
//
// Dépendances :
//   - :core (TenantAwareEntity, exceptions)
//   - :company (ModuleAccessGuard optionnel — le module est toujours-actif au MVP)
//   - :invoicing (Invoice + InvoiceLine direction=PURCHASE, pour le 3-way match)
//   - :third-parties (ThirdParty SUPPLIER, pour la résolution du fournisseur)
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":company"))
    implementation(project(":invoicing"))
    implementation(project(":third-parties"))
}
