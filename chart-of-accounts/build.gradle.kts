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
}
