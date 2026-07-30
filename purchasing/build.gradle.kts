// :purchasing — factures fournisseur (achats), paiements fournisseurs, écriture comptable
// Débit Achats + TVA déductible / Crédit Fournisseur (restructuration 2026-07-24 — module bonus).
// Dépendances calquées sur :tax (pattern sectoriel avec ModuleAccessGuard).
//
// Audit v4.7 §4.1 Finding #2 — la retenue à la source fournisseur est appliquée via le port
// WithholdingRulePort (défini dans :core, implémenté par :tax). PAS de dépendance directe
// vers :tax — éviterait une dépendance circulaire Gradle (:tax dépend déjà de :purchasing
// pour PurchaseInvoiceRepository utilisé dans TaxService.getDeclaration).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":document-numbering"))
    implementation(project(":approval-workflow"))
    implementation(project(":third-parties"))
    implementation(project(":company"))
    // WithholdingRulePort est dans :core — pas de dépendance vers :tax (évite cycle)
}
