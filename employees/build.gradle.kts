// :employees — employés (RH), rattachés à un ThirdParty de type EMPLOYEE.
// Restructuration 2026-07-24 — module bonus (toujours-actif, pas de ModuleAccessGuard requise).
// Ne génère AUCUNE écriture comptable (comme :third-parties).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":third-parties"))
    implementation(project(":company"))
}
