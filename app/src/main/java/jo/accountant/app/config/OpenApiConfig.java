package jo.accountant.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.1 global config (§3.8).
 *
 * <p>Définit le schéma de sécurité Bearer JWT une seule fois (réutilisé via
 * {@code @SecurityRequirement(name = "bearerAuth")} sur les endpoints protégés),
 * les métadonnées de l'API (titre, version, description, contact, licence), les
 * serveurs (dev / staging / prod), et la liste ordonnée des {@link Tag tags}
 * utilisés pour grouper les endpoints dans Swagger UI.
 *
 * <p><strong>GroupedOpenApi par module</strong> : des beans {@code GroupedOpenApi}
 * sont définis ci-dessous pour produire une page Swagger par module (accessible
 * via {@code /v3/api-docs/{groupName}}). Le groupe {@code all} (défaut) agrège
 * tous les endpoints.
 *
 * <h2>Tags standards</h2>
 * <ul>
 *   <li><b>Auth</b> — register / login / refresh / logout / forgot-password / reset-password</li>
 *   <li><b>MFA</b> — TOTP RFC 6238 (setup / verify / check / recovery-code / disable / status)</li>
 *   <li><b>Company</b> — CRUD sociétés + wizard + modules + legal fields (PATCH /legal)</li>
 *   <li><b>Accounting Engine</b> — écritures, journaux, balance, exercices fiscaux, RLS</li>
 *   <li><b>Invoicing</b> — factures clients + Factur-X + reverse charge</li>
 *   <li><b>Purchasing</b> — factures fournisseurs + void</li>
 *   <li><b>Purchase Orders</b> — bons de commande + 3-way match (V48)</li>
 *   <li><b>Tax</b> — règles fiscales + TVA (débits/encaissements) + cotisations (V40)</li>
 *   <li><b>Payroll</b> — campagnes de paie + PayrollCalculator + bulletins</li>
 *   <li><b>Employees</b> — annuaire RH + heures supp / absences (V49)</li>
 *   <li><b>Fixed Assets</b> — immobilisations + composants IAS 16 + dépréciation IAS 36</li>
 *   <li><b>Expenses</b> — notes de frais + catégories avec plafonds (V43)</li>
 *   <li><b>Bank Reconciliation</b> — rapprochement bancaire + lettrage</li>
 *   <li><b>Third Parties</b> — tiers (clients/fournisseurs/donateurs) + legal fields (V42)</li>
 *   <li><b>Chart of Accounts</b> — plan comptable multi-référentiels</li>
 *   <li><b>Financial Statements</b> — bilan + compte de résultat + cash flow (IAS 7)</li>
 *   <li><b>Reporting</b> — dashboard + balance âgée clients/fournisseurs</li>
 *   <li><b>Notifications</b> — notifications in-app + mark-all-read</li>
 *   <li><b>Approval Workflow</b> — workflow 4-yeux</li>
 *   <li><b>Audit Trail</b> — journal d'audit forensique</li>
 *   <li><b>Document Generation</b> — PDF / CSV / Factur-X</li>
 *   <li><b>Document Numbering</b> — séquences documentaires</li>
 *   <li><b>Inventory</b> — stock + valorisation + mouvements</li>
 *   <li><b>Time Billing</b> — projets + feuilles de temps + utilization</li>
 *   <li><b>Funds &amp; Grants</b> — subventions ONG</li>
 *   <li><b>FX Operations</b> — opérations de change</li>
 *   <li><b>Batch Admin</b> — jobs Spring Batch (paie + clôture annuelle)</li>
 *   <li><b>JWKS</b> — endpoint RFC 7517 (clé publique RS256)</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(@Value("${app.openapi.server-url:http://localhost:8080}") String serverUrl,
                            @Value("${app.openapi.server-desc:Local development}") String serverDesc) {
        return new OpenAPI()
            .info(new Info()
                .title("JOAccountant API")
                .version("5.2.0")
                .description("Multi-tenant, multi-secteur, multi-référentiel SaaS comptable backend " +
                             "(Spring Boot 3.5 / Java 17 / PostgreSQL 14+).\n\n" +
                             "## Authentification\n" +
                             "Tous les endpoints company-scoped requièrent un Bearer JWT dont le claim " +
                             "`companies` liste les paires `{companyId, role}` acceptées par l'utilisateur. " +
                             "Le header `Authorization: Bearer <jwt>` doit être envoyé sur chaque requête ; " +
                             "l'`AuthInterceptor` côté backend valide le JWT et le `TenantContextFilter` " +
                             "extrait le `companyId` depuis l'URL.\n\n" +
                             "## MFA 2-step login (RFC 6238 TOTP)\n" +
                             "Si la MFA est activée pour l'utilisateur, `POST /auth/login` renvoie " +
                             "`mfaRequired=true` + un `mfaChallengeToken` (JWT 5 min) au lieu des tokens " +
                             "normaux. Le client doit alors appeler `POST /auth/login/mfa?mfaChallengeToken=&code=` " +
                             "pour obtenir les vrais tokens d'accès. La MFA est obligatoire pour les rôles " +
                             "OWNER et ADMIN (NIST 800-63B AAL2).\n\n" +
                             "## Multi-référentiels comptables\n" +
                             "6 référentiels supportés : SYCEBNH (Haïti), PCG (France), SYSCOHADA (OHADA), " +
                             "IFRS-Full, IFRS-SME, US-GAAP. Le référentiel est choisi par société via le wizard " +
                             "de création et immuable après le 1er exercice clôturé.\n\n" +
                             "## Conformité\n" +
                             "- **Factur-X** (Loi 2023-314) : facturation électronique B2B France 2026 — " +
                             "endpoint `GET /invoicing/invoices/{id}/factur-x`.\n" +
                             "- **IAS 16/36** : amortissement par composant + test de dépréciation.\n" +
                             "- **IAS 7** : tableau de flux de trésorerie.\n" +
                             "- **TVA encaissement** (art. 289 II CGI) : V44.\n" +
                             "- **Reverse charge** (art. 196 CGI) : V45.\n" +
                             "- **Row Level Security** PostgreSQL (V51) : défense en profondeur.\n")
                .contact(new Contact()
                    .name("JOAccountant Team")
                    .email("dev@joaccountant.ht")
                    .url("https://github.com/joaccountant/backend"))
                .license(new License()
                    .name("Propriétaire")
                    .url("https://joaccountant.ht/licence")))
            .servers(List.of(
                new Server().url(serverUrl).description(serverDesc),
                new Server().url("https://api.joaccountant.ht").description("Production"),
                new Server().url("https://staging.api.joaccountant.ht").description("Staging")
            ))
            .components(new Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .in(SecurityScheme.In.HEADER)
                        .name("Authorization")
                        .description("JWT obtenu via `POST /api/v1/auth/login` (ou `POST /auth/login/mfa` " +
                                     "si MFA activée). Format : `Bearer <token>`.")))
            .tags(List.of(
                new Tag().name("Auth").description("Authentification JWT + refresh rotatif + reset password"),
                new Tag().name("MFA").description("MFA TOTP RFC 6238 — setup / verify / check / recovery / disable / status"),
                new Tag().name("JWKS").description("Endpoint RFC 7517 — clé publique RS256 (public, pas de Bearer)"),
                new Tag().name("Company").description("CRUD sociétés + wizard + modules + legal fields (V42)"),
                new Tag().name("Accounting Engine").description("Écritures, journaux, balance, exercices fiscaux, RLS PostgreSQL"),
                new Tag().name("Invoicing").description("Factures clients + Factur-X (Loi 2023-314) + reverse charge (V45)"),
                new Tag().name("Purchasing").description("Factures fournisseurs + void"),
                new Tag().name("Purchase Orders").description("Bons de commande + 3-way match PO/GR/Invoice (V48)"),
                new Tag().name("Tax").description("Règles fiscales + TVA (débits/encaissements, V44) + cotisations (V40)"),
                new Tag().name("Payroll").description("Campagnes de paie + PayrollCalculator + bulletins (C. trav. R3243-1)"),
                new Tag().name("Employees").description("Annuaire RH + heures supp / absences (V49)"),
                new Tag().name("Fixed Assets").description("Immobilisations + composants IAS 16 + dépréciation IAS 36 (V47)"),
                new Tag().name("Expenses").description("Notes de frais + catégories avec plafonds (V43)"),
                new Tag().name("Bank Reconciliation").description("Rapprochement bancaire + lettrage"),
                new Tag().name("Third Parties").description("Tiers (clients/fournisseurs/donateurs) + legal fields (V42)"),
                new Tag().name("Chart of Accounts").description("Plan comptable multi-référentiels"),
                new Tag().name("Financial Statements").description("Bilan + compte de résultat + cash flow IAS 7"),
                new Tag().name("Reporting").description("Dashboard + balance âgée clients/fournisseurs"),
                new Tag().name("Notifications").description("Notifications in-app + mark-all-read"),
                new Tag().name("Approval Workflow").description("Workflow 4-yeux"),
                new Tag().name("Audit Trail").description("Journal d'audit forensique"),
                new Tag().name("Document Generation").description("PDF / CSV / Factur-X"),
                new Tag().name("Document Numbering").description("Séquences documentaires"),
                new Tag().name("Inventory").description("Stock + valorisation + mouvements"),
                new Tag().name("Time Billing").description("Projets + feuilles de temps + utilization"),
                new Tag().name("Funds & Grants").description("Subventions ONG"),
                new Tag().name("FX Operations").description("Opérations de change"),
                new Tag().name("Batch Admin").description("Jobs Spring Batch (paie + clôture annuelle) — V52")
            ));
    }

    // ────────────────────────────────────────────────────────────────────
    //  GroupedOpenApi par module — page Swagger dédiée par module
    //  (accessible via /v3/api-docs/{groupName} et /swagger-ui.html?group={groupName})
    // ────────────────────────────────────────────────────────────────────

    @Bean
    public org.springdoc.core.models.GroupedOpenApi authGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("auth")
                .displayName("Authentification + MFA + JWKS")
                .pathsToMatch("/api/v1/auth/**", "/.well-known/jwks.json")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi companyGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("company")
                .displayName("Sociétés + Modules + Legal Fields")
                .pathsToMatch("/api/v1/companies", "/api/v1/companies/**",
                        "/api/v1/business-types")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi accountingGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("accounting")
                .displayName("Moteur comptable + Écritures + Exercices")
                .pathsToMatch("/api/v1/companies/{companyId}/accounting-engine/**",
                        "/api/v1/companies/{companyId}/journal-entries/**",
                        "/api/v1/companies/{companyId}/fiscal-years/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi invoicingGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("invoicing")
                .displayName("Facturation + Factur-X + Reverse Charge")
                .pathsToMatch("/api/v1/companies/{companyId}/invoicing/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi purchasingGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("purchasing")
                .displayName("Achats + Bons de commande + 3-way match")
                .pathsToMatch("/api/v1/companies/{companyId}/purchase-invoices/**",
                        "/api/v1/companies/{companyId}/purchase-orders/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi taxGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("tax")
                .displayName("Fiscalité + TVA + Cotisations")
                .pathsToMatch("/api/v1/companies/{companyId}/tax/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi payrollGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("payroll")
                .displayName("Paie + PayrollCalculator")
                .pathsToMatch("/api/v1/companies/{companyId}/payroll-runs/**",
                        "/api/v1/companies/{companyId}/payslips/**",
                        "/api/v1/companies/{companyId}/employees/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi fixedAssetsGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("fixed-assets")
                .displayName("Immobilisations + IAS 16/36")
                .pathsToMatch("/api/v1/companies/{companyId}/fixed-assets/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi financialStatementsGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("financial-statements")
                .displayName("États financiers + Cash Flow IAS 7")
                .pathsToMatch("/api/v1/companies/{companyId}/financial-statements/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi batchAdminGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("batch-admin")
                .displayName("Jobs Spring Batch (paie + clôture)")
                .pathsToMatch("/api/v1/companies/{companyId}/admin/batch/**")
                .build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi reportingGroup() {
        return org.springdoc.core.models.GroupedOpenApi.builder()
                .group("reporting")
                .displayName("Reporting + Dashboard + Balance âgée")
                .pathsToMatch("/api/v1/companies/{companyId}/reporting/**")
                .build();
    }
}
