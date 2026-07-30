import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    // Audit v4.7 §8.1 Finding HAUT — Outillage qualité
    // Spotless : formatage automatique du code (google-java-format) — évite les diffs noise
    // JaCoCo : couverture de code — cible 70% lignes / 60% branches
    alias(libs.plugins.spotless) apply false
    // Finding #19 (audit batch C) — SpotBugs : analyse statique (bugs patterns).
    // Finding #19 — PMD : analyse statique (best practices + errorprone).
    // Configurés plus bas dans le subprojects block (warnings only — ne cassent pas le build).
    alias(libs.plugins.spotbugs) apply false
    // PMD : plugin Gradle core (pas de version à déclarer — fourni nativement par Gradle).
    // On ne le déclare pas dans le plugins block (les plugins core ne supportent pas
    // `apply false` — ils sont déjà sur le classpath). Il sera activé via `apply(plugin = "pmd")`
    // dans le subprojects block ci-dessous.
    jacoco

    // R-39 (lot-F2-tests-qa) — PIT (mutation testing) sur modules critiques.
    // Plugin Gradle pour PIT (pitest.org). Appliqué uniquement sur les 4 modules critiques
    // (core, accounting-engine, payroll, tax) — pas sur tous les subprojects, car les modules
    // sans tests feraient échouer `./gradlew pitest` (mutationThreshold/coverageThreshold non
    // atteints faute de tests). Le plugin est déclaré `apply false` ici et activé dans chaque
    // build.gradle.kts critique via `apply(plugin = "info.solidsoft.pitest")`.
    id("info.solidsoft.pitest") version "1.15.0" apply false
}

