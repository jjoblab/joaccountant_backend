// :inventory — stock, valorisation FIFO/coût moyen pondéré, secteur Commerce (§13 Phase 9).
// Génère des écritures COGS avec sourceModule=INVENTORY à chaque sortie de stock.
// LIFO n'est PAS implémenté — IFRS l'interdit (pas de flag exposé nulle part).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":company"))
}
