// :funds-grants — fonds, subventions, dons, fonds dédiés (secteur ONG, §13 Phase 14).
// Mécanisme des fonds dédiés via approval-workflow (§7).
// Dépend de :company pour ModuleAccessGuard (vérification d'activation du module FUNDS_GRANTS,
// restructuration 2026-07-24 §7.2).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":document-numbering"))
    implementation(project(":approval-workflow"))
    implementation(project(":analytics"))
    implementation(project(":third-parties"))
    implementation(project(":company"))
}
