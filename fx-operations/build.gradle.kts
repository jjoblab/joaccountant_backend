// :fx-operations — opérations en devises étrangères (achat/vente, réévaluation, gain/perte de change).
// Restructuration 2026-07-24 (suite 3) — module bonus (toujours-actif).
// Réutilise ExchangeRateService/ExchangeRate de :core (V20_001).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":company"))
}
