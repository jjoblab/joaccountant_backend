package jo.accountant.company.entity;

/** Code de module métier — utilisé par CompanyModule pour tracker l'activation (§11). */
public enum ModuleCode {
    // Always-on (socle commun)
    CHART_OF_ACCOUNTS,
    ACCOUNTING_ENGINE,
    THIRD_PARTIES,
    INVOICING,
    DOCUMENT_NUMBERING,
    APPROVAL_WORKFLOW,
    DOCUMENT_GENERATION,
    NOTIFICATIONS,
    AUDIT_TRAIL,

    // Modules spécifiques au secteur (auto-activés selon le mapping §11)
    INVENTORY,            // COMMERCE
    TIME_BILLING,         // SERVICE
    FUNDS_GRANTS,         // ONG

    // Modules sectoriels transverses
    FIXED_ASSETS,
    BANK_RECONCILIATION,
    TAX,

    // Modules sectoriels — pilotés par business_type_module (PURCHASING, FX_OPERATIONS) ou
    // always-on (EMPLOYEES, EXPENSES, PAYROLL — voir BusinessTypeModuleService.alwaysOnModules).
    // Restructuration 2026-07-24 (suite) — 4 nouveaux modules bonus + FX (stabilization 2026-07-25).
    PURCHASING,
    EMPLOYEES,
    EXPENSES,
    PAYROLL,
    FX_OPERATIONS,

    // Reporting (toujours construit, pas un module sectoriel)
    FINANCIAL_STATEMENTS,
    ANALYTICS,
    REPORTING
}
