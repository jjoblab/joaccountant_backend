// :reporting — orchestration des exports PDF/Excel, tableaux de bord (§13 Phase 17).
// Réutilise :document-generation (Phase 11) pour le rendu PDF — pas de logique de rendu dupliquée.
//
// Part C/D/E (nouveau) — :reporting devient le point d'agrégation de tous les exports CSV
// sectoriels (tax_declaration, purchase_register, expense_register, payroll_summary,
// inventory_valuation, stock_movement_register, time_billing_utilization,
// fixed_assets_register, fx_operations_register). Pour cela, il dépend des modules
// sectoriels correspondants (uniquement de leurs repositories — pas de logique métier).
//
// Pas de cycle : aucun des modules ci-dessous ne dépend de :reporting en retour
// (garanti par les règles ArchUnit Rule 22, 29, 33, 35, 37, 39, 41, etc.).
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    implementation(project(":chart-of-accounts"))
    implementation(project(":accounting-engine"))
    implementation(project(":approval-workflow"))
    implementation(project(":financial-statements"))
    implementation(project(":document-generation"))
    implementation(project(":third-parties"))
    implementation(project(":funds-grants"))
    implementation(project(":invoicing"))

    // Part C1 — ModuleAccessGuard (gating des exports par module activé, §7.2).
    implementation(project(":company"))

    // Part D/E — repositories pour les exports CSV sectoriels.
    implementation(project(":tax"))              // TaxService.getDeclaration (D2)
    implementation(project(":purchasing"))       // PurchaseInvoiceRepository (D3, D1)
    implementation(project(":expenses"))         // ExpenseReportRepository (D4)
    implementation(project(":payroll"))          // PayrollRunRepository + PayslipRepository (D5)
    implementation(project(":inventory"))        // ItemRepository, StockMoveRepository, StockValuationLayerRepository (E1, E2, E4)
    implementation(project(":time-billing"))     // TimesheetEntryRepository, ProjectRepository (E3, E4)
    implementation(project(":fixed-assets"))     // AssetRepository (E4)
    implementation(project(":fx-operations"))    // FxOperationRepository (E4)
}
