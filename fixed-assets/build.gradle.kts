// :fixed-assets — immobilisations, amortissements, cession (§13 Phase 8).
// Génère des écritures avec sourceModule=FIXED_ASSETS au moteur comptable.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":company"))
}
