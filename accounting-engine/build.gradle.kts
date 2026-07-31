// :accounting-engine — moteur comptable (écritures, journal, grand livre, balance, exercices/périodes).
// Cœur non négociable du projet. Premier consommateur réel de :document-numbering,
// :approval-workflow, :chart-of-accounts, et implémente AccountBalanceGuard (interface posée
// en Phase 3).
//
// V8.2 Phase 4 (audit Z.ai 2026-07-31) — ajout dépendance vers :company pour
// AccountingEngineAutoInitializer (@TransactionalEventListener sur CompanyWizardCompletedEvent).
// Cette dépendance est autorisée par ArchUnit Rule 13 (qui n'interdit que les modules en aval
// comme :invoicing, :tax, :purchasing, etc. — pas :company).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":document-numbering"))
    implementation(project(":approval-workflow"))
    implementation(project(":analytics"))
    implementation(project(":company"))
    // step2-backend — Reports Hub v2.4.0 : rendu PDF de la balance générale et du
    // grand livre via DocumentGenerationService. Pas de cycle (Rule 24 ArchUnit
    // n'interdit que la direction inverse : :document-generation ne peut pas
    // dépendre de :accounting-engine).
    implementation(project(":document-generation"))
}

// =============================================================================
// R-39 (lot-F2-tests-qa) — PIT mutation testing sur le module :accounting-engine
// =============================================================================
// Le module :accounting-engine (AccountingEngineService, JournalEntry, FiscalYear...)
// est le cœur métier de l'application : écritures comptables, journal, grand livre,
// balance, périodes fiscales. Une régression ici corrompt directement la compta —
// c'est le module le plus critique avec :tax.
//
// Voir :core/build.gradle.kts pour la documentation détaillée des paramètres PIT.
// Exécuter : ./gradlew :accounting-engine:pitest
// Rapports  : accounting-engine/build/reports/pitest/index.html
// =============================================================================
apply(plugin = "info.solidsoft.pitest")
configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    targetClasses.set(listOf("jo.accountant.accountingengine.*"))
    targetTests.set(listOf("jo.accountant.accountingengine.*Test"))
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    timeoutConstInMillis.set(5000)
    timeoutFactor.set(1.5.toBigDecimal())
    jvmArgs.set(listOf("-Xmx1024m"))
    mutationThreshold.set(50)
    coverageThreshold.set(70)
}