allprojects {
    group = "jo.accountant"
    version = "2.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Finding #18 (audit batch C) — `libs` est auto-discovered sur le root project, mais n'est
    // PAS encore register sur les subprojects au moment où ce bloc s'exécute (les build.gradle.kts
    // des subprojects ne sont pas encore évalués). On capture donc la référence rootProject.libs
    // pour pouvoir utiliser les accesseurs type-safe dans ce bloc.
    val libs = rootProject.libs

    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")
    // Finding #19 (audit batch C) — application des plugins qualité sur tous les subprojects.
    // SpotBugs et PMD sont configurés en warnings-only (ne cassent pas le build) — voir plus bas.
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "pmd")

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
        implementation(platform(SpringBootPlugin.BOM_COORDINATES))

        // Multi-tenant convention imposed by §3.5: BigDecimal ONLY. Enforce compile-time guard via annotations.
        implementation(libs.spring.boot.starter.validation)
        implementation(libs.spring.boot.starter.data.jpa)
        implementation(libs.jakarta.validation.api)

        // Force alignement des versions JUnit Platform (sinon conflit quand plusieurs modules
        // apportent leur propre JUnit via spring-boot-starter-test).
        implementation(platform(libs.junit.bom))

        // Tests
        testImplementation(libs.spring.boot.starter.test)
        testImplementation(libs.spring.security.test)
        testImplementation(libs.zonky.embedded.postgres)
        testImplementation(libs.archunit.junit5)
    }

    // Audit v4.7 §8.1 — Spotless : formatage automatique avec google-java-format
    // Exécuter ./gradlew spotlessApply pour formater, ./gradlew spotlessCheck pour vérifier
    // Note : apply(plugin = "com.diffplug.spotless") doit être fait AVANT la configuration
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            // google-java-format : style standard Google (4 espaces, import order, etc.)
            googleJavaFormat("1.22.0")
            // Supprimer les imports inutilisés
            removeUnusedImports()
            // Formatage des imports (ordre alphabétique, java.* avant javax.*)
            formatAnnotations()
        }
    }

    // Audit v4.7 §8.1 — JaCoCo : couverture de code
    // Cible : 70% lignes / 60% branches (audit §8.2)
    // Exécuter ./gradlew test jacocoTestReport pour générer le rapport HTML dans build/reports/jacoco/
    // Exécuter ./gradlew jacocoTestCoverageVerification pour vérifier les seuils (fail build si < cible)
    jacoco {
        toolVersion = libs.versions.jacoco.get()
    }
    // (libs est capturé en haut du bloc subprojects — voir commentaire sur rootProject.libs)

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)   // Pour intégration CI (SonarQube, Codecov)
            html.required.set(true)  // Pour consultation locale
            csv.required.set(false)
        }
        // Cible de couverture — audit §8.2 : 70% lignes / 60% branches
        // Note : la vérification est dans jacocoTestCoverageVerification (séparée du report)
    }

    tasks.withType<JacocoCoverageVerification> {
        violationRules {
            // R-17 (lot-D-qualite-arch) — activation effective du failOnViolation (était commenté avant).
            // Le build casse désormais si la couverture descend sous les seuils ci-dessous.
            // Workaround Kotlin DSL : `failOnViolation = true` ne compile pas (le backing field
            // est private dans la superclasse). On appelle explicitement le setter public.
            setFailOnViolation(true)
            rule {
                limit {
                    counter = "LINE"
                    // R-17 — seuil abaissé temporairement de 0.70 à 0.30 le temps
                    // que les tests unitaires montent en couverture. La majorité des 27 modules
                    // métier étaient à 0% de tests unitaires avant le lot-D (R-16 a ajouté 7 tests
                    // unitaires purs sur tax/payroll/invoicing/accounting-engine/auth/core).
                    // TODO: remonter à 0.70 une fois la couverture unitaire suffisante
                    minimum = "0.30".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    // TODO: remonter à 0.60 une fois la couverture unitaire suffisante
                    minimum = "0.20".toBigDecimal()
                }
            }
        }
    }

    // =========================================================================
    // Finding #19 (audit batch C) — SpotBugs : analyse statique (bugs patterns)
    // =========================================================================
    // SpotBugs détecte les patterns de bugs courants (null déréférencé, ressources non fermées,
    // mauvaise gestion de BigDecimal.equals, etc.). Configuré en "medium" + effort "max" pour
    // maximiser la couverture des détecteurs sans trop de bruit.
    //
    // Warnings only pour l'instant — ne casse pas le build. Pour fail le build :
    //   tasks.withType<com.github.spotbugs.snom.SpotBugsTask> { ignoreFailures = false }
    // On gardera ignoreFailures = true jusqu'à atteindre 0 warning medium+ (TODO v4.8).
    configure<com.github.spotbugs.snom.SpotBugsExtension> {
        effort.set(com.github.spotbugs.snom.Effort.MAX)
        reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
        ignoreFailures.set(true)  // Warnings only — ne casse pas le build
    }
    tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
        // SpotBugs 6.x pré-enregistre 5 reports (html, xml, text, sarif, emacs). Par défaut,
        // seul HTML est activé (required=true). On garde les defaults — le rapport HTML
        // généré dans build/reports/spotbugs/ suffit pour la consultation locale et la CI.
        // Pour activer XML (intégration SonarQube / GitHub Annotations), décommenter :
        //   reports.register<com.github.spotbugs.snom.SpotBugsReport>("xml") { required.set(true) }
        ignoreFailures = true  // VerificationTask : boolean natif (pas un Property<Boolean>)
    }

    // =========================================================================
    // Finding #19 (audit batch C) — PMD : analyse statique (best practices + errorprone)
    // =========================================================================
    // PMD détecte les violations de best practices (code mort, equals/hashCode incohérents,
    // switch sans default, etc.) et les patterns errorprone (assert cassé, comparaison de
    // String avec ==, etc.). On active 2 ruleSets ciblés pour limiter le bruit (les 6 ruleSets
    // par défaut de PMD génèrent trop de warnings sur une codebase existante).
    //
    // Warnings only pour l'instant — ne casse pas le build. Pour fail le build :
    //   tasks.withType<Pmd> { ignoreFailures = false }
    configure<org.gradle.api.plugins.quality.PmdExtension> {
        // Workaround Kotlin DSL : `ignoreFailures = true` échoue car le champ backing est private
        // dans la superclasse CodeQualityExtension. On appelle explicitement le setter public.
        setIgnoreFailures(true)  // Warnings only — ne casse pas le build
        ruleSets = listOf(
            "category/java/bestpractices.xml",
            "category/java/errorprone.xml"
        )
    }
    tasks.withType<org.gradle.api.plugins.quality.Pmd> {
        // Génère un rapport HTML + XML pour intégration CI.
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        // VerificationTask#setIgnoreFailures (interface) — accessible directement.
        ignoreFailures = true  // Warnings only — ne casse pas le build
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
    }

    // Force une version unique pour JUnit Platform (sinon conflit quand plusieurs modules
    // apportent leur propre JUnit via spring-boot-starter-test). Aligné sur la version
    // fournie par Spring Boot 3.5.x.
    configurations.all {
        resolutionStrategy {
            force(
                "org.junit.platform:junit-platform-engine:${libs.versions.junitPlatform.get()}",
                "org.junit.platform:junit-platform-launcher:${libs.versions.junitPlatform.get()}",
                "org.junit.platform:junit-platform-commons:${libs.versions.junitPlatform.get()}",
                "org.junit.jupiter:junit-jupiter:${libs.versions.junitJupiter.get()}",
                "org.junit.jupiter:junit-jupiter-api:${libs.versions.junitJupiter.get()}",
                "org.junit.jupiter:junit-jupiter-engine:${libs.versions.junitJupiter.get()}"
            )
        }
    }
    // (libs est capturé en haut du bloc subprojects — voir commentaire sur rootProject.libs)

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("FAILED", "SKIPPED")
            showStandardStreams = false
        }
        // Audit v4.7 §8.1 — JaCoCo : collecter la couverture pendant les tests
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    // R-17 (lot-D-qualite-arch) — lie jacocoTestCoverageVerification à `check` pour que le
    // seuil de couverture soit effectivement enforced sur ./gradlew check et ./gradlew build.
    // Sans ce binding, la vérification ne s'exécute que si on l'invoque explicitement.
    tasks.named("check").configure {
        dependsOn("jacocoTestCoverageVerification")
    }
}
