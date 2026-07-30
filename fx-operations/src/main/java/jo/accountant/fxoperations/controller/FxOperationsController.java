package jo.accountant.fxoperations.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import jo.accountant.core.currency.ExchangeRate;
import jo.accountant.core.currency.ExchangeRateService;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.fxoperations.dto.CreateExchangeRateRequest;
import jo.accountant.fxoperations.dto.CreateFxOperationRequest;
import jo.accountant.fxoperations.dto.FxOperationResponse;
import jo.accountant.fxoperations.service.FxOperationsService;
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
 * Endpoints des opérations en devises étrangères (restructuration 2026-07-24 suite 3).
 *
 * <p>Le module est <strong>toujours-actif</strong> (always-on — non concerné par
 * MODULE_NOT_ENABLED). Il réutilise {@code ExchangeRateService} du module :core pour la
 * conversion des montants.
 *
 * <p>Trois types d'opérations :
 * <ul>
 *   <li><b>BUY</b> — achat de devise étrangère (ex. HTG → USD)</li>
 *   <li><b>SELL</b> — vente de devise étrangère (ex. USD → HTG)</li>
 *   <li><b>REVALUATION</b> — réévaluation de fin de période au taux de clôture</li>
 * </ul>
 *
 * <p>Chaque opération génère une écriture comptable :
 * <ul>
 *   <li>BUY : D 521 / C 521 + (C 776 si gain OU D 676 si perte)</li>
 *   <li>SELL : D 521 / C 521 + (C 776 si gain OU D 676 si perte)</li>
 *   <li>REVALUATION : D 521 / C 776 (gain latent) OU D 676 / C 521 (perte latente)</li>
 * </ul>
 *
 * <p>Préalable : créer des taux de change via {@code POST /core/exchange-rates}
 * (module :core, endpoint non exposé publiquement au MVP — utiliser le service directement
 * ou un script d'admin).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/fx-operations")
@Tag(name = "FxOperations", description = "Opérations en devises étrangères (achat/vente/réévaluation)")
public class FxOperationsController {

    private final FxOperationsService service;
    private final ExchangeRateService exchangeRateService;
    private final RoleChecker roleChecker;
    private final jo.accountant.company.security.ModuleAccessGuard moduleAccessGuard;

    public FxOperationsController(FxOperationsService service,
                                    ExchangeRateService exchangeRateService,
                                    RoleChecker roleChecker,
                                    jo.accountant.company.security.ModuleAccessGuard moduleAccessGuard) {
        this.service = service;
        this.exchangeRateService = exchangeRateService;
        this.roleChecker = roleChecker;
        this.moduleAccessGuard = moduleAccessGuard;
    }

    @Operation(summary = "Créer un taux de change",
        description = "Crée un taux de change pour une date donnée. Le taux est direct " +
                      "(fromCurrency → toCurrency). Le service calcule l'inverse automatiquement " +
                      "si nécessaire lors des conversions.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ExchangeRate.class),
                examples = @ExampleObject(name = "Taux HTG/USD créé", value = """
                    {
                      "id": "0192c10d-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "fromCurrency": "HTG",
                      "toCurrency": "USD",
                      "rate": 0.0075,
                      "asOfDate": "2026-03-31",
                      "source": "BRH"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(value = "/rates", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExchangeRate> createRate(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreateExchangeRateRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        moduleAccessGuard.ensureEnabled(companyId, jo.accountant.company.entity.ModuleCode.FX_OPERATIONS);
        ExchangeRate rate = exchangeRateService.createRate(companyId,
            req.fromCurrency(), req.toCurrency(), req.rate(),
            req.asOfDate() != null ? req.asOfDate() : java.time.LocalDate.now(),
            req.source());
        return ResponseEntity.status(HttpStatus.CREATED).body(rate);
    }

    @Operation(summary = "Convertir un montant entre deux devises",
        description = "Convertit un montant en utilisant le taux applicable à la date donnée. " +
                      "Si aucun taux direct n'existe, le service cherche le taux inverse.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ConversionResponse.class),
                examples = @ExampleObject(name = "Conversion HTG → EUR", value = """
                    {
                      "originalAmount": 100000.00,
                      "fromCurrency": "HTG",
                      "convertedAmount": 750.00,
                      "toCurrency": "EUR",
                      "asOfDate": "2026-03-31"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Aucun taux trouvé pour cette paire/date",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/exchange-rate-not-found",
                      "title": "Taux de change introuvable",
                      "status": 404,
                      "detail": "Aucun taux HTG→EUR trouvé pour la date 2026-03-31.",
                      "properties": {"code": "EXCHANGE_RATE_NOT_FOUND"}
                    }
                    """)))
    })
    @GetMapping("/convert")
    public ConversionResponse convert(@PathVariable UUID companyId,
                                        @CurrentUser UUID userId,
                                        @Parameter(description = "Montant à convertir", required = true, example = "100000")
                                        @org.springframework.web.bind.annotation.RequestParam java.math.BigDecimal amount,
                                        @Parameter(description = "Code ISO 4217 source", required = true, example = "HTG")
                                        @org.springframework.web.bind.annotation.RequestParam String fromCurrency,
                                        @Parameter(description = "Code ISO 4217 cible", required = true, example = "EUR")
                                        @org.springframework.web.bind.annotation.RequestParam String toCurrency,
                                        @Parameter(description = "Date du taux", required = true, example = "2026-03-31")
                                        @org.springframework.web.bind.annotation.RequestParam java.time.LocalDate asOfDate) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, jo.accountant.company.entity.ModuleCode.FX_OPERATIONS);
        java.math.BigDecimal converted = exchangeRateService.convert(
            companyId, amount, fromCurrency, toCurrency, asOfDate);
        return new ConversionResponse(amount, fromCurrency, converted, toCurrency, asOfDate);
    }

    /** Réponse de l'endpoint /convert. */
    public record ConversionResponse(
        java.math.BigDecimal originalAmount,
        String fromCurrency,
        java.math.BigDecimal convertedAmount,
        String toCurrency,
        java.time.LocalDate asOfDate
    ) {}

    @Operation(summary = "Créer une opération de change",
        description = "Crée et poste une opération de change (BUY/SELL/REVALUATION). " +
                      "Génère automatiquement l'écriture comptable avec gain/perte de change.")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = FxOperationResponse.class),
                examples = @ExampleObject(name = "Achat USD contre HTG", value = """
                    {
                      "id": "0192c10e-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "type": "BUY",
                      "fromCurrency": "HTG",
                      "toCurrency": "USD",
                      "fromAmount": 133333.33,
                      "toAmount": 1000.00,
                      "rate": 0.0075,
                      "fromAmountFunctional": 133333.33,
                      "toAmountFunctional": 1000.00,
                      "fxGainLoss": 0,
                      "operationDate": "2026-03-31",
                      "description": "Achat USD pour paiement fournisseur",
                      "journalEntryId": "0192c10f-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "status": "POSTED",
                      "createdAt": "2026-03-31T14:00:00Z",
                      "updatedAt": "2026-03-31T14:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (BOOKKEEPER requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FxOperationResponse> create(
        @PathVariable UUID companyId, @CurrentUser UUID userId,
        @Valid @RequestBody CreateFxOperationRequest req) {
        roleChecker.ensureRole(companyId, "BOOKKEEPER");
        moduleAccessGuard.ensureEnabled(companyId, jo.accountant.company.entity.ModuleCode.FX_OPERATIONS);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(companyId, req));
    }

    @Operation(summary = "Lister les opérations de change",
        description = "Retourne toutes les opérations de change (BUY, SELL, REVALUATION) de l'entreprise.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = FxOperationResponse.class),
                examples = @ExampleObject(name = "3 opérations (BUY USD, SELL EUR, REVALUATION HTG)", value = """
                    [
                      {
                        "id": "0192c10e-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "type": "BUY",
                        "fromCurrency": "HTG",
                        "toCurrency": "USD",
                        "fromAmount": 133333.33,
                        "toAmount": 1000.00,
                        "rate": 0.0075,
                        "fxGainLoss": 0,
                        "operationDate": "2026-03-31",
                        "status": "POSTED"
                      },
                      {
                        "id": "0192c10e-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "type": "SELL",
                        "fromCurrency": "EUR",
                        "toCurrency": "HTG",
                        "fromAmount": 500.00,
                        "toAmount": 66666.67,
                        "rate": 133.33,
                        "fxGainLoss": 0,
                        "operationDate": "2026-03-20",
                        "status": "POSTED"
                      },
                      {
                        "id": "0192c10e-3e4f-5a6b-7c8d-9e0fa1bcde02",
                        "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                        "type": "REVALUATION",
                        "fromCurrency": "USD",
                        "toCurrency": "HTG",
                        "fromAmount": 5000.00,
                        "toAmount": 666666.67,
                        "rate": 133.33,
                        "fxGainLoss": 5000.00,
                        "operationDate": "2026-03-31",
                        "description": "Réévaluation fin de période solde USD compte 521",
                        "status": "POSTED"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping
    public List<FxOperationResponse> list(@PathVariable UUID companyId,
                                            @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, jo.accountant.company.entity.ModuleCode.FX_OPERATIONS);
        return service.list(companyId);
    }

    @Operation(summary = "Détail d'une opération de change",
        description = "Retourne une opération de change avec son écriture comptable associée.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = FxOperationResponse.class),
                examples = @ExampleObject(name = "Détail opération BUY USD", value = """
                    {
                      "id": "0192c10e-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "type": "BUY",
                      "fromCurrency": "HTG",
                      "toCurrency": "USD",
                      "fromAmount": 133333.33,
                      "toAmount": 1000.00,
                      "rate": 0.0075,
                      "fxGainLoss": 0,
                      "operationDate": "2026-03-31",
                      "description": "Achat USD pour paiement fournisseur",
                      "journalEntryId": "0192c10f-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "status": "POSTED",
                      "createdAt": "2026-03-31T14:00:00Z",
                      "updatedAt": "2026-03-31T14:00:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Opération introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public FxOperationResponse get(@PathVariable UUID companyId,
                                     @PathVariable UUID id,
                                     @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        moduleAccessGuard.ensureEnabled(companyId, jo.accountant.company.entity.ModuleCode.FX_OPERATIONS);
        return service.get(companyId, id);
    }
}
