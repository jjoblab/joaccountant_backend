// :company - company identity, wizard, sector module activation, max-companies guard.
//
// ⚠️ V8.2 (audit Z.ai 2026-07-31) — Refonte wizard 4 étapes avec activation atomique.
// Pour éviter les dépendances circulaires (de nombreux modules dépendent déjà de :company),
// AccountingProvisioningService est défini dans :app (le module final qui peut tout voir).
// :company expose des interfaces (CompanyWizardContext, WizardStep3Data) que :app implémente
// via le service d'orchestration. :company ne dépend que de :core, :audit-trail, :auth.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":auth"))
}
