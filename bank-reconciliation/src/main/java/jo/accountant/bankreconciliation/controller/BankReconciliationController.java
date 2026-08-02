package jo.accountant.bankreconciliation.controller;

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
import jo.accountant.bankreconciliation.dto.CreateBankAccountRequest;
import jo.accountant.bankreconciliation.dto.ImportBankStatementRequest;
import jo.accountant.bankreconciliation.dto.ImportResult;
import jo.accountant.bankreconciliation.dto.MatchRequest;
import jo.accountant.bankreconciliation.dto.ReconciliationStatus;
import jo.accountant.bankreconciliation.entity.BankAccount;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import jo.accountant.bankreconciliation.service.BankReconciliationService;
import jo.accountant.company.entity.ModuleCode;
import jo.accountant.company.security.ModuleAccessGuard;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
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
 * Endpoints de rapprochement bancaire (§13 Phase 13).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/bank-reconciliation")
@Tag(name = "BankReconciliation", description = "Import et lettrage bancaire, rapprochement automatique (§13 Phase 13)")
public class BankReconciliationController {

 private final BankReconciliationService service;
 private final RoleChecker roleChecker;
 private final ModuleAccessGuard moduleAccessGuard;

 public BankReconciliationController(BankReconciliationService service, RoleChecker roleChecker,
 ModuleAccessGuard moduleAccessGuard) {
 this.service = service;
 this.roleChecker = roleChecker;
 this.moduleAccessGuard = moduleAccessGuard;
 }

