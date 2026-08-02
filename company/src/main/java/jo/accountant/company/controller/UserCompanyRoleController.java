package jo.accountant.company.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.dto.CompanyUserResponse;
import jo.accountant.auth.dto.InviteUserRequest;
import jo.accountant.auth.dto.UpdateUserRoleRequest;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.service.UserCompanyRoleService;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.security.RoleChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitation utilisateur + gestion des rôles par société (§3.4, §13 Phase 1).
 *
 * <p>§3.8 : les paths utilisent {@code /api/v1/companies/{companyId}/users/{...}}.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/users")
@Tag(name = "Company.Users", description = "Invitation, role assignment per company")
public class UserCompanyRoleController {

 private final UserCompanyRoleService userCompanyRoleService;
 private final RoleChecker roleChecker;

 public UserCompanyRoleController(UserCompanyRoleService userCompanyRoleService, RoleChecker roleChecker) {
 this.userCompanyRoleService = userCompanyRoleService;
 this.roleChecker = roleChecker;
 }

 @Operation(summary = "Invite a user to this company (V2.6.0 — wizard refonte)",
 description = "Sends an invitation email via NotificationChannelPort. The invited user has no access " +
 "until they accept. OWNER role cannot be assigned via invitation. " +
 "V2.6.0 : if the email does not match an existing account, a new user is created on-the-fly " +
 "with a random temp password (the invitee will use the forgot-password flow to set their own). " +
 "In that case, `fullName` is required. Requires ADMIN role (OWNER > ADMIN, so ADMIN is the minimum).")
 @ApiResponses({
 @ApiResponse(responseCode = "201", description = "User invited (and created if not existing)",
 content = @Content(schema = @Schema(implementation = CompanyUserResponse.class))),
 @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OWNER rights / not in company, OR role=OWNER",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "Company not found",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "User already in company",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "422", description = "fullName required for new user / invalid input",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<CompanyUserResponse> invite(@CurrentUser UUID callerId,
 @PathVariable UUID companyId,
 @Valid @RequestBody InviteUserRequest req) {
 // V2.6.0 — OWNER > ADMIN, so ADMIN is the minimum role required to invite.
 // (Previously required OWNER — relaxed to ADMIN per wizard refonte spec.)
 roleChecker.ensureRole(companyId, "ADMIN");
 UserCompanyRole saved = userCompanyRoleService.inviteUser(
 companyId, req.email(), req.fullName(), req.role());
 return ResponseEntity.status(HttpStatus.CREATED)
 .body(userCompanyRoleService.toCompanyUserResponse(saved));
 }

 @Operation(summary = "Update a user's role in this company")
 @PatchMapping("/{userId}/role")
 public Map<String, Object> updateRole(@CurrentUser UUID callerId,
 @PathVariable UUID companyId,
 @PathVariable UUID userId,
 @Valid @RequestBody UpdateUserRoleRequest req) {
 roleChecker.ensureRole(companyId, "OWNER");
 UserCompanyRole saved = userCompanyRoleService.updateRole(companyId, userId, req.role());
 return Map.of(
 "id", saved.getId(),
 "userId", saved.getUserId(),
 "companyId", saved.getCompanyId(),
 "role", saved.getRole()
 );
 }

 @Operation(summary = "Accept an invitation to join this company",
 description = "The invited user accepts their own invitation. Before acceptance, the user " +
 "can see the company in their list but cannot access it. " +
 "Audit v4.7 §6.2 : seul l'utilisateur invité peut accepter sa propre invitation.")
 @ApiResponses({
 @ApiResponse(responseCode = "200"),
 @ApiResponse(responseCode = "403", description = "Caller is not the invited user",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "404", description = "No pending invitation found",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "Invitation already accepted",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @PostMapping("/{userId}/accept")
 public Map<String, Object> acceptInvitation(@CurrentUser UUID callerId,
 @PathVariable UUID companyId,
 @PathVariable UUID userId) {
 // Audit v4.7 §6.2 BAC critique : callerId était capturé mais jamais comparé
 // à userId. Sans ce garde-fou, n'importe quel membre de la company pouvait forcer-accepter
 // les invitations des autres utilisateurs.
 if (!callerId.equals(userId)) {
 throw new ForbiddenException("INVITATION_NOT_YOURS",
 "Seul l'utilisateur invité peut accepter sa propre invitation. " +
 "callerId=" + callerId + " ≠ userId=" + userId + ".");
 }
 UserCompanyRole saved = userCompanyRoleService.acceptInvitation(userId, companyId);
 return Map.of(
 "id", saved.getId(),
 "userId", saved.getUserId(),
 "companyId", saved.getCompanyId(),
 "role", saved.getRole(),
 "acceptedAt", saved.getAcceptedAt()
 );
 }

 @Operation(summary = "List users of this company with their roles (V2.6.0 — VIEWER allowed)",
 description = "Returns the list of users (with email + fullName + role + invitedAt + acceptedAt) " +
 "for this company. V2.6.0 : access relaxed from ADMIN to VIEWER (a viewer can see " +
 "who has access, but cannot invite or change roles). Returns " +
 "CompanyUserResponse records.")
 @ApiResponses({
 @ApiResponse(responseCode = "200",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = CompanyUserResponse.class))),
 @ApiResponse(responseCode = "403", description = "Caller lacks VIEWER rights / not in company",
 content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
 })
 @GetMapping
 public List<CompanyUserResponse> listUsers(@CurrentUser UUID callerId,
 @PathVariable UUID companyId) {
 // V2.6.0 — VIEWER can list users (read-only); previously required ADMIN.
 roleChecker.ensureRole(companyId, "VIEWER");
 return userCompanyRoleService.listForCompanyAsResponse(companyId);
 }
}
