// :expenses — notes de frais, approval via JOURNAL_ENTRY_POST, écriture comptable.
// Restructuration 2026-07-24 — module bonus (toujours-actif, pas de ModuleAccessGuard requis).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":approval-workflow"))
    implementation(project(":third-parties"))
    implementation(project(":company"))
}
