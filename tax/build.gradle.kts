// :tax — règles fiscales locales, TVA, retenues à la source (§13 Phase 16).
// Dépend de :company pour ModuleAccessGuard (vérification d'activation du module TAX,
// restructuration 2026-07-24 §7.2).
// Audit v4.7 §4.1 Finding #1 — ajout dépendance vers :purchasing pour calculer la TVA déductible.
// Audit v4.7 §4.1 Finding #4 — ajout dépendance vers :financial-statements pour récupérer le
// résultat comptable (nécessaire pour la projection d'IS).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":financial-statements"))
    implementation(project(":invoicing"))
    implementation(project(":purchasing"))
    implementation(project(":company"))
}

// =============================================================================
// R-39 (lot-F2-tests-qa) — PIT mutation testing sur le module :tax
// =============================================================================
// Le module :tax (TaxService, VatCalculator, WithholdingRule) calcule la TVA, les
// retenues à la source et l'IS (France + Haïti — R-23/R-25). Une erreur ici
// génère des déclarations fiscales erronées transmises à la DGI/DGFIP — impact
// réglementaire et financier direct pour les clients.
//
// Voir :core/build.gradle.kts pour la documentation détaillée des paramètres PIT.
// Exécuter : ./gradlew :tax:pitest
// Rapports  : tax/build/reports/pitest/index.html
// =============================================================================
apply(plugin = "info.solidsoft.pitest")
configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    targetClasses.set(listOf("jo.accountant.tax.*"))
    targetTests.set(listOf("jo.accountant.tax.*Test"))
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    timeoutConstInMillis.set(5000)
    timeoutFactor.set(1.5.toBigDecimal())
    jvmArgs.set(listOf("-Xmx1024m"))
    mutationThreshold.set(50)
    coverageThreshold.set(70)
}
