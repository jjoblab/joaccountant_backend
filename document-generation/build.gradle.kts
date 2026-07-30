// :document-generation — rendu PDF partagé via Thymeleaf + openhtmltopdf (§8, §13 Phase 11).
// Infrastructure transverse — dépend uniquement de :core (pour FileStoragePort).
// Pas de dépendance vers :accounting-engine, :invoicing, etc. (principe 5).
plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":audit-trail"))
    // Thymeleaf pour le rendu de templates HTML
    api("org.springframework.boot:spring-boot-starter-thymeleaf")
    // OGNL — moteur d'évaluation d'expressions requis par Thymeleaf
    api(libs.ognl)
    // openhtmltopdf pour la conversion HTML → PDF (licence Apache 2.0, pas iText AGPL)
    api(libs.openhtmltopdf.core)
    api(libs.openhtmltopdf.pdfbox)
}
