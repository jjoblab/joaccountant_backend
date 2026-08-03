// :app - Spring Boot bootstrap, global security config, OpenAPI aggregation.
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":auth"))
    implementation(project(":company"))
    implementation(project(":document-numbering"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":approval-workflow"))
    implementation(project(":analytics"))
    implementation(project(":accounting-engine"))
    implementation(project(":financial-statements"))
    implementation(project(":third-parties"))
    implementation(project(":fixed-assets"))
    implementation(project(":inventory"))
    implementation(project(":time-billing"))
    implementation(project(":document-generation"))
    implementation(project(":invoicing"))
    implementation(project(":bank-reconciliation"))
    implementation(project(":funds-grants"))
    implementation(project(":notifications"))
    implementation(project(":tax"))
    implementation(project(":reporting"))
    implementation(project(":purchase-orders"))
    implementation(project(":expenses"))
    implementation(project(":employees"))
    implementation(project(":payroll"))
    implementation(project(":fx-operations"))
    // V8.1 — Module Démos (4 entreprises fictives, endpoints publics /api/v1/demos/**)
    implementation(project(":demo-data"))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.batch)
    implementation(libs.springdoc.openapi.webmvc.ui)

    // Audit v4.7 §9.3 Finding #3 — Observabilité : micrometer-registry-prometheus pour exposer
    // les métriques au format Prometheus sur /actuator/prometheus (scrapé par Prometheus/Grafana).
    // Inclut aussi Micrometer JVM + HikariCP bindings automatiques.
    implementation(libs.micrometer.registry.prometheus)

    // Audit v4.7 §9.3 Finding #3 — Logs JSON structurés en prod pour ingestion ELK/Loki/Datadog.
    // logstash-logback-encoder fournit LogstashEncoder (JSON) + LoggingEventCompositeJsonEncoder
    // (configurable). Utilisé dans logback-spring.xml via le profil 'prod'.
    implementation(libs.logstash.logback.encoder)

    // Audit v4.7 §9.3 Finding #5 — ShedLock pour ScheduledAlertsConfig : empêche l'exécution
    // multiple d'une tâche cron sur un déploiement multi-instances (3 replicas = 3× la même
    // tâche sans ShedLock → 3× les alertes envoyées). ShedLock utilise une table DB partagée
    // pour élire un leader par tâche. Backend JDBC (PostgreSQL) — voir ShedLockConfig.
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)

    // Audit v4.7 §9.3 Finding #3 (suite) — OpenTelemetry tracing distribué pour diagnostic
    // latence P99. Compatible avec Tempo/Jaeger/Zipkin/Datadog APM. Auto-instrumentation HTTP,
    // JPA, HikariCP via le starter. Export OTLP par défaut (configurable via
    // OTEL_EXPORTER_OTLP_ENDPOINT).
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)

    // Audit batch B Finding #3 — Bucket4j (in-memory) pour RateLimitFilter.
    // API plus propre que la ConcurrentHashMap manuelle (RateBucket + CAS loop), et
    // prépare la migration vers Redis : il suffira de remplacer Bucket4jRateLimitStore
    // par une implémentation basée sur bucket4j-redis + Lettuce (voir commentaire en bas
    // de RateLimitFilter). Version 8.10.1 = dernière 8.x stable (compatible Java 17).
    implementation(libs.bucket4j.core)

    // R-14 (lot-C-perf-devops) — Bucket4j Redis + Spring Data Redis pour rate-limiting distribué.
    // Sans Redis, 3 replicas = 3× la limite effective (chaque instance a son propre store in-memory).
    // Avec Redis, le bucket est partagé : la limite s'applique globalement sur les 3 instances.
    // Fallback in-memory conservé si app.rate-limit.redis.enabled=false (default).
    implementation(libs.bucket4j.redis)
    implementation(libs.spring.boot.starter.data.redis)

    // Embedded PostgreSQL (Zonky) for `dev` profile — exposed at runtime via DevLauncher.
    // Same binaries as tests, real PG behavior (pgcrypto, uuidv7(), planner).
    implementation(project(":test-support"))

    testImplementation(project(":test-support"))

    // =============================================================================
    // R-44 (lot-F2-tests-qa) — Spring Modulith : vérification runtime des boundaries
    // =============================================================================
    // Spring Modulith fournit ApplicationModules.of(...) qui inspecte l'ApplicationContext
    // au runtime et vérifie que :
    //   - chaque module Gradle (= package jo.accountant.<mod>) est un module Modulith valide,
    //   - les internals de package (sous-packages nommés "internal") ne sont pas accédés
    //     depuis l'extérieur du module,
    //   - les événements publiés via ApplicationEventPublisher sont bien déclarés comme
    //     externes (Spring Modulith infère la visibilité par convention).
    //
    // Complémente ArchUnit (R-44 côté compile-time) qui vérifie les dépendances statiques.
    // Voir ModulithVerificationTest pour les cas de vérification.
    //
    // Version : 1.4.0 alignée sur Spring Boot 3.5.x (le starter-test 1.2.1 demandé initialement
    // cible Spring Boot 3.3.x — à la compilation, il provoque des NoSuchMethodError sur les
    // classes Documenter/AggregatingDocument de Spring Modulith 1.2 car ces APIs ont évolué
    // entre 1.2 et 1.4). Spring Modulith 1.4.x est la version officiellement compatible avec
    // Spring Boot 3.5.x — voir https://spring.io/projects/spring-modulith#support.
    // =============================================================================
    testImplementation("org.springframework.modulith:spring-modulith-starter-test:1.4.0")
}

// Tâche `devRun` : démarre le backend avec PostgreSQL embarqué (Zonky) via DevLauncher.
// Usage : ./gradlew :app:devRun
tasks.register<JavaExec>("devRun") {
    group = "application"
    description = "Run JOAccountant with embedded PostgreSQL (dev mode, no install required)"
    mainClass.set("jo.accountant.app.dev.DevLauncher")
    classpath = sourceSets["main"].runtimeClasspath
    // Active le profil dev pour utiliser application-dev.yml
    args = listOf("--spring.profiles.active=dev")
    // Passe la JVM en UTF-8 + active les assertions
    jvmArgs = listOf("-Dfile.encoding=UTF-8", "-ea")
    standardOutput = System.out
    standardInput = System.`in`
}

// Tâche `printRuntimeClasspath` : écrit le classpath complet (pour lancer en `java -cp` direct).
// Usage : ./gradlew :app:printRuntimeClasspath -q > classpath.txt && java -cp @classpath.txt jo.accountant.app.dev.DevLauncher
tasks.register("printRuntimeClasspath") {
    group = "help"
    description = "Print the runtime classpath as a single line (for direct java -cp invocation)"
    doLast {
        println(sourceSets["main"].runtimeClasspath.asPath)
    }
}
