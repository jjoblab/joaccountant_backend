package jo.accountant.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.auth.service.MfaService;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.security.CurrentUser;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour la MFA TOTP— session 7).
 *
 * <p>Expose les endpoints de setup, vérification et désactivation de la MFA.
 * Le {@link MfaService} (infrastructure complète RFC 6238) était implémenté mais non exposé
 * en REST — ce contrôleur débloque l'intégration mobile (challenge 2-step au login).
 *
 * <h2>Flux d'activation MFA</h2>
 * <ol>
 * <li>{@code POST /api/v1/auth/mfa/setup} → retourne l'URL otpauth:// pour le QR code.</li>
 * <li>L'utilisateur scanne le QR code avec Google Authenticator / Authy / FreeOTP.</li>
 * <li>{@code POST /api/v1/auth/mfa/verify?code=123456} → valide le premier code TOTP.
 * Si valide, active la MFA + retourne les 10 codes de récupération (à afficher 1× seule).</li>
 * </ol>
 *
 * <h2>Flux de login 2-step (à implémenter côté AuthService)</h2>
 * <ol>
 * <li>{@code POST /auth/login} → si MFA activée, retourne {@code 200} avec {@code mfaRequired: true}
 * + un {@code mfaChallengeToken} (JWT court 5min) au lieu des tokens normaux.</li>
 * <li>Le client envoie {@code POST /auth/login/mfa?challenge=...&code=123456}.</li>
 * <li>Si code valide, retourne les tokens normaux (access + refresh).</li>
 * </ol>
 *
 * <p><b>Note</b> : le flux login 2-step nécessite une modification de {@code AuthService.login()}
 * et du DTO {@code LoginResponse} — prévu . Ce contrôleur expose déjà le CRUD MFA qui
 * permet au mobile d'implémenter l'écran de setup + l'écran codes de récupération.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@Tag(name = "MFA", description = "Authentification multi-facteurs TOTP (RFC 6238)")
/**
 * Contrôleur REST Mfa.
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
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 *   <li>{@code POST /}</li>
 * </ul>

 * @author jo@Dev


 */

public class MfaController {

 private final MfaService mfaService;
 private final UserCompanyRoleRepository userCompanyRoleRepository;
 private final UserRepository userRepository;

 public MfaController(MfaService mfaService,
 UserCompanyRoleRepository userCompanyRoleRepository,
 UserRepository userRepository) {
 this.mfaService = mfaService;
 this.userCompanyRoleRepository = userCompanyRoleRepository;
 this.userRepository = userRepository;
 }

