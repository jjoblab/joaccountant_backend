// :analytics — dimensions analytiques transverses (§5, §13 Phase 5).
// Mécanisme générique qui permet à Commerce/Service/ONG de partager le même moteur comptable
// sans branches de code spécifiques par secteur.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
}
