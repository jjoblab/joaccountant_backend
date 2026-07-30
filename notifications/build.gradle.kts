// :notifications — centre de notifications in-app + e-mail, règles d'alerte (§9, §13 Phase 15).
// Devient l'implémentation de référence de NotificationChannelPort (posé en Phase 1).
// S'abonne aux événements de domaine via ApplicationEventPublisher — même pattern que :audit-trail.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    // Finding #1 (audit batch 1) — listeners forensiques pour les 4 événements de domaine les
    // plus importants (InvoiceIssuedEvent, JournalEntryPostedEvent, BankStatementImportedEvent,
    // AssetDisposedEvent). Ces dépendances compile-time sont nécessaires pour que la classe
    // ForensicEventListener puisse référencer les types concrets des événements écoutés.
    // Pas de cycle : :invoicing/:accounting-engine/:bank-reconciliation/:fixed-assets ne dépendent
    // pas de :notifications (principe 5 — :notifications est un module consommateur pur).
    implementation(project(":invoicing"))
    implementation(project(":accounting-engine"))
    implementation(project(":bank-reconciliation"))
    implementation(project(":fixed-assets"))
    // Finding #16 (audit batch C) — listeners forensiques additionnels pour 5 events de plus.
    // Le module :notifications doit référencer les types concrets des events écoutés
    // (AssetCreatedEvent, DepreciationPostedEvent — déjà couverts par :fixed-assets ;
    //  ChartOfAccountsInitializedEvent, AccountUpdatedEvent — :chart-of-accounts nouveau).
    // Pas de cycle : :chart-of-accounts ne dépend pas de :notifications (consumer pur).
    implementation(project(":chart-of-accounts"))
}
