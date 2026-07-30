// :test-support — helpers partagés entre les tests d'intégration des modules métier.
// Évite la dépendance circulaire :test -> :app (l'app est le consommateur final, pas un helper).
plugins {
    `java-library`
}

dependencies {
    api(libs.zonky.embedded.postgres)
    api("org.springframework.boot:spring-boot-starter-test")
}
