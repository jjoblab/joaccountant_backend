// :third-parties — clients/fournisseurs/donateurs/salariés, lettrage, balance âgée (§13 Phase 7).
// Premier consommateur réel des comptes collectifs (isCollective=true) posés en Phase 3,
// et du champ thirdPartyId sur JournalLine posé en Phase 5.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    // step7-backend — Reports Hub v2.5.0 : needed for the LETTERING_REPORT PDF endpoint
    // (GET /api/v1/companies/{companyId}/third-parties/lettrage/pdf).
    // ArchUnit Rule 24 only forbids the reverse direction (:document-generation → business modules),
    // so this dependency is allowed.
    implementation(project(":document-generation"))
}
