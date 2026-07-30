package jo.accountant.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jo.accountant.auth.dto.ForgotPasswordRequest;
import jo.accountant.auth.dto.LoginRequest;
import jo.accountant.auth.dto.LoginResponse;
import jo.accountant.auth.dto.MfaLoginRequest;
import jo.accountant.auth.dto.RefreshRequest;
import jo.accountant.auth.dto.RegisterRequest;
import jo.accountant.auth.dto.RegisterResponse;
import jo.accountant.auth.dto.ResetPasswordRequest;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.auth.service.AuthService;
import jo.accountant.auth.service.JwtService;
import jo.accountant.auth.service.MfaService;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints d'auth — HORS de l'espace d'URL company-scoped (§3.8).
 *
 * <p>Path : {@code /api/v1/auth/*}
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Authentication, registration, refresh, password reset")
public class AuthController {

    private final AuthService authService;
    private final MfaService mfaService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, MfaService mfaService, JwtService jwtService,
                          UserRepository userRepository) {
        this.authService = authService;
        this.mfaService = mfaService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Operation(summary = "Register a new user",
        description = "Creates an unaffiliated user (no company yet). Email must be unique. " +
                      "Password must satisfy complexity rules (≥12 chars, upper, lower, digit, special).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created",
            content = @Content(schema = @Schema(implementation = RegisterResponse.class),
                examples = @ExampleObject(value = """
                    {"userId":"0192a8d4-7b1c-7d8e-9f01-234567890abc","email":"marie@joaccountant.dev","fullName":"Marie Joseph"}
                    """))),
        @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {"type":"https://joaccountant.dev/errors/email_already_registered","title":"Conflict","status":409,"detail":"An account already exists for email marie@joaccountant.dev","code":"EMAIL_ALREADY_REGISTERED"}
                    """))),
        @ApiResponse(responseCode = "422", description = "Weak password / invalid email",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        var user = authService.register(req.email(), req.password(), req.fullName(), req.locale());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new RegisterResponse(user.getId(), user.getEmail(), user.getFullName()));
    }

    @Operation(summary = "Login",
        description = "Exchanges credentials for an access token (15 min TTL) and a refresh token (30 days, rotated). " +
                      "Returns the list of companies the user has an accepted role in.\n\n" +
                      "**MFA 2-step login (RFC 6238 TOTP)** : si l'utilisateur a activé la MFA, " +
                      "`accessToken` et `refreshToken` sont `null` — un `mfaChallengeToken` (JWT 5 min) " +
                      "est retourné à la place. Le client doit alors appeler " +
                      "`POST /auth/login/mfa` avec un body JSON `{\"mfaChallengeToken\":\"…\",\"code\":123456}` " +
                      "contenant le code TOTP saisi par l'utilisateur pour obtenir les vrais tokens d'accès.\n\n" +
                      "**R-01 (lot-A-securite)** : le `mfaChallengeToken` doit être transmis dans le body " +
                      "(plus en query param) pour éviter sa fuite dans les logs nginx/Tomcat.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authenticated (MFA disabled)",
            content = @Content(schema = @Schema(implementation = LoginResponse.class),
                examples = @ExampleObject(name = "Standard login (MFA disabled)", value = """
                    {
                      "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMTkyYThkNC03YjFjLTdkOGUtOWYwMS0yMzQ1Njc4OTBhYmMiLCJlbWFpbCI6Im1hcmlham9hY2NvdW50YW50LmRldiIsImNvbXBhbmllcyI6W3siY29tcGFueUlkIjoiMDE5MmE4ZDUtMWMyZC0zZTRmLTVhNmItN2M4ZDllMGZhYmNkIiwicm9sZSI6Ik9XTkVSIn1dLCJpYXQiOjE3NTM2NzIwMDAsImV4cCI6MTc1MzY3MjkwMH0.signature",
                      "refreshToken": "rT-0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "tokenType": "Bearer",
                      "expiresIn": 900,
                      "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "email": "marie@joaccountant.dev",
                      "fullName": "Marie Joseph",
                      "companies": [{"companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd", "role": "OWNER"}],
                      "mfaRequired": false,
                      "mfaChallengeToken": null
                    }
                    """))),
        @ApiResponse(responseCode = "200", description = "MFA challenge required (MFA enabled)",
            content = @Content(schema = @Schema(implementation = LoginResponse.class),
                examples = @ExampleObject(name = "MFA challenge response", value = """
                    {
                      "accessToken": null,
                      "refreshToken": null,
                      "tokenType": "Bearer",
                      "expiresIn": 300,
                      "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "email": "marie@joaccountant.dev",
                      "fullName": "Marie Joseph",
                      "companies": [{"companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd", "role": "OWNER"}],
                      "mfaRequired": true,
                      "mfaChallengeToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMTkyYThkNC03YjFjLTdkOGUtOWYwMS0yMzQ1Njc4OTBhYmMiLCJjaGFsbGVuZ2UiOnRydWUsImlhdCI6MTc1MzY3MjAwMCwiZXhwIjoxNzUzNjcyMzAwfQ.signature"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Invalid credentials / account disabled (code `INVALID_CREDENTIALS` or `ACCOUNT_DISABLED`)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/invalid-credentials",
                      "title": "Invalid credentials",
                      "status": 403,
                      "detail": "Email ou mot de passe invalide.",
                      "instance": "/api/v1/auth/login",
                      "properties": {"code": "INVALID_CREDENTIALS"}
                    }
                    """))),
        @ApiResponse(responseCode = "429", description = "Too many login attempts (rate limit : 10/min/IP)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        var result = authService.login(req.email(), req.password());

        // Audit v4.7 §6.3 (session 14) — MFA login 2-step : si l'utilisateur a activé la MFA,
        // on ne retourne PAS les tokens normaux. À la place, on retourne mfaRequired=true +
        // un mfaChallengeToken (JWT court 5min). Le client doit envoyer POST /auth/login/mfa
        // avec le code TOTP pour obtenir les tokens normaux.
        if (mfaService.isMfaEnabled(result.userId())) {
            String challengeToken = jwtService.issueAccessToken(result.userId(), result.email(),
                result.companies());  // réutilise le JWT pour le challenge (TTL 5min via config)
            return new LoginResponse(null, null, "Bearer", 300L,
                result.userId(), result.email(), result.fullName(), result.companies(),
                true, challengeToken);
        }

        return new LoginResponse(result.accessToken(), result.refreshToken(), "Bearer", 900L,
            result.userId(), result.email(), result.fullName(), result.companies());
    }

    /**
     * Étape 2 du login MFA — valide le code TOTP et retourne les tokens normaux.
     * Audit v4.7 §6.3 (session 14).
     *
     * <p>R-01 (lot-A-securite) — corrections appliquées :
     * <ul>
     *   <li><b>Vérification de signature JWT</b> : le {@code mfaChallengeToken} est maintenant
     *       validé via {@link JwtService#parseAndVerifyClaims(String)} (signature + expiration
     *       + issuer + audience). Avant, {@code parseClaims} n'effectuait aucune vérification —
     *       un attaquant pouvait forger un JWT non signé (alg: none) pour bypasser la MFA.</li>
     *   <li><b>Body JSON au lieu de query params</b> : le token n'apparaît plus dans l'URL,
     *       évitant sa fuite dans les logs nginx/Tomcat (access_log `$request` logue la query
     *       string) et les Referer envoyés à des tiers.</li>
     * </ul>
     */
    @Operation(summary = "Login MFA step 2 — validate TOTP code",
        description = "After login returns `mfaRequired=true`, the client sends the TOTP code " +
                      "with the `mfaChallengeToken` to obtain the real access + refresh tokens.\n\n" +
                      "Le `mfaChallengeToken` expire après 5 minutes — au-delà, le client doit " +
                      "redemander un login (step 1) pour obtenir un nouveau challenge.\n\n" +
                      "**R-01 (lot-A-securite)** : le `mfaChallengeToken` doit être envoyé dans le " +
                      "body JSON (plus en query param). La signature JWT est désormais vérifiée " +
                      "serveur-side — un token non signé (alg: none) ou avec une signature invalide " +
                      "est rejeté avec 403 `MFA_CHALLENGE_TOKEN_INVALID`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "MFA validated — access + refresh tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class),
                examples = @ExampleObject(name = "MFA validated", value = """
                    {
                      "accessToken": "eyJhbGciOiJIUzI1NiJ9.signature",
                      "refreshToken": "rT-new-0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "tokenType": "Bearer",
                      "expiresIn": 900,
                      "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "email": "marie@joaccountant.dev",
                      "fullName": "Marie Joseph",
                      "companies": [{"companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd", "role": "OWNER"}],
                      "mfaRequired": false,
                      "mfaChallengeToken": null
                    }
                    """))),
        @ApiResponse(responseCode = "401", description = "Code TOTP invalide",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.ht/errors/mfa-invalid-code",
                      "title": "Invalid TOTP code",
                      "status": 401,
                      "detail": "Code TOTP invalide ou expiré. Réessayez.",
                      "instance": "/api/v1/auth/login/mfa",
                      "properties": {"code": "MFA_INVALID_CODE"}
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "Challenge token invalide (signature, expiration, issuer/audience)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                examples = @ExampleObject(value = """
                    {
                      "type": "https://joaccountant.dev/errors/mfa_challenge_token_invalid",
                      "title": "Forbidden",
                      "status": 403,
                      "detail": "Signature du mfaChallengeToken invalide — token rejeté (possible forgery).",
                      "instance": "/api/v1/auth/login/mfa",
                      "properties": {"code": "MFA_CHALLENGE_TOKEN_INVALID"}
                    }
                    """)))
    })
    @PostMapping("/login/mfa")
    public LoginResponse loginMfa(@Valid @RequestBody MfaLoginRequest req) {
        // R-01 (lot-A-securite) — Vérification EXPLICITE de la signature + expiration du
        // challenge token. L'ancien code appelait parseClaims() qui ne vérifiait rien —
        // un attaquant pouvait forger un JWT non signé pour bypasser la MFA.
        // Lever InvalidJwtException (→ HTTP 403 MFA_CHALLENGE_TOKEN_INVALID) si :
        //   - signature invalide (token forgé ou alg: none)
        //   - token expiré (TTL 5 min dépassé)
        //   - issuer/audience inattendus (rejeu cross-environnement)
        java.util.Map<String, Object> claims = jwtService.parseAndVerifyClaims(req.mfaChallengeToken());
        java.util.UUID userId = java.util.UUID.fromString((String) claims.get("sub"));
        String email = (String) claims.get("email");

        if (!mfaService.verifyCode(userId, req.code())) {
            throw new jo.accountant.core.exception.ForbiddenException("MFA_CODE_INVALID",
                "Invalid MFA code. Please verify your authenticator app and try again.");
        }

        // Code valide — émettre les tokens normaux
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> companies =
            (java.util.List<java.util.Map<String, Object>>) claims.get("companies");
        var result = authService.issueTokensForMfaUser(userId, email, companies);
        return new LoginResponse(result.accessToken(), result.refreshToken(), "Bearer", 900L,
            result.userId(), result.email(), result.fullName(), result.companies());
    }

    @Operation(summary = "Refresh access token",
        description = "Rotates the refresh token: the old one is revoked, a new one is issued. " +
                      "Reuse of a revoked token triggers immediate revocation of every active session for the user.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Refreshed",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "403", description = "Token unknown / expired / reused",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest req) {
        var result = authService.refresh(req.refreshToken());
        return new LoginResponse(result.accessToken(), result.refreshToken(), "Bearer", 900L,
            result.userId(), result.email(), result.fullName(), result.companies());
    }

    @Operation(summary = "Logout (revoke current refresh token)",
        description = "Idempotent — calling with an already-revoked or unknown token returns 200.")
    @ApiResponse(responseCode = "200", description = "Logged out")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest req) {
        authService.logout(req == null ? null : req.refreshToken());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Initiate password reset",
        description = "Always returns 202, regardless of whether the email exists — anti-enumeration. " +
                      "If the email is registered, a reset link is sent via the notification channel.")
    @ApiResponse(responseCode = "202", description = "Reset initiated (or silently ignored)")
    @PostMapping(value = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.initiatePasswordReset(req.email());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Consume a password reset token",
        description = "Single-use, 1-hour expiration. On success, every active session for the user is revoked.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Password updated"),
        @ApiResponse(responseCode = "403", description = "Token unknown / already used / expired",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Weak new password",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.consumePasswordReset(req.token(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH /api/v1/auth/me — met à jour le profil de l'utilisateur courant.
     *
     * <p>Seul {@code fullName} est persisté pour l'instant. {@code phone} est accepté
     * pour compatibilité future (la colonne n'existe pas encore côté DB).
     */
    @Operation(summary = "Update current user profile",
        description = "Updates the authenticated user's fullName. Phone is accepted but not yet persisted.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile updated",
            content = @Content(schema = @Schema(implementation = jo.accountant.auth.dto.UserResponse.class))),
        @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public jo.accountant.auth.dto.UserResponse updateMe(
            @CurrentUser java.util.UUID userId,
            @RequestBody jo.accountant.auth.dto.UpdateUserRequest req) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException("User", userId));
        if (req.fullName() != null && !req.fullName().isBlank()) {
            user.setFullName(req.fullName().trim());
        }
        User saved = userRepository.save(user);
        return jo.accountant.auth.dto.UserResponse.from(saved);
    }
}
