package jo.accountant.fundsgrants.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.fundsgrants.dto.CloseFiscalYearResult;
import jo.accountant.fundsgrants.dto.CreateDonationReceiptRequest;
import jo.accountant.fundsgrants.dto.CreateGrantRequest;
import jo.accountant.fundsgrants.dto.DonorReport;
import jo.accountant.fundsgrants.dto.GrantResponse;
import jo.accountant.fundsgrants.entity.DonationReceipt;
import jo.accountant.fundsgrants.service.FundsGrantsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints des fonds et subventions (§13.
 
 *
 *

 *

 *

 *

 *

 *

 *
 * <p>Endpoints exposés :
 * <ul>
 *   <li>{@code GET  /}</li>
 *   <li>{@code GET  /}</li>
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 * </ul>

 * @author jo@Dev


*/
@RestController
@RequestMapping("/api/v1/companies/{companyId}/funds-grants")
@Tag(name = "FundsGrants", description = "Fonds, subventions, dons, fonds dédiés (secteur ONG, §13")
public class FundsGrantsController {

    private final FundsGrantsService service;
    private final RoleChecker roleChecker;
    private final ModuleAccessGuard moduleAccessGuard;

    public FundsGrantsController(FundsGrantsService service, RoleChecker roleChecker,
                                ModuleAccessGuard moduleAccessGuard) {
        this.service = service;
        this.roleChecker = roleChecker;
        this.moduleAccessGuard = moduleAccessGuard;
    }

    @Operation(summary = "Lister les subventions",
        description = "Retourne toutes les subventions de l'ONG (RESTRICTED et UNRESTRICTED).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = GrantResponse.class),
                examples = @ExampleObject(name = "2 subventions (RESTRICTED + UNRESTRICTED)", value = """
                    [
                      {
                        "id": "0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "donorThirdPartyId": "0192a8d3-3e4f-5a6b-7c8d-9e0fa1bcde04",
                        "code": "USAID-2026-WASH",
                        "label": "Subvention USAID programme WASH",
                        "totalAmount": 500000.00,
                        "currency": "USD",
                        "startDate": "2026-01-01",
                        "endDate": "2026-12-31",
                        "restrictionType": "RESTRICTED",
                        "analyticalValueId": "0192a8e0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "createdAt": "2026-01-05T09:00:00Z",
                        "updatedAt": "2026-01-05T09:00:00Z"
                      },
                      {
                        "id": "0192c109-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "donorThirdPartyId": "0192a8d3-4f5a-6b7c-8d9e-0fa1bcde05",
                        "code": "FOND-GEN-2026",
                        "label": "Fonds de fonctionnement général",
                        "totalAmount": 120000.00,
                        "currency": "EUR",
                        "startDate": "2026-01-01",
                        "endDate": null,
                        "restrictionType": "UNRESTRICTED",
                        "analyticalValueId": null,
                        "createdAt": "2026-01-10T08:00:00Z",
                        "updatedAt": "2026-01-10T08:00:00Z"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis) ou module FUNDS_GRANTS désactivé",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/grants")
    public List<GrantResponse> listGrants(@PathVariable UUID companyId,
                                          @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        return service.listGrants(companyId);
    }

    @Operation(summary = "Créer une subvention",
        description = "Rattachée à un bailleur (ThirdParty type DONOR). RESTRICTED déclenche " +
                      "le mécanisme des fonds dédiés à la clôture.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = GrantResponse.class),
                examples = @ExampleObject(name = "Subvention USAID RESTRICTED créée", value = """
                    {
                      "id": "0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "donorThirdPartyId": "0192a8d3-3e4f-5a6b-7c8d-9e0fa1bcde04",
                      "code": "USAID-2026-WASH",
                      "label": "Subvention USAID programme WASH",
                      "totalAmount": 500000.00,
                      "currency": "USD",
                      "startDate": "2026-01-01",
                      "endDate": "2026-12-31",
                      "restrictionType": "RESTRICTED",
                      "analyticalValueId": "0192a8e0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "createdAt": "2026-01-05T09:00:00Z",
                      "updatedAt": "2026-01-05T09:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Code vide ou totalAmount ≤ 0",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(value = "/grants", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GrantResponse> createGrant(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreateGrantRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createGrant(companyId, req));
    }

    @Operation(summary = "Créer un reçu de don",
        description = "Numéro généré via document-numbering (DocumentType.DONATION_RECEIPT).")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = DonationReceipt.class),
                examples = @ExampleObject(name = "Reçu de don créé", value = """
                    {
                      "id": "0192c10a-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "receiptNumber": "REC-2026-0001",
                      "donorThirdPartyId": "0192a8d3-4f5a-6b7c-8d9e-0fa1bcde05",
                      "amount": 5000.00,
                      "currency": "EUR",
                      "receiptDate": "2026-03-20",
                      "journalEntryId": "0192c10b-1c2d-3e4f-5a6b-7c8d9e0fabcd"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(value = "/donation-receipts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DonationReceipt> createDonationReceipt(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreateDonationReceiptRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createDonationReceipt(companyId, req));
    }

    @Operation(summary = "Rapport bailleur par subvention",
        description = "Montant reçu, dépenses, solde restant — pour reddition de comptes.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = DonorReport.class),
                examples = @ExampleObject(name = "Rapport bailleur USAID", value = """
                    {
                      "grantId": "0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "grantCode": "USAID-2026-WASH",
                      "grantLabel": "Subvention USAID programme WASH",
                      "totalReceived": 500000.00,
                      "totalSpent": 320000.00,
                      "remainingBalance": 180000.00,
                      "currency": "USD",
                      "asOf": "2026-07-31"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Subvention introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/grants/{grantId}/donor-report")
    public DonorReport getDonorReport(@PathVariable UUID companyId,
                                      @PathVariable UUID grantId,
                                      @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        return service.getDonorReport(companyId, grantId);
    }

    @Operation(summary = "Clôture d'exercice — fonds dédiés",
        description = "Pour une subvention RESTRICTED : calcule le solde (produits - charges), " +
                      "et si positif, soumet une ApprovalRequest pour l'écriture de fonds dédiés. " +
                      "Tant que non APPROVED, aucune écriture n'est postée.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CloseFiscalYearResult.class),
                examples = @ExampleObject(name = "Clôture fonds dédiés — solde positif", value = """
                    {
                      "grantId": "0192c109-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "fiscalYearId": "0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "products": 500000.00,
                      "charges": 320000.00,
                      "balance": 180000.00,
                      "approvalRequestId": "0192c10c-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "journalEntryId": null,
                      "status": "PENDING_APPROVAL"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ACCOUNTANT requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Clôture déjà effectuée pour cet exercice",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping("/grants/{grantId}/close-fiscal-year")
    public CloseFiscalYearResult closeFiscalYear(@PathVariable UUID companyId,
                                                  @PathVariable UUID grantId,
                                                  @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "ACCOUNTANT");
        moduleAccessGuard.ensureEnabled(companyId, ModuleCode.FUNDS_GRANTS);
        return service.closeFiscalYear(companyId, grantId);
    }
}