 @Operation(summary = "Initier le setup MFA",
 description = "Génère un secret TOTP aléatoire, le chiffre AES-256-GCM, le persiste (enabledAt=null). "
 + "Retourne l'URL otpauth:// pour générer le QR code côté client (Google Authenticator, Authy, FreeOTP). "
 + "<p><b>Important</b> : tant que l'utilisateur n'a pas validé un premier code via "
 + "<code>POST /auth/mfa/verify</code>, la MFA n'est PAS active.",
 responses = {
 @ApiResponse(responseCode = "200",
 description = "Secret TOTP généré",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = MfaSetupResponse.class),
 examples = @ExampleObject(name = "Setup response", value = """
 {
 "secret": "JBSWY3DPEHPK3PXP",
 "otpauthUrl": "otpauth://totp/JOAccountant:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=JOAccountant&algorithm=SHA1&digits=6&period=30"
 }
 """))),
 @ApiResponse(responseCode = "401", description = "JWT manquant ou invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "MFA déjà activée — désactiver d'abord via POST /auth/mfa/disable",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/setup")
 public ResponseEntity<MfaSetupResponse> setup(@CurrentUser UUID userId) {
 // (audit qualité/arch) — On récupère le VRAI email de l'utilisateur depuis la DB
 // pour construire l'URL otpauth:// correcte (l'email figure dans le label du compte TOTP
 // et permet à l'utilisateur de distinguer plusieurs comptes dans son app Authenticator).
 // Avant : email fictif "user-{uuid}" → l'email de notification MFA n'arrivait jamais au
 // bon destinataire et le label du QR code était illisible.
 User user = userRepository.findById(userId)
 .orElseThrow(() -> new NotFoundException(
 "USER_NOT_FOUND",
 "Utilisateur introuvable pour userId=" + userId));
 String email = user.getEmail();
 MfaService.MfaSetupResult result = mfaService.initiateSetup(userId, email);
 return ResponseEntity.ok(new MfaSetupResponse(result.secret(), result.otpauthUrl()));
 }

 @Operation(summary = "Confirmer le setup MFA avec un premier code TOTP",
 description = "Valide le code TOTP fourni par l'utilisateur. Si valide, active la MFA (enabledAt=now) "
 + "et génère 10 codes de récupération à usage unique. Ces codes doivent être affichés "
 + "UNE SEULE FOIS à l'utilisateur — ils ne seront plus accessibles ensuite.",
 parameters = @Parameter(name = "code", description = "Code TOTP à 6 chiffres saisi par l'utilisateur (issu de l'app Authenticator)", example = "123456"),
 responses = {
 @ApiResponse(responseCode = "200",
 description = "MFA activée + 10 codes de récupération",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = MfaVerifyResponse.class),
 examples = @ExampleObject(name = "Verify response", value = """
 {
 "enabled": true,
 "recoveryCodes": [
 "a1b2c3d4e5",
 "f6g7h8i9j0",
 "k1l2m3n4o5",
 "p6q7r8s9t0",
 "u1v2w3x4y5",
 "z6a7b8c9d0",
 "e1f2g3h4i5",
 "j6k7l8m9n0",
 "o1p2q3r4s5",
 "t6u7v8w9x0"
 ]
 }
 """))),
 @ApiResponse(responseCode = "401", description = "JWT manquant ou invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "403", description = "Code TOTP invalide — réessayer",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
 @ApiResponse(responseCode = "409", description = "MFA déjà activée",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/verify")
 public ResponseEntity<MfaVerifyResponse> verify(@CurrentUser UUID userId,
 @RequestParam int code) {
 List<String> recoveryCodes = mfaService.confirmSetup(userId, code);
 return ResponseEntity.ok(new MfaVerifyResponse(true, recoveryCodes));
 }

 @Operation(summary = "Vérifier un code TOTP (pour login 2-step ou opération sensible)",
 description = "Vérifie un code TOTP avec fenêtre de tolérance ±30s. Retourne true si valide. "
 + "Utilisé par le flux login 2-step (à implémenter côté AuthService).",
 parameters = @Parameter(name = "code", description = "Code TOTP à 6 chiffres", example = "123456"),
 responses = {
 @ApiResponse(responseCode = "200",
 description = "Résultat de la vérification",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = MfaCheckResponse.class),
 examples = @ExampleObject(name = "Valid code", value = "{ \"valid\": true }"))),
 @ApiResponse(responseCode = "401", description = "JWT manquant ou invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @PostMapping("/check")
 public ResponseEntity<MfaCheckResponse> check(@CurrentUser UUID userId,
 @RequestParam int code) {
 boolean valid = mfaService.verifyCode(userId, code);
 return ResponseEntity.ok(new MfaCheckResponse(valid));
 }

 @Operation(summary = "Consommer un code de récupération",
 description = "Utilise un code de récupération à usage unique (si l'utilisateur a perdu son téléphone). "
 + "Le code est marqué comme utilisé et ne peut plus être réutilisé.")
 @PostMapping("/recovery-code")
 public ResponseEntity<MfaCheckResponse> useRecoveryCode(@CurrentUser UUID userId,
 @RequestParam String code) {
 boolean valid = mfaService.consumeRecoveryCode(userId, code);
 return ResponseEntity.ok(new MfaCheckResponse(valid));
 }

 @Operation(summary = "Désactiver la MFA",
 description = "Révoque le secret TOTP + tous les codes de récupération. L'utilisateur devra refaire "
 + "un setup pour réactiver la MFA. Recommandé : exiger une vérification de mot de passe "
 + "ou un code TOTP valide avant de permettre la désactivation.")
 @PostMapping("/disable")
 public ResponseEntity<Void> disable(@CurrentUser UUID userId) {
 mfaService.disable(userId);
 return ResponseEntity.noContent().build();
 }

 @Operation(summary = "Vérifier si la MFA est activée pour l'utilisateur courant",
 description = "Retourne deux flags : <ul>"
 + "<li><code>mfaEnabled</code> — true si l'utilisateur a activé la MFA (validé un 1er code TOTP).</li>"
 + "<li><code>mfaRequired</code> — true si l'utilisateur détient un rôle OWNER ou ADMIN "
 + "dans au moins une société (MFA obligatoire — NIST 800-63B AAL2).</li></ul>"
 + "<p>Le mobile utilise ces flags pour piloter l'UI : "
 + "si <code>mfaEnabled=false</code> ET <code>mfaRequired=true</code>, afficher une bannière "
 + "d'avertissement + bouton \"Configurer la MFA\".",
 responses = {
 @ApiResponse(responseCode = "200",
 description = "Statut MFA de l'utilisateur courant",
 content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
 schema = @Schema(implementation = MfaStatusResponse.class),
 examples = @ExampleObject(name = "Owner sans MFA", value = """
 {
 "mfaEnabled": false,
 "mfaRequired": true
 }
 """))),
 @ApiResponse(responseCode = "401", description = "JWT manquant ou invalide",
 content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
 })
 @GetMapping("/status")
 public ResponseEntity<MfaStatusResponse> status(@CurrentUser UUID userId) {
 boolean enabled = mfaService.isMfaEnabled(userId);
 boolean required = isMfaRequiredForUser(userId);
 return ResponseEntity.ok(new MfaStatusResponse(enabled, required));
 }

 /**
 * Détermine si la MFA est obligatoire pour l'utilisateur (OWNER/ADMIN).
 * Audit mobile #28 / MFA OWNER/ADMIN obligatoire.
 *
 * <p>Un utilisateur qui détient un rôle {@link UserRole#OWNER} ou {@link UserRole#ADMIN} dans
 * au moins une société DOIT activer la MFA — ces rôles permettent d'effectuer des opérations
 * sensibles (validation d'écritures comptables, clôture d'exercice, gestion des utilisateurs,
 * changement de plan comptable). La compromission de leur compte entraînerait des
 * conséquences financières et réglementaires graves.
 *
 * <p>Implémentation : on interroge {@link UserCompanyRoleRepository#findByUserId(UUID)} et on
 * vérifie si au moins une ligne a un rôle OWNER ou ADMIN. Seules les lignes avec
 * {@code acceptedAt != null} sont prises en compte (l'utilisateur doit avoir accepté
 * l'invitation pour que le rôle soit effectif — cf. {@link UserCompanyRole#getAcceptedAt()}).
 *
 * <p>Le mobile utilise ce flag pour :
 * <ul>
 * <li>Afficher un warning « MFA requise pour les rôles OWNER/ADMIN » si
 * {@code mfaEnabled = false} ET {@code mfaRequired = true}.</li>
 * <li>Bloquer les opérations sensibles (clôture, gestion users) tant que la MFA
 * n'est pas activée — Non implémenté : , nécessite un intercepteur AOP.</li>
 * </ul>
 */
 private boolean isMfaRequiredForUser(UUID userId) {
 List<UserCompanyRole> roles = userCompanyRoleRepository.findByUserId(userId);
 if (roles == null || roles.isEmpty()) {
 return false;
 }
 // Un rôle n'est effectif qu'une fois l'invitation acceptée (acceptedAt != null).
 // On ignore donc les invitations en attente — un utilisateur invité comme ADMIN qui n'a
 // pas encore accepté n'est pas ADMIN effectif tant qu'il n'a pas cliqué sur le lien.
 return roles.stream()
 .filter(r -> r.getAcceptedAt() != null)
 .map(UserCompanyRole::getRole)
 .anyMatch(role -> role == UserRole.OWNER || role == UserRole.ADMIN);
 }

 // --- DTOs ---

 public record MfaSetupResponse(String secret, String otpauthUrl) {}
 public record MfaVerifyResponse(boolean enabled, List<String> recoveryCodes) {}
 public record MfaCheckResponse(boolean valid) {}
 public record MfaStatusResponse(boolean mfaEnabled, boolean mfaRequired) {
 public MfaStatusResponse(boolean mfaEnabled) {
 this(mfaEnabled, false);
 }
 }
}
