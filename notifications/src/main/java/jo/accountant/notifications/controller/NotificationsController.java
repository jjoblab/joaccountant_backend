package jo.accountant.notifications.controller;

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
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import jo.accountant.notifications.dto.CreateAlertRuleRequest;
import jo.accountant.notifications.dto.NotificationResponse;
import jo.accountant.notifications.dto.UpdatePreferencesRequest;
import jo.accountant.notifications.entity.AlertRule;
import jo.accountant.notifications.entity.NotificationPreference;
import jo.accountant.notifications.service.NotificationsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de notifications (§9, §13 Phase 15).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/notifications")
@Tag(name = "Notifications", description = "Centre de notifications in-app + e-mail, règles d'alerte (§9)")
public class NotificationsController {

    private final NotificationsService service;
    private final RoleChecker roleChecker;

    public NotificationsController(NotificationsService service, RoleChecker roleChecker) {
        this.service = service;
        this.roleChecker = roleChecker;
    }

    @Operation(summary = "Lister mes notifications (paginé)",
        description = "Pagination via ?page=&size= (défaut 0/20, size capped à 200). " +
                      "Finding #3 — remplace la variante List<> pour éviter l'OOM sur utilisateurs " +
                      "avec un fort volume de notifications historiques.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = NotificationResponse.class),
                examples = @ExampleObject(name = "Page de 3 notifications", value = """
                    {
                      "content": [
                        {
                          "id": "0192c101-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "recipientUserId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                          "type": "INVOICE_ISSUED",
                          "payloadJson": "{\"invoiceId\":\"0192c102-1c2d-3e4f-5a6b-7c8d9e0fabcd\",\"invoiceNumber\":\"FAC-2026-0001\",\"amount\":1200.00}",
                          "channel": "IN_APP",
                          "status": "SENT",
                          "createdAt": "2026-03-15T10:00:00Z",
                          "sentAt": "2026-03-15T10:00:00Z",
                          "readAt": null
                        },
                        {
                          "id": "0192c101-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "recipientUserId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                          "type": "PAYMENT_RECEIVED",
                          "payloadJson": "{\"paymentId\":\"0192c103-1c2d-3e4f-5a6b-7c8d9e0fabcd\",\"amount\":850.00,\"currency\":\"EUR\"}",
                          "channel": "IN_APP",
                          "status": "SENT",
                          "createdAt": "2026-03-14T16:30:00Z",
                          "sentAt": "2026-03-14T16:30:00Z",
                          "readAt": "2026-03-14T17:00:00Z"
                        },
                        {
                          "id": "0192c101-3e4f-5a6b-7c8d-9e0fa1bcde02",
                          "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                          "recipientUserId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                          "type": "FISCAL_YEAR_CLOSING",
                          "payloadJson": "{\"fiscalYearId\":\"0192a8f0-1c2d-3e4f-5a6b-7c8d9e0fabcd\",\"deadline\":\"2026-03-31\"}",
                          "channel": "EMAIL",
                          "status": "SENT",
                          "createdAt": "2026-03-10T08:00:00Z",
                          "sentAt": "2026-03-10T08:05:00Z",
                          "readAt": null
                        }
                      ],
                      "totalElements": 3,
                      "totalPages": 1,
                      "number": 0,
                      "size": 20,
                      "first": true,
                      "last": true,
                      "empty": false
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (VIEWER minimum requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping
    public org.springframework.data.domain.Page<NotificationResponse> listNotifications(
        @PathVariable UUID companyId,
        @CurrentUser UUID userId,
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "0") int page,
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "20") int size) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // Finding #3 — PageRequest cappé à 200 (empêche l'OOM si un client demande size=10000).
        org.springframework.data.domain.Pageable pageable =
            org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        return service.listNotifications(userId, pageable);
    }

    @Operation(summary = "Marquer une notification comme lue",
        description = "Passe la notification à status=READ + readAt=now. Idempotent (déjà lue = no-op).")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = NotificationResponse.class),
                examples = @ExampleObject(name = "Notification marquée comme lue", value = """
                    {
                      "id": "0192c101-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "recipientUserId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "type": "INVOICE_ISSUED",
                      "channel": "IN_APP",
                      "status": "READ",
                      "createdAt": "2026-03-15T10:00:00Z",
                      "sentAt": "2026-03-15T10:00:00Z",
                      "readAt": "2026-03-15T14:25:00Z"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "Notification introuvable / hors tenant",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PatchMapping("/{notificationId}/read")
    public NotificationResponse markAsRead(@PathVariable UUID companyId,
                                           @PathVariable UUID notificationId,
                                           @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        // Audit v4.7 §6.2 — propager companyId au service pour filtrage tenant
        return service.markAsRead(companyId, notificationId, userId);
    }

    /**
     * POST /notifications/mark-all-read — marque toutes les notifications non lues comme lues.
     * Bulk update en une seule requête (corrige le phantom endpoint mobile).
     */
    @Operation(summary = "Marquer toutes les notifications comme lues",
        description = "Bulk update — marque toutes les notifications non lues de l'utilisateur courant comme lues.")
    @ApiResponse(responseCode = "200",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = java.util.List.class)))
    @PostMapping("/mark-all-read")
    public java.util.List<NotificationResponse> markAllAsRead(@PathVariable UUID companyId,
                                                                @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.markAllAsRead(companyId, userId);
    }

    @Operation(summary = "Lister mes préférences de notification",
        description = "Retourne les préférences de l'utilisateur par type de notification (IN_APP / EMAIL activés ou non).")
    @ApiResponse(responseCode = "200",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = NotificationPreference.class),
            examples = @ExampleObject(name = "2 préférences", value = """
                [
                  {
                    "id": "0192c104-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                    "type": "INVOICE_ISSUED",
                    "inAppEnabled": true,
                    "emailEnabled": true
                  },
                  {
                    "id": "0192c104-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                    "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                    "type": "FISCAL_YEAR_CLOSING",
                    "inAppEnabled": true,
                    "emailEnabled": false
                  }
                ]
                """)))
    @GetMapping("/preferences")
    public List<NotificationPreference> getPreferences(@PathVariable UUID companyId,
                                                       @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.getPreferences(userId, companyId);
    }

    @Operation(summary = "Mettre à jour mes préférences",
        description = "Active/désactive un canal (IN_APP / EMAIL) pour un type de notification donné.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = NotificationPreference.class),
                examples = @ExampleObject(name = "Préférence mise à jour", value = """
                    {
                      "id": "0192c104-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "type": "INVOICE_ISSUED",
                      "inAppEnabled": true,
                      "emailEnabled": false
                    }
                    """))),
        @ApiResponse(responseCode = "422", description = "Type inconnu",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PatchMapping(value = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NotificationPreference updatePreferences(@PathVariable UUID companyId,
                                                    @CurrentUser UUID userId,
                                                    @Valid @RequestBody UpdatePreferencesRequest req) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.updatePreferences(userId, companyId, req);
    }

    @Operation(summary = "Créer une règle d'alerte",
        description = "Crée une règle d'alerte configurable (ex : alerter si solde bancaire < seuil, " +
                      "si facture en retard > 30 jours, etc.).")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AlertRule.class),
                examples = @ExampleObject(name = "Règle d'alerte seuil bancaire", value = """
                    {
                      "id": "0192c105-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                      "code": "LOW_CASH_ALERT",
                      "label": "Alerte si solde bancaire < 10000€",
                      "type": "LOW_CASH_THRESHOLD",
                      "threshold": 10000.00,
                      "active": true
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Rôle insuffisant (ADMIN requis)",
            content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(value = "/alert-rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertRule> createAlertRule(@PathVariable UUID companyId,
                                                      @CurrentUser UUID userId,
                                                      @Valid @RequestBody CreateAlertRuleRequest req) {
        roleChecker.ensureRole(companyId, "ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createAlertRule(companyId, req));
    }

    @Operation(summary = "Lister les règles d'alerte",
        description = "Retourne toutes les règles d'alerte configurées pour l'entreprise.")
    @ApiResponse(responseCode = "200",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = AlertRule.class),
            examples = @ExampleObject(name = "2 règles d'alerte", value = """
                [
                  {
                    "id": "0192c105-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "code": "LOW_CASH_ALERT",
                    "label": "Alerte si solde bancaire < 10000€",
                    "type": "LOW_CASH_THRESHOLD",
                    "threshold": 10000.00,
                    "active": true
                  },
                  {
                    "id": "0192c105-2d3e-4f5a-6b7c-8d9e0fa1bcde",
                    "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
                    "code": "OVERDUE_INVOICE_ALERT",
                    "label": "Facture client en retard > 30 jours",
                    "type": "OVERDUE_INVOICE_THRESHOLD",
                    "threshold": 30,
                    "active": true
                  }
                ]
                """)))
    @GetMapping("/alert-rules")
    public List<AlertRule> listAlertRules(@PathVariable UUID companyId,
                                          @CurrentUser UUID userId) {
        roleChecker.ensureRole(companyId, "VIEWER");
        return service.listAlertRules(companyId);
    }
}
