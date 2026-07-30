// :bank-reconciliation — import et lettrage bancaire (§13 Phase 13).
// Parseurs COMPLETS pour CSV et OFX. Fichier d'import brut conservé via FileStoragePort.
// Dépend de :company pour ModuleAccessGuard (vérification d'activation du module
// BANK_RECONCILIATION, restructuration 2026-07-24 §7.2).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":company"))
}
