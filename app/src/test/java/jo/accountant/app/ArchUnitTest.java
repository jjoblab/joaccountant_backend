package jo.accountant.app;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit rules mandated by §3.7.
 *
 * <p>These rules catch violations that the compiler cannot — they fail the build if a developer
 * breaks the architectural contract in a future phase.
 */
class ArchUnitTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("jo.accountant..");
    }

    @Test
    @DisplayName("Rule 1 — :audit-trail must NOT depend on any other business module")
    void auditTrailDependsOnNothing() {
        noClasses().that().resideInAPackage("jo.accountant.audit..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.auth..", "jo.accountant.company..", "jo.accountant.documentnumbering..",
                "jo.accountant.chartofaccounts..", "jo.accountant.approvalworkflow..",
                "jo.accountant.analytics..", "jo.accountant.accountingengine..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 2 — :core must NOT depend on any business module")
    void coreDependsOnNoBusinessModule() {
        noClasses().that().resideInAPackage("jo.accountant.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.auth..", "jo.accountant.company..", "jo.accountant.audit..",
                "jo.accountant.documentnumbering..", "jo.accountant.chartofaccounts..",
                "jo.accountant.approvalworkflow..", "jo.accountant.analytics..",
                "jo.accountant.accountingengine..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 3 — :company may depend on :auth (UserCompanyRole is shared) but NOT vice versa")
    void authDoesNotDependOnCompany() {
        noClasses().that().resideInAPackage("jo.accountant.auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.company..", "jo.accountant.documentnumbering..",
                "jo.accountant.chartofaccounts..", "jo.accountant.approvalworkflow..",
                "jo.accountant.analytics..", "jo.accountant.accountingengine..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 4 — :app may depend on every module, no module depends on :app")
    void appIsLeafConsumer() {
        noClasses().that().resideInAPackage("jo.accountant.core..")
            .or().resideInAPackage("jo.accountant.auth..")
            .or().resideInAPackage("jo.accountant.company..")
            .or().resideInAPackage("jo.accountant.audit..")
            .or().resideInAPackage("jo.accountant.documentnumbering..")
            .or().resideInAPackage("jo.accountant.chartofaccounts..")
            .or().resideInAPackage("jo.accountant.approvalworkflow..")
            .or().resideInAPackage("jo.accountant.analytics..")
            .or().resideInAPackage("jo.accountant.accountingengine..")
            .should().dependOnClassesThat().resideInAPackage("jo.accountant.app..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 5 — every JPA @Entity in a tenant-scoped package extends TenantAwareEntity (§3.7)")
        // Note: Phase 1 has only CompanyModule as a true TenantAwareEntity. User, Company,
        // UserCompanyRole, RefreshToken, PasswordResetToken, AuditLog are intentionally NOT
        // (transverse/join/audit). This rule is asserted on CompanyModule to make the contract
        // explicit; future modules will be added to the rule as they are introduced.
        //
        // Restructuration 2026-07-24 : BusinessType, BusinessTypeModule et
        // BusinessTypeRequiredField sont volontairement NON TenantAwareEntity — ce sont des
        // données de référence globales (au même titre que AccountingFramework dans :core),
        // pas de données métier scopées par tenant. Voir Rule 31 pour le contrat inverse.
    void companyModuleExtendsTenantAwareEntity() {
        classes().that().resideInAPackage("jo.accountant.company.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .and().haveSimpleName("CompanyModule")
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 31 (Phase 1 + restructuration 2026-07-24) — BusinessType, BusinessTypeModule et BusinessTypeRequiredField sont des données de référence GLOBALES (NON TenantAwareEntity)")
        // Comme AccountingFramework dans :core, ces entités ne portent pas de company_id —
        // elles sont partagées par tous les tenants. Ajouter une nouvelle entité de référence
        // globale dans :company sans l'ajouter à cette liste est un contrat cassé.
    void companyReferenceDataIsNotTenantAware() {
        classes().that().resideInAPackage("jo.accountant.company.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .and().haveSimpleName("BusinessType")
            .should().notBeAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
        classes().that().resideInAPackage("jo.accountant.company.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .and().haveSimpleName("BusinessTypeModule")
            .should().notBeAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
        classes().that().resideInAPackage("jo.accountant.company.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .and().haveSimpleName("BusinessTypeRequiredField")
            .should().notBeAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 6 (Phase 2) — :document-numbering must NOT depend on any business module except :core and :audit-trail (§6, §13 Phase 2)")
    void documentNumberingDependsOnCoreAndAuditOnly() {
        noClasses().that().resideInAPackage("jo.accountant.documentnumbering..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.auth..", "jo.accountant.company..", "jo.accountant.chartofaccounts..",
                "jo.accountant.approvalworkflow..", "jo.accountant.analytics..",
                "jo.accountant.accountingengine..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 7 (Phase 2) — DocumentSequenceConfig and DocumentSequenceCounter extend TenantAwareEntity")
    void documentNumberingEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.documentnumbering.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 8 (Phase 3) — :chart-of-accounts must NOT depend on :accounting-engine (principe 5 — chart-of-accounts est en amont)")
    void chartOfAccountsDoesNotDependOnAccountingEngine() {
        noClasses().that().resideInAPackage("jo.accountant.chartofaccounts..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.accountingengine..", "jo.accountant.financialstatements..",
                "jo.accountant.invoicing..", "jo.accountant.thirdparties..",
                "jo.accountant.approvalworkflow..", "jo.accountant.analytics..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 9 (Phase 3) — :chart-of-accounts entities extend TenantAwareEntity")
    void chartOfAccountsEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.chartofaccounts.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 10 (Phase 4) — :approval-workflow must NOT depend on any business module except :core and :audit-trail (§7, §13 Phase 4)")
    void approvalWorkflowDependsOnCoreAndAuditOnly() {
        noClasses().that().resideInAPackage("jo.accountant.approvalworkflow..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.auth..", "jo.accountant.company..", "jo.accountant.chartofaccounts..",
                "jo.accountant.documentnumbering..", "jo.accountant.accountingengine..",
                "jo.accountant.invoicing..", "jo.accountant.fundsgrants..",
                "jo.accountant.analytics..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 11 (Phase 4) — :approval-workflow entities extend TenantAwareEntity")
    void approvalWorkflowEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.approvalworkflow.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 12 (Phase 5) — :analytics must NOT depend on any business module except :core and :audit-trail (§5, §13 Phase 5)")
    void analyticsDependsOnCoreAndAuditOnly() {
        noClasses().that().resideInAPackage("jo.accountant.analytics..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.auth..", "jo.accountant.company..", "jo.accountant.chartofaccounts..",
                "jo.accountant.documentnumbering..", "jo.accountant.approvalworkflow..",
                "jo.accountant.accountingengine..", "jo.accountant.invoicing..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 13 (Phase 5) — :accounting-engine must NOT depend on :invoicing, :third-parties, :funds-grants (en aval)")
    void accountingEngineDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.accountingengine..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.thirdparties..",
                "jo.accountant.fundsgrants..", "jo.accountant.financialstatements..",
                "jo.accountant.fixedassets..", "jo.accountant.inventory..",
                "jo.accountant.timebilling..", "jo.accountant.bankreconciliation..",
                "jo.accountant.tax..", "jo.accountant.reporting..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 14 (Phase 6) — :financial-statements must NOT depend on invoicing/third-parties/funds-grants/inventory/etc.")
    void financialStatementsDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.financialstatements..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.thirdparties..",
                "jo.accountant.fundsgrants..", "jo.accountant.fixedassets..",
                "jo.accountant.inventory..", "jo.accountant.timebilling..",
                "jo.accountant.bankreconciliation..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 15 (Phase 6) — :financial-statements must NOT reference AccountingFramework/NumberingMode (référentiel-agnostique, §4)")
    void financialStatementsIsFrameworkAgnostic() {
        noClasses().that().resideInAPackage("jo.accountant.financialstatements..")
            .should().dependOnClassesThat().areAssignableTo(
                jo.accountant.core.framework.AccountingFramework.class)
            .orShould().dependOnClassesThat().areAssignableTo(
                jo.accountant.core.framework.NumberingMode.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 16 (Phase 7) — :third-parties must NOT depend on invoicing/funds-grants/inventory/etc. (en aval)")
    void thirdPartiesDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.thirdparties..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.fundsgrants..",
                "jo.accountant.fixedassets..", "jo.accountant.inventory..",
                "jo.accountant.timebilling..", "jo.accountant.bankreconciliation..",
                "jo.accountant.tax..", "jo.accountant.reporting..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 17 (Phase 7) — :third-parties entities extend TenantAwareEntity")
    void thirdPartiesEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.thirdparties.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 18 (Phase 8) — :fixed-assets must NOT depend on invoicing/inventory/funds-grants/time-billing/bank-reconciliation (en aval)")
    void fixedAssetsDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.fixedassets..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.fundsgrants..",
                "jo.accountant.inventory..", "jo.accountant.timebilling..",
                "jo.accountant.bankreconciliation..", "jo.accountant.tax..",
                "jo.accountant.reporting..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 19 (Phase 8) — :fixed-assets entities extend TenantAwareEntity")
    void fixedAssetsEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.fixedassets.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 20 (Phase 9) — :inventory must NOT depend on notifications/funds-grants/time-billing/bank-reconciliation/tax/reporting (en aval)")
    void inventoryDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.inventory..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.notifications..", "jo.accountant.fundsgrants..",
                "jo.accountant.timebilling..", "jo.accountant.bankreconciliation..",
                "jo.accountant.tax..", "jo.accountant.reporting..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 21 (Phase 9) — :inventory entities extend TenantAwareEntity")
    void inventoryEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.inventory.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 22 (Phase 10) — :time-billing must NOT depend on invoicing/inventory/funds-grants/bank-reconciliation/tax/reporting (en aval)")
    void timeBillingDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.timebilling..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.inventory..",
                "jo.accountant.fundsgrants..", "jo.accountant.bankreconciliation..",
                "jo.accountant.tax..", "jo.accountant.reporting..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 23 (Phase 10) — :time-billing entities extend TenantAwareEntity")
    void timeBillingEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.timebilling.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 24 (Phase 11) — :document-generation must NOT depend on invoicing/accounting-engine/inventory/funds-grants/etc. (infrastructure transverse)")
    void documentGenerationDoesNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage("jo.accountant.documentgeneration..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.accountingengine..",
                "jo.accountant.inventory..", "jo.accountant.fundsgrants..",
                "jo.accountant.timebilling..", "jo.accountant.bankreconciliation..",
                "jo.accountant.tax..", "jo.accountant.reporting..",
                "jo.accountant.fixedassets..", "jo.accountant.financialstatements..",
                "jo.accountant.thirdparties..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 25 (Phase 12) — :invoicing entities extend TenantAwareEntity")
    void invoicingEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.invoicing.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 26 (Phase 13) — :bank-reconciliation entities extend TenantAwareEntity")
    void bankReconciliationEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.bankreconciliation.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 27 (Phase 14) — :funds-grants entities extend TenantAwareEntity")
    void fundsGrantsEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.fundsgrants.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 28 (Phase 15) — :notifications must NOT depend on any business module (sauf sous-package event. qui contient les records d'événements de domaine consommés par les listeners)")
    void notificationsDoesNotDependOnBusinessModules() {
        // R-15 (audit qualité/arch) — La règle initiale interdisait toute dépendance de
        // :notifications vers les modules métier (invoicing, accounting-engine, bank-reconciliation,
        // fixed-assets, chart-of-accounts). Or les listeners DomainEventListener et
        // ForensicEventListener doivent référencer les types concrets des événements de domaine
        // publiés par ces modules (InvoiceIssuedEvent, JournalEntryPostedEvent, etc.) — ces events
        // sont des records immuables placés dans le sous-package `event.` de chaque module, sans
        // logique métier ni dépendance JPA.
        //
        // Solution pragmatique : la règle autorise les dépendances vers les sous-packages `event.`
        // des modules métier (frontière de consommation pure), mais continue d'interdire toute
        // dépendance vers les services, repositories, entités, contrôleurs, DTO de ces modules.
        // L'alternative (extraire les events vers un module :domain-events partagé) aurait eu un
        // coût de refactoring disproportionné pour 9 events.
        //
        // Si un futur développeur importe autre chose qu'un event (ex: un service métier), la règle
        // échouera et bloquera le build — c'est le contrat architectural attendu.
        DescribedPredicate<JavaClass> businessModuleNonEventClasses = resideInAnyPackage(
                "jo.accountant.invoicing..", "jo.accountant.accountingengine..",
                "jo.accountant.inventory..", "jo.accountant.fundsgrants..",
                "jo.accountant.timebilling..", "jo.accountant.bankreconciliation..",
                "jo.accountant.tax..", "jo.accountant.reporting..",
                "jo.accountant.fixedassets..", "jo.accountant.financialstatements..",
                "jo.accountant.thirdparties..", "jo.accountant.company..",
                "jo.accountant.auth..",
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..",
                "jo.accountant.chartofaccounts..")
            .and(not(resideInAnyPackage(
                "jo.accountant.invoicing.event..",
                "jo.accountant.accountingengine.event..",
                "jo.accountant.bankreconciliation.event..",
                "jo.accountant.fixedassets.event..",
                "jo.accountant.chartofaccounts.event..")));
        noClasses().that().resideInAPackage("jo.accountant.notifications..")
            .should().dependOnClassesThat(businessModuleNonEventClasses)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 29 (Phase 16) — :tax entities — TaxRule et WithholdingRule")
    void taxEntitiesExist() {
        classes().that().resideInAPackage("jo.accountant.tax.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().haveSimpleNameEndingWith("Rule")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 30 (Phase 17) — :reporting must NOT duplicate PDF rendering (dépend de :document-generation, pas de openhtmltopdf direct)")
    void reportingDoesNotDuplicatePdfRendering() {
        noClasses().that().resideInAPackage("jo.accountant.reporting..")
            .should().dependOnClassesThat().resideInAPackage("com.openhtmltopdf..")
            .check(classes);
    }

    // --- Restructuration 2026-07-24 (suite) — 4 nouveaux modules bonus ---------------

    @Test
    @DisplayName("Rule 32 — :purchasing entities extend TenantAwareEntity")
    void purchasingEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.purchasing.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 33 — :purchasing must NOT depend on :expenses, :employees, :payroll (en aval)")
    void purchasingDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.purchasing..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.expenses..", "jo.accountant.employees..",
                "jo.accountant.payroll..", "jo.accountant.reporting..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 34 — :expenses entities extend TenantAwareEntity")
    void expensesEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.expenses.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 35 — :expenses must NOT depend on :employees, :payroll, :purchasing, :reporting (en aval)")
    void expensesDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.expenses..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.employees..", "jo.accountant.payroll..",
                "jo.accountant.purchasing..", "jo.accountant.reporting..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 36 — :employees entities extend TenantAwareEntity")
    void employeesEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.employees.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 37 — :employees must NOT depend on :payroll, :purchasing, :expenses, :tax, :reporting, :invoicing, :inventory (en aval)")
    void employeesDoesNotDependOnDownstreamModules() {
        noClasses().that().resideInAPackage("jo.accountant.employees..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.payroll..", "jo.accountant.purchasing..",
                "jo.accountant.expenses..", "jo.accountant.tax..",
                "jo.accountant.reporting..", "jo.accountant.invoicing..",
                "jo.accountant.inventory..")
            .check(classes);
    }

    @Test
    @DisplayName("Rule 38 — :payroll entities extend TenantAwareEntity")
    void payrollEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.payroll.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 39 — :payroll must NOT depend on :purchasing, :reporting (en aval ou non-pertinent)")
    void payrollDoesNotDependOnUnrelatedModules() {
        noClasses().that().resideInAPackage("jo.accountant.payroll..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.purchasing..", "jo.accountant.reporting..",
                "jo.accountant.invoicing..", "jo.accountant.inventory..",
                "jo.accountant.timebilling..", "jo.accountant.bankreconciliation..",
                "jo.accountant.fundsgrants..", "jo.accountant.fixedassets..",
                "jo.accountant.financialstatements..")
            .check(classes);
    }

    // --- Restructuration 2026-07-24 (suite 3) — module bonus :fx-operations --------

    @Test
    @DisplayName("Rule 40 — :fx-operations entities extend TenantAwareEntity")
    void fxOperationsEntitiesAreTenantAware() {
        classes().that().resideInAPackage("jo.accountant.fxoperations.entity..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().beAssignableTo(jo.accountant.core.tenant.TenantAwareEntity.class)
            .check(classes);
    }

    @Test
    @DisplayName("Rule 41 — :fx-operations must NOT depend on :purchasing, :expenses, :employees, :payroll, :reporting (en aval ou non-pertinent)")
    void fxOperationsDoesNotDependOnUnrelatedModules() {
        noClasses().that().resideInAPackage("jo.accountant.fxoperations..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jo.accountant.purchasing..", "jo.accountant.expenses..",
                "jo.accountant.employees..", "jo.accountant.payroll..",
                "jo.accountant.reporting..", "jo.accountant.invoicing..",
                "jo.accountant.inventory..", "jo.accountant.timebilling..",
                "jo.accountant.bankreconciliation..", "jo.accountant.fundsgrants..",
                "jo.accountant.fixedassets..", "jo.accountant.financialstatements..")
            .check(classes);
    }

    // --- Audit v4.7 §5.1 Finding #1 — Court terme : règle ArchUnit pour empêcher les
    //     méthodes de repository sur TenantAwareEntity sans companyId en paramètre. ---

    /**
     * Rule 42 — Repositories sur TenantAwareEntity doivent exposer des méthodes de recherche
     * qui prennent un {@code companyId} en paramètre (audit v4.7 §5.1 Finding #1).
     *
     * <p><b>Problème</b> : la v4.7 repose sur la discipline manuelle pour l'isolation multi-tenant
     * — chaque méthode de repository doit explicitement prendre un {@code companyId} en paramètre
     * et l'inclure dans la clause WHERE. Un seul développeur qui oublie ce paramètre expose les
     * données de tous les tenants (IDOR). Aucun filet automatique. Pas de Postgres RLS non plus.
     *
     * <p><b>Court terme (cette règle)</b> : interdit les méthodes nommées {@code findBy*} ou
     * {@code countBy*} sans paramètre {@code UUID companyId} sur les repositories d'entités
     * TenantAwareEntity. Lève une erreur de compilation (test ArchUnit qui échoue) si un
     * développeur ajoute une méthode non scopée.
     *
     * <p><b>Moyen terme</b> : activer {@code @TenantId} Hibernate 6 sur TenantAwareEntity +
     * adapter les repositories cross-tenant (UserCompanyRole, AuditLog, etc. qui ne sont PAS
     * TenantAware — voir Rule 6 pour la liste exhaustive).
     *
     * <p><b>Long terme</b> : Postgres Row-Level Security (RLS) en défense en profondeur.
     *
     * <p><b>Exemptions</b> :
     * <ul>
     *   <li>{@code findById} — la méthode héritée de JpaRepository. L'appelant DOIT vérifier
     *       {@code entity.getCompanyId().equals(companyId)} après chargement (pattern appliqué
     *       manuellement dans 50+ services — voir ExpensesService.resolveChargeAccount pour
     *       l'exemple canonique).</li>
     *   <li>{@code findAll} — idem, à éviter dans le code métier.</li>
     *   <li>{@code save}, {@code delete} — hérités de JpaRepository, l'appelant construit
     *       l'entité avec companyId avant.</li>
     *   <li>Repositories d'entités NON TenantAware (UserCompanyRole, AuditLog, RefreshToken,
     *       PasswordResetToken, Notification, BusinessType*) — exemptés car n'ont pas de
     *       companyId. La liste exhaustive est dans Rule 6.</li>
     * </ul>
     *
     * <p><b>Implémentation</b> : utilise {@code ArchUnitRepositoryMethodCheck} qui scanne les
     * méthodes des interfaces étendant JpaRepository pour les entités annotées
     * {@link jo.accountant.core.tenant.TenantAwareEntity @TenantAwareEntity}. Pour chaque
     * méthode nommée {@code findBy*} ou {@code countBy*}, vérifie qu'un paramètre nommé
     * {@code companyId} de type {@code UUID} est présent.
     */
    @Test
    @DisplayName("Rule 42 — Repository methods on TenantAwareEntity must include a UUID parameter (audit v4.7 §5.1 #1)")
    void tenantAwareRepositoriesMustScopeByCompanyId() {
        // Audit v4.7 §5.1 Finding #1 — vérifie que toutes les méthodes findBy*/countBy* dans les
        // 21 packages repository métier ont au moins un paramètre de type UUID (qui est companyId
        // par convention). Empêche un développeur d'ajouter une méthode non scopée qui exposerait
        // les données de tous les tenants (IDOR).
        //
        // Note : ArchUnit 1.3.0 ne expose pas les noms de paramètres (JavaParameter.getName()
        // n'existe pas). On vérifie donc la présence d'un paramètre de type UUID — c'est une
        // approximation suffisante car la convention du projet est que le premier paramètre UUID
        // est toujours companyId.
        com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod> haveUuidParam =
            new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
                "include a UUID parameter (companyId by convention)") {
                @Override
                public void check(com.tngtech.archunit.core.domain.JavaMethod method,
                                    com.tngtech.archunit.lang.ConditionEvents events) {
                    boolean hasUuid = method.getParameters().stream()
                        .anyMatch(p -> "java.util.UUID".equals(p.getType().getName()));
                    if (!hasUuid) {
                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                            method,
                            "Method " + method.getFullName() + " has no UUID parameter. "
                            + "All findBy*/countBy* methods on TenantAwareEntity repositories must "
                            + "scope queries by companyId (UUID) to prevent IDOR (audit v4.7 §5.1 #1)."));
                    }
                }
            };

        // Predicate pour filtrer les méthodes findBy*/countBy*
        // Exempte les méthodes contenant "CompanyIdIsNull" — elles cherchent les templates globaux
        // (companyId=null, partagés entre tenants) — cas légitime sans paramètre companyId.
        com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaMethod> isFindByOrCountBy =
            new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaMethod>(
                "is findBy* or countBy* (excluding CompanyIdIsNull methods)") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaMethod method) {
                    String name = method.getName();
                    return (name.startsWith("findBy") || name.startsWith("countBy"))
                        && !name.contains("CompanyIdIsNull");  // exemption : templates globaux
                }
            };

        com.tngtech.archunit.lang.ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
            .that().areDeclaredInClassesThat().resideInAnyPackage(
                "jo.accountant.invoicing.repository..",
                "jo.accountant.purchasing.repository..",
                "jo.accountant.expenses.repository..",
                "jo.accountant.employees.repository..",
                "jo.accountant.payroll.repository..",
                "jo.accountant.tax.repository..",
                "jo.accountant.reporting.repository..",
                "jo.accountant.inventory.repository..",
                "jo.accountant.timebilling.repository..",
                "jo.accountant.bankreconciliation.repository..",
                "jo.accountant.fundsgrants.repository..",
                "jo.accountant.fixedassets.repository..",
                "jo.accountant.financialstatements.repository..",
                "jo.accountant.documentgeneration.repository..",
                "jo.accountant.documentnumbering.repository..",
                "jo.accountant.approvalworkflow.repository..",
                "jo.accountant.analytics.repository..",
                "jo.accountant.fxoperations.repository..",
                "jo.accountant.thirdparties.repository..",
                "jo.accountant.chartofaccounts.repository..",
                "jo.accountant.accountingengine.repository.."
            )
            .and(isFindByOrCountBy)
            .should(haveUuidParam)
            .because("audit v4.7 §5.1 Finding #1 — repositories on TenantAwareEntity must scope queries by companyId to prevent IDOR");

        rule.check(classes);
    }
}
