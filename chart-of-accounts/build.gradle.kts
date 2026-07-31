// :chart-of-accounts — plan comptable multi-référentiel (§4, §13 Phase 3).
// Ne dépend JAMAIS de :accounting-engine (principe 5 du prompt maître).
//
// V8.2 Phase 4 (audit Z.ai 2026-07-31) — ajout dépendance vers :company pour
// ChartOfAccountsAutoInitializer (@TransactionalEventListener sur CompanyWizardCompletedEvent).
// Cette dépendance est autorisée par ArchUnit Rule 8 (qui n'interdit que :accounting-engine,
// :financial-statements, :invoicing, :third-parties, etc. — pas :company).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":company"))
    // v2.5.0-task8 — CsvEndpointHelper (util dans :document-generation) pour l'export
    // CSV du plan comptable. Pas de cycle : Rule 24 ArchUnit n'interdit que la direction
    // inverse (:document-generation → business modules), pas :chart-of-accounts → :document-generation.
    // Rule 8 (chart-of-accounts must NOT depend on accounting-engine/financial-statements/etc.)
    // ne liste pas :document-generation.
    implementation(project(":document-generation"))
}
