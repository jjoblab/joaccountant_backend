// :chart-of-accounts — plan comptable multi-référentiel (§4, §13 Phase 3).
// Ne dépend JAMAIS de :accounting-engine (principe 5 du prompt maître).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
}
