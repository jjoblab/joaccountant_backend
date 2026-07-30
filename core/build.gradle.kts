// :core - shared infrastructure, NO business rules.
// Provides: TenantAwareEntity, TenantContext, @TenantId wiring, ports, audit-trail, ProblemDetail mapping.
plugins {
    `java-library`
}

dependencies {
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.security)
    api(libs.spring.security.oauth2.resource.server)
    api(libs.spring.security.oauth2.jose)
    api(libs.hibernate.core)
    api(libs.flyway.core)
    api(libs.flyway.database.postgresql)
    api(libs.postgresql)
    api(libs.spring.data.commons)
    // Argon2 for password hashing (§3.4)
    api(libs.bouncy.castle)
    // OpenAPI annotations — shared across every module that documents endpoints (§3.8)
    api(libs.swagger.annotations)
    // Audit v4.7 §9 — S3 / MinIO storage for horizontal scaling (optional, activated via app.storage.backend=s3)
    implementation(libs.aws.s3)
    implementation(libs.aws.auth)
    implementation(libs.aws.regions)

    // Audit v4.7 §7.2 Finding #3 — Cache applicatif pour données référentielles (Account, Journal,
    // TaxRule, Currency, etc.). Sans cache, 1000 factures/jour × 7-10 SELECT sur des données qui
    // ne changent jamais = 7000-10000 SELECT inutiles. Caffeine est un cache local in-process
    // (high-performance, JVM-level). Pour un déploiement multi-instances, ajouter Redis comme
    // L2 cache distribué (TODO v4.8).
    api(libs.spring.boot.starter.cache)
    api(libs.caffeine)
}

// =============================================================================
// R-39 (lot-F2-tests-qa) — PIT mutation testing sur le module :core
// =============================================================================
// Le module :core (PiiMasker, CurrencyRoundingService, TenantContext, framework...)
// contient des utilitaires transverses partagés par tous les modules métier. Une
// régression ici impacte l'ensemble de l'application — c'est pourquoi il fait partie
// des 4 modules critiques couverts par PIT.
//
// Configuration :
//   - targetClasses / targetTests : limités au package `jo.accountant.core` pour ne
//     pas remonter (PIT mute les classes de ses dépendances transitives sinon).
//   - mutators = STRONGER : active les mutateurs les plus agressifs (negate, void,
//     return, math, conditional, increments, invert, etc.) — détection maximale des
//     tests qui ne testent rien.
//   - mutationThreshold = 50 : le build casse si < 50% des mutants sont tués (cible
//     pragmatique pour démarrer — à remonter à 70% une fois la dette résorbée).
//   - coverageThreshold = 70 : le build casse si < 70% de couverture de ligne.
//
// Exécuter : ./gradlew :core:pitest
// Rapports  : core/build/reports/pitest/index.html (HTML) + mutations.xml (XML)
// =============================================================================
apply(plugin = "info.solidsoft.pitest")
configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    targetClasses.set(listOf("jo.accountant.core.*"))
    targetTests.set(listOf("jo.accountant.core.*Test"))
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    timeoutConstInMillis.set(5000)
    timeoutFactor.set(1.5.toBigDecimal())
    jvmArgs.set(listOf("-Xmx1024m"))
    mutationThreshold.set(50)
    coverageThreshold.set(70)
}
