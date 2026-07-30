// :document-numbering — génération atomique et sans trou de tout numéro de document métier (§6).
// Module consommé (jamais consommateur) — dépend uniquement de :core et :audit-trail (principe 5).
// Les tests d'intégration vivent dans :app (qui agrège tous les modules et porte la
// @SpringBootApplication — évite le scan de configuration inter-module).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
}
