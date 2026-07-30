// :demo-data — Module Démos V8.1
// 4 entreprises fictives haïtiennes (Boutik Lakay, Moïse & Associés, Espwa pou Ayiti, Caribbean Textiles)
// sur 2 exercices fiscaux (FY2024-2025 + FY2025-2026, exercice haïtien 01/10 → 30/09).
// Endpoints publics GET /api/v1/demos/** (lecture seule, sans auth) pour prospection commerciale.
dependencies {
    implementation(project(":core"))
    implementation(project(":company"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":financial-statements"))
    implementation(project(":third-parties"))
    implementation(project(":employees"))
    implementation(project(":invoicing"))
    implementation(project(":purchasing"))
    implementation(project(":inventory"))
    implementation(project(":payroll"))
    implementation(project(":tax"))
    implementation(project(":funds-grants"))
    implementation(project(":fx-operations"))
    implementation(project(":time-billing"))
    implementation(project(":expenses"))
    implementation(project(":reporting"))
    implementation(project(":audit-trail"))
    implementation(project(":auth"))
    implementation(project(":document-numbering"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