 @Operation(summary = "Créer un compte bancaire rattaché à un compte de trésorerie",
 description = "Crée un compte bancaire rattaché à un compte de trésorerie (classe 5 SYSCOHADA) pour permettre l'import de relevés.")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = BankAccount.class),
 examples = @ExampleObject(name = "Compte bancaire BNC créé", value = """
 {
 "id": "0192c0fd-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "label": "Compte courant BNC Pétion-Ville",
 "iban": null,
 "bankCode": "BNC",
 "accountNumber": "001-12345-67890",
 "currency": "HTG",
 "cashAccountId": "0192a8c0-7c8d-9e0f-a1bc-de0501030405",
 "active": true
 }
 """))),
 @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(value = "/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<BankAccount> createAccount(@PathVariable UUID companyId,
 @CurrentUser UUID userId,
 @Valid @RequestBody CreateBankAccountRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.BANK_RECONCILIATION);
 return ResponseEntity.status(HttpStatus.CREATED)
 .body(service.createBankAccount(companyId, req));
 }

 @Operation(summary = "Importer un relevé bancaire (CSV ou OFX)",
 description = "Parse le fichier, stocke le brut via FileStoragePort, crée les lignes, " +
 "tente le rapprochement automatique (montant exact).")
 @ApiResponses({
 @ApiResponse(responseCode = "201",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ImportResult.class),
 examples = @ExampleObject(name = "Import OFX 50 lignes + 35 auto-matchées", value = """
 {
 "importId": "0192c0fe-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "bankAccountId": "0192c0fd-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "format": "OFX",
 "lineCount": 50,
 "autoMatchedCount": 35,
 "importedAt": "2026-04-05T14:30:00Z",
 "lines": [
 {"id": "0192c0ff-1c2d-3e4f-5a6b-7c8d9e0fabcd", "date": "2026-03-31", "amount": 5000.00, "description": "VIREMENT CLIENT DUPONT", "matched": true}
 ]
 }
 """))),
 @ApiResponse(responseCode = "422", description = "Format inconnu ou fichier invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(value = "/accounts/{bankAccountId}/imports", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<ImportResult> importStatement(
 @PathVariable UUID companyId, @PathVariable UUID bankAccountId,
 @CurrentUser UUID userId, @Valid @RequestBody ImportBankStatementRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.BANK_RECONCILIATION);
 return ResponseEntity.status(HttpStatus.CREATED)
 .body(service.importStatement(companyId, bankAccountId, req));
 }

 @Operation(summary = "Rapprocher manuellement une ligne de relevé avec une écriture",
 description = "Lettrage manuel d'une ligne de relevé bancaire avec une ligne d'écriture comptable.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = BankStatementLine.class),
 examples = @ExampleObject(name = "Ligne rapprochée", value = """
 {
 "id": "0192c0ff-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "bankAccountId": "0192c0fd-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "date": "2026-03-31",
 "amount": 5000.00,
 "description": "VIREMENT CLIENT DUPONT",
 "matched": true,
 "matchedJournalLineId": "0192c100-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "matchedAt": "2026-04-05T15:00:00Z"
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Ligne ou écriture introuvable",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping(value = "/lines/{lineId}/match", consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<BankStatementLine> manualMatch(
 @PathVariable UUID companyId, @PathVariable UUID lineId,
 @CurrentUser UUID userId, @Valid @RequestBody MatchRequest req) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.BANK_RECONCILIATION);
 return ResponseEntity.ok(service.manualMatch(companyId, lineId, req));
 }

 @Operation(summary = "Annuler un rapprochement (unmatch)",
 description = "Audit v4.7 §3.1 .6 — Annule le rapprochement d'une ligne de relevé " +
 "bancaire. Passe matched=false, matchedJournalLineId=null, matchedAt=null. " +
 "Si un rapprochement est erroné (manuel ou auto), l'utilisateur peut " +
 "l'annuler sans intervention DBA.")
 @io.swagger.v3.oas.annotations.responses.ApiResponses({
 @ApiResponse(responseCode = "200", description = "Rapprochement annulé",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = BankStatementLine.class),
 examples = @ExampleObject(name = "Ligne dé-rapprochée (matched=false)", value = """
 {
 "id": "0192c0ff-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "bankAccountId": "0192c0fd-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "date": "2026-03-31",
 "amount": 5000.00,
 "description": "VIREMENT CLIENT DUPONT",
 "matched": false,
 "matchedJournalLineId": null,
 "matchedAt": null
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Ligne introuvable ou non rapprochée",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
 examples = @ExampleObject(value = """
 {
 "type": "https://joaccountant.ht/errors/not-found",
 "title": "Ligne introuvable",
 "status": 404,
 "detail": "Aucune ligne de relevé avec l'id 0192c0ff-1c2d-3e4f-5a6b-7c8d9e0fabcd pour cette entreprise.",
 "properties": {"code": "BANK_STATEMENT_LINE_NOT_FOUND"}
 }
 """)))
 })
 @PostMapping("/lines/{lineId}/unmatch")
 public ResponseEntity<BankStatementLine> unmatch(
 @PathVariable UUID companyId, @PathVariable UUID lineId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "BOOKKEEPER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.BANK_RECONCILIATION);
 return ResponseEntity.ok(service.unmatch(companyId, lineId));
 }

 @Operation(summary = "Statut de rapprochement d'un compte bancaire",
 description = "Retourne le compte total/matched/unmatched + la liste des lignes non rapprochées.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = ReconciliationStatus.class),
 examples = @ExampleObject(name = "Statut de rapprochement", value = """
 {
 "bankAccountId": "0192c0fd-1c2d-3e4f-5a6b-7c8d9e0fabcd",
 "label": "Compte courant BNC Pétion-Ville",
 "totalLines": 50,
 "matchedLines": 35,
 "unmatchedLines": 15,
 "totalDebit": 250000.00,
 "totalCredit": 180000.00,
 "unmatched": [
 {"id": "0192c0ff-2d3e-4f5a-6b7c-8d9e0fa1bcde", "date": "2026-03-25", "amount": -1200.00, "description": "FRAIS BANCAIRES"}
 ]
 }
 """))),
 @ApiResponse(responseCode = "404", description = "Compte bancaire introuvable",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping("/accounts/{bankAccountId}/status")
 public ReconciliationStatus getStatus(@PathVariable UUID companyId,
 @PathVariable UUID bankAccountId,
 @CurrentUser UUID userId) {
 roleChecker.ensureRole(companyId, "VIEWER");
 moduleAccessGuard.ensureEnabled(companyId, ModuleCode.BANK_RECONCILIATION);
 return service.getStatus(companyId, bankAccountId);
 }
}
