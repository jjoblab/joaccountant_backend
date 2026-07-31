// :financial-statements — bilan, compte de résultat, snapshots figés à la clôture (§13 Phase 6).
// Premier consommateur des écritures POSTED pour générer les états financiers.
// Utilise UNIQUEMENT reportingClass/reportingSubcategory des comptes (§4) — jamais de
// logique par référentiel (SYSCOHADA vs IFRS).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    // Task v6-4-presentation-currency — conversion HTG/USD pour DCR DGI Haïti
    implementation(project(":company"))
    implementation(project(":fx-operations"))
    // step2-backend — Reports Hub v2.4.0 : rendu PDF des états financiers
    // (bilan, compte de résultat, flux de trésorerie, variation des capitaux propres)
    // via DocumentGenerationService. Pas de cycle : :document-generation ne dépend
    // d'aucun module métier (Rule 24 ArchUnit).
    implementation(project(":document-generation"))
}
