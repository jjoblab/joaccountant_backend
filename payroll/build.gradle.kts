// :payroll — paie consolidée, calcul brut→net via :tax (WithholdingRule), bulletin PDF.
// Restructuration 2026-07-24 — module bonus (toujours-actif, pas de ModuleAccessGuard requise).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":document-numbering"))
    implementation(project(":document-generation"))
    implementation(project(":approval-workflow"))
    implementation(project(":employees"))
    implementation(project(":tax"))
    implementation(project(":third-parties"))
    implementation(project(":company"))
}

// =============================================================================
// R-39 (lot-F2-tests-qa) — PIT mutation testing sur le module :payroll
// =============================================================================
// Le module :payroll (PayrollCalculator, PayrollService) calcule le brut→net selon
// les règles fiscales Haïti (CNSS, OFATMA, AST — R-20/R-25) et France. Une erreur
// de calcul = salaires nets erronés versés aux employés — impact légal direct.
//
// Voir :core/build.gradle.kts pour la documentation détaillée des paramètres PIT.
// Exécuter : ./gradlew :payroll:pitest
// Rapports  : payroll/build/reports/pitest/index.html
// =============================================================================
apply(plugin = "info.solidsoft.pitest")
configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    targetClasses.set(listOf("jo.accountant.payroll.*"))
    targetTests.set(listOf("jo.accountant.payroll.*Test"))
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    timeoutConstInMillis.set(5000)
    timeoutFactor.set(1.5.toBigDecimal())
    jvmArgs.set(listOf("-Xmx1024m"))
    mutationThreshold.set(50)
    coverageThreshold.set(70)
}
