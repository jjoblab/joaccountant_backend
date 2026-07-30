// :third-parties — clients/fournisseurs/donateurs/salariés, lettrage, balance âgée (§13 Phase 7).
// Premier consommateur réel des comptes collectifs (isCollective=true) posés en Phase 3,
// et du champ thirdPartyId sur JournalLine posé en Phase 5.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
}
