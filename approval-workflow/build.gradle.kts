// :approval-workflow — seuils d'approbation transverses, mécanisme "quatre yeux" (§7, §13 Phase 4).
// Module consommé (jamais consommateur) — dépend uniquement de :core et :audit-trail (principe 5).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    // Référence :auth uniquement au niveau test (pour récupérer les UserCompanyRole
    // afin d'envoyer des notifications aux approbateurs éligibles).
    // En production :auth sera aussi présent à runtime via :app, donc on peut l'implémenter
    // ici sans dépendance compile-time — on passe par le repository de :auth indirectement.
}
