// :time-billing — temps, projets, WIP (secteur Service, §13 Phase 10).
// Pas d'écriture comptable tant que non facturé (sauf option revenue recognition, désactivée par défaut).
// Dépend de :company pour ModuleAccessGuard (vérification d'activation du module TIME_BILLING,
// restructuration 2026-07-24 §7.2).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":company"))
}
