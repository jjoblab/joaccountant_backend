package jo.accountant.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.config.Argon2PasswordEncoder;
import jo.accountant.auth.entity.PasswordResetToken;
import jo.accountant.auth.entity.RefreshToken;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.repository.PasswordResetTokenRepository;
import jo.accountant.auth.repository.RefreshTokenRepository;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.auth.validator.PasswordValidator;
import jo.accountant.core.audit.SecurityAuditEvent;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cœur de l'authentification (§13.
 *
 * <p>Implémente chaque règle listée dans §13« Règles métier » :
 * <ul>
 * <li>email unique</li>
 * <li>complexité du mot de passe (déléguée à {@link PasswordValidator})</li>
 * <li>rotation du refresh token — ancien token révoqué à l'usage, réutilisation → 403</li>
 * <li>invitation + réinitialisation de mot de passe via {@link NotificationChannelPort}</li>
 * </ul>
 
 *
 * @author jo@Dev


*/
@Service
public class AuthService {

 private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
 //— TTL réduit de 30j à 14j. Pour un SaaS financier, 30j est trop long
 // (recommandation OWASP : 14j max). En cas de vol de token, la fenêtre d'exploitation est
 // réduite de moitié. Compensation : l'utilisateur doit se reconnecter plus souvent, mais
 // la rotation au refresh prolonge la session active sans re-saisie du mot de passe.
 private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
 private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

 private final UserRepository userRepository;
 private final UserCompanyRoleRepository userCompanyRoleRepository;
 private final RefreshTokenRepository refreshTokenRepository;
 private final PasswordResetTokenRepository passwordResetTokenRepository;
 private final Argon2PasswordEncoder passwordEncoder;
 private final PasswordValidator passwordValidator;
 private final JwtService jwtService;
 private final TokenHasher tokenHasher;
 private final NotificationChannelPort notificationChannel;
 private final ApplicationEventPublisher events;

 public AuthService(UserRepository userRepository,
 UserCompanyRoleRepository userCompanyRoleRepository,
 RefreshTokenRepository refreshTokenRepository,
 PasswordResetTokenRepository passwordResetTokenRepository,
 Argon2PasswordEncoder passwordEncoder,
 PasswordValidator passwordValidator,
 JwtService jwtService,
 TokenHasher tokenHasher,
 NotificationChannelPort notificationChannel,
 ApplicationEventPublisher events) {
 this.userRepository = userRepository;
 this.userCompanyRoleRepository = userCompanyRoleRepository;
 this.refreshTokenRepository = refreshTokenRepository;
 this.passwordResetTokenRepository = passwordResetTokenRepository;
 this.passwordEncoder = passwordEncoder;
 this.passwordValidator = passwordValidator;
 this.jwtService = jwtService;
 this.tokenHasher = tokenHasher;
 this.notificationChannel = notificationChannel;
 this.events = events;
 }

 @Transactional
 public User register(String email, String password, String fullName, String locale) {
 if (email == null || email.isBlank()) {
 throw new ValidationException("EMAIL_REQUIRED", "Email is required");
 }
 if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
 throw new ValidationException("EMAIL_INVALID", "Email format is invalid");
 }
 if (fullName == null || fullName.isBlank()) {
 throw new ValidationException("FULL_NAME_REQUIRED", "Full name is required");
 }
 passwordValidator.validate(password);

 if (userRepository.existsByEmailIgnoreCase(email)) {
 throw new ConflictException("EMAIL_ALREADY_REGISTERED",
 "An account already exists for email " + email);
 }

 User user = new User();
 user.setId(UUID.randomUUID());
 user.setEmail(email.trim().toLowerCase());
 user.setPasswordHash(passwordEncoder.encode(password));
 user.setFullName(fullName.trim());
 user.setLocale(locale == null || locale.isBlank() ? "fr" : locale);
 user.setActive(true);
 user.setCreatedAt(Instant.now());
 user.setUpdatedAt(Instant.now());
 User saved = userRepository.save(user);

 events.publishEvent(new jo.accountant.auth.event.UserRegisteredEvent(saved));
 return saved;
 }

 @Transactional
 public LoginResult login(String email, String password) {
 String safeEmail = email == null ? "" : email.trim().toLowerCase();
 String correlationId = TenantContext.getCorrelationId();

 User user = userRepository.findByEmailIgnoreCase(safeEmail)
 .orElse(null);

 //audit sécurité LOGIN_FAILED pour email inconnu.
 // Pas d'userId → on ne peut pas deviner qui c'était, mais on trace la tentative (anti brute-force).
 if (user == null) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_FAILED, null, null,
 Map.of("reason", "UNKNOWN_EMAIL", "email", safeEmail),
 correlationId));
 throw new ForbiddenException("INVALID_CREDENTIALS", "Invalid email or password");
 }

 if (!user.isActive()) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_FAILED, user.getId(), null,
 Map.of("reason", "ACCOUNT_DISABLED", "email", safeEmail),
 correlationId));
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.ACCOUNT_DISABLED, user.getId(), null,
 Map.of("email", safeEmail), correlationId));
 throw new ForbiddenException("ACCOUNT_DISABLED", "Account is disabled");
 }

 if (!passwordEncoder.matches(password, user.getPasswordHash())) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_FAILED, user.getId(), null,
 Map.of("reason", "INVALID_PASSWORD", "email", safeEmail),
 correlationId));
 throw new ForbiddenException("INVALID_CREDENTIALS", "Invalid email or password");
 }

 List<Map<String, Object>> companiesClaim = buildCompaniesClaim(user.getId());
 String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), companiesClaim);
 String rawRefreshToken = issueRefreshToken(user.getId());

 TenantContext.setUserId(user.getId());

 //audit sécurité LOGIN_SUCCESS.
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_SUCCESS, user.getId(), null,
 Map.of("email", safeEmail), correlationId));

 return new LoginResult(accessToken, rawRefreshToken, user.getId(), user.getEmail(),
 user.getFullName(), companiesClaim);
 }

 /**
 * FIX v9.4.1 (audit T2.6) — Variante de {@link #login(String, String)} qui émet un JWT avec
 * le claim {@code demo=true} pour distinguer les sessions démo.
 *
 * <p>Les credentials sont vérifiés de la même manière que {@link #login(String, String)}, mais
 * le token d'accès émis contient un claim additionnel {@code demo: true} qui permet à
 * l'audit trail et aux filtres de sécurité de tracer/restreindre les actions démo.
 *
 * <p>Utilisé par {@link jo.accountant.demo.controller.DemoLoginController#demoLogin} pour
 * connecter les 4 entreprises démo (BOUTIK_LAKAY, MOISE_ASSOCIES, ESPWA_POU_AYITI,
 * CARIBBEAN_TEXTILES) sans saisir d'email/password.
 *
 * @param email    email du user OWNER démo (ex: owner@boutik-lakay.demo)
 * @param password mot de passe démo partagé (DemoCredentials.DEMO_PASSWORD)
 * @return LoginResult avec un accessToken contenant le claim demo=true
 * @throws jo.accountant.core.exception.ForbiddenException si les credentials sont invalides
 */
 @Transactional
 public LoginResult loginDemo(String email, String password) {
 String safeEmail = email == null ? "" : email.trim().toLowerCase();
 String correlationId = TenantContext.getCorrelationId();

 User user = userRepository.findByEmailIgnoreCase(safeEmail)
 .orElse(null);

 if (user == null) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_FAILED, null, null,
 Map.of("reason", "UNKNOWN_EMAIL", "email", safeEmail, "context", "DEMO"),
 correlationId));
 throw new ForbiddenException("INVALID_CREDENTIALS", "Invalid email or password");
 }

 if (!user.isActive()) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_FAILED, user.getId(), null,
 Map.of("reason", "ACCOUNT_DISABLED", "email", safeEmail, "context", "DEMO"),
 correlationId));
 throw new ForbiddenException("ACCOUNT_DISABLED", "Account is disabled");
 }

 if (!passwordEncoder.matches(password, user.getPasswordHash())) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_FAILED, user.getId(), null,
 Map.of("reason", "INVALID_PASSWORD", "email", safeEmail, "context", "DEMO"),
 correlationId));
 throw new ForbiddenException("INVALID_CREDENTIALS", "Invalid email or password");
 }

 List<Map<String, Object>> companiesClaim = buildCompaniesClaim(user.getId());
 // FIX v9.4.1 (audit T2.6) — émet un JWT avec le claim demo=true pour distinguer
 // les sessions démo des sessions réelles dans l'audit trail et permettre la
 // révocation en masse via DemoSessionFilter (à implémenter).
 String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), companiesClaim, true);
 String rawRefreshToken = issueRefreshToken(user.getId());

 TenantContext.setUserId(user.getId());

 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGIN_SUCCESS, user.getId(), null,
 Map.of("email", safeEmail, "context", "DEMO"), correlationId));

 return new LoginResult(accessToken, rawRefreshToken, user.getId(), user.getEmail(),
 user.getFullName(), companiesClaim);
 }

 @Transactional
 public LoginResult refresh(String rawRefreshToken) {
 String correlationId = TenantContext.getCorrelationId();

 if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
 throw new ValidationException("REFRESH_TOKEN_REQUIRED", "Refresh token is required");
 }
 String hash = tokenHasher.hash(rawRefreshToken);
 RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
 .orElseThrow(() -> new ForbiddenException("REFRESH_TOKEN_INVALID", "Refresh token is unknown"));

 if (token.getRevokedAt() != null) {
 // Vol de token possible — révoquer tous les tokens actifs de cet utilisateur
 LOG.warn("Refresh token reuse detected for user {} — revoking all sessions", token.getUserId());
 refreshTokenRepository.findByUserIdAndRevokedAtIsNull(token.getUserId()).stream()
 .filter(t -> token.getUserId().equals(t.getUserId()) && t.getRevokedAt() == null)
 .forEach(t -> {
 t.setRevokedAt(Instant.now());
 refreshTokenRepository.save(t);
 });
 //REFRESH_TOKEN_REUSED = signal critique de vol de token.
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.REFRESH_TOKEN_REUSED, token.getUserId(), null,
 Map.of("action", "ALL_SESSIONS_REVOKED"), correlationId));
 throw new ForbiddenException("REFRESH_TOKEN_REUSED",
 "Refresh token has been revoked — possible token theft. All sessions have been invalidated.");
 }

 if (token.getExpiresAt().isBefore(Instant.now())) {
 token.setRevokedAt(Instant.now());
 refreshTokenRepository.save(token);
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.REFRESH_TOKEN_EXPIRED, token.getUserId(), null,
 Map.of(), correlationId));
 throw new ForbiddenException("REFRESH_TOKEN_EXPIRED", "Refresh token has expired");
 }

 // Rotation : révoquer l'ancien token, en émettre un nouveau
 token.setRevokedAt(Instant.now());
 refreshTokenRepository.save(token);

 User user = userRepository.findById(token.getUserId())
 .orElseThrow(() -> new ForbiddenException("REFRESH_TOKEN_INVALID", "User no longer exists"));
 if (!user.isActive()) {
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.ACCOUNT_DISABLED, user.getId(), null,
 Map.of("context", "REFRESH"), correlationId));
 throw new ForbiddenException("ACCOUNT_DISABLED", "Account is disabled");
 }

 List<Map<String, Object>> companiesClaim = buildCompaniesClaim(user.getId());
 String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), companiesClaim);
 String newRawRefreshToken = issueRefreshToken(user.getId());

 TenantContext.setUserId(user.getId());

 //REFRESH_TOKEN_ROTATED = usage normal du refresh.
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.REFRESH_TOKEN_ROTATED, user.getId(), null,
 Map.of(), correlationId));

 return new LoginResult(accessToken, newRawRefreshToken, user.getId(), user.getEmail(),
 user.getFullName(), companiesClaim);
 }

 @Transactional
 public void logout(String rawRefreshToken) {
 if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
 return; // idempotent
 }
 String hash = tokenHasher.hash(rawRefreshToken);
 String correlationId = TenantContext.getCorrelationId();
 refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
 if (token.getRevokedAt() == null) {
 token.setRevokedAt(Instant.now());
 refreshTokenRepository.save(token);
 //LOGOUT.
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.LOGOUT, token.getUserId(), null,
 Map.of(), correlationId));
 }
 });
 }

 /**
 * §13« mot de passe oublié » — initie la réinitialisation.
 *
 * <p>Renvoie toujours un succès, ne révèle jamais si l'email existe (anti-énumération).
 * L'email de notification est envoyé de manière asynchrone via {@link NotificationChannelPort}.
 */
 @Transactional
 public void initiatePasswordReset(String email) {
 String safeEmail = email == null ? "" : email.trim().toLowerCase();
 String correlationId = TenantContext.getCorrelationId();
 userRepository.findByEmailIgnoreCase(safeEmail)
 .ifPresent(user -> {
 String rawToken = tokenHasher.generateRawToken();
 PasswordResetToken token = new PasswordResetToken();
 token.setId(UUID.randomUUID());
 token.setUserId(user.getId());
 token.setTokenHash(tokenHasher.hash(rawToken));
 token.setExpiresAt(Instant.now().plus(PASSWORD_RESET_TTL));
 token.setCreatedAt(Instant.now());
 passwordResetTokenRepository.save(token);

 Map<String, Object> vars = new HashMap<>();
 vars.put("fullName", user.getFullName());
 vars.put("resetToken", rawToken);
 vars.put("expiresInMinutes", PASSWORD_RESET_TTL.toMinutes());
 notificationChannel.sendEmail(user.getEmail(), "password-reset", vars);

 //PASSWORD_RESET_REQUESTED.
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.PASSWORD_RESET_REQUESTED, user.getId(), null,
 Map.of("email", safeEmail), correlationId));
 });
 // Note : si l'email n'existe pas, on NE publie PAS d'événement — anti-énumération.
 // L'API renvoie toujours 204 No Content, indépendamment de l'existence du compte.
 }

 /**
 * §3.4 : jeton à usage unique, expiration courte (1h).
 * Toute tentative ultérieure d'utiliser le même token → 403.
 */
 @Transactional
 public void consumePasswordReset(String rawToken, String newPassword) {
 if (rawToken == null || rawToken.isBlank()) {
 throw new ValidationException("RESET_TOKEN_REQUIRED", "Reset token is required");
 }
 passwordValidator.validate(newPassword);

 String hash = tokenHasher.hash(rawToken);
 PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hash)
 .orElseThrow(() -> new ForbiddenException("RESET_TOKEN_INVALID", "Reset token is unknown"));

 if (token.getUsedAt() != null) {
 throw new ForbiddenException("RESET_TOKEN_ALREADY_USED",
 "Reset token has already been used");
 }
 if (token.getExpiresAt().isBefore(Instant.now())) {
 throw new ForbiddenException("RESET_TOKEN_EXPIRED", "Reset token has expired");
 }

 User user = userRepository.findById(token.getUserId())
 .orElseThrow(() -> new ForbiddenException("RESET_TOKEN_INVALID", "User no longer exists"));

 user.setPasswordHash(passwordEncoder.encode(newPassword));
 user.setUpdatedAt(Instant.now());
 userRepository.save(user);

 token.setUsedAt(Instant.now());
 passwordResetTokenRepository.save(token);

 // Révoquer toutes les sessions existantes — reset password = logout forcé partout
 refreshTokenRepository.findByUserIdAndRevokedAtIsNull(token.getUserId()).stream()
 .filter(t -> user.getId().equals(t.getUserId()) && t.getRevokedAt() == null)
 .forEach(t -> {
 t.setRevokedAt(Instant.now());
 refreshTokenRepository.save(t);
 });

 //PASSWORD_RESET_CONSUMED. Événement critique de sécurité :
 // un changement de mot de passe peut indiquer une reprise de compte légitime OU une
 // compromission (si l'attaquant a intercepté le token). Tracer permet la forensique.
 events.publishEvent(SecurityAuditEvent.of(
 SecurityAuditEvent.Types.PASSWORD_RESET_CONSUMED, user.getId(), null,
 Map.of("email", user.getEmail(), "sessionsRevoked", "true"),
 TenantContext.getCorrelationId()));
 }

 private List<Map<String, Object>> buildCompaniesClaim(UUID userId) {
 List<UserCompanyRole> roles = userCompanyRoleRepository.findByUserId(userId).stream()
 .filter(r -> r.getAcceptedAt() != null)
 .toList();
 List<Map<String, Object>> claim = new ArrayList<>(roles.size());
 for (UserCompanyRole r : roles) {
 Map<String, Object> entry = new HashMap<>();
 entry.put("companyId", r.getCompanyId().toString());
 entry.put("role", r.getRole().name());
 claim.add(entry);
 }
 return claim;
 }

 private String issueRefreshToken(UUID userId) {
 String rawToken = tokenHasher.generateRawToken();
 RefreshToken token = new RefreshToken();
 token.setId(UUID.randomUUID());
 token.setUserId(userId);
 token.setTokenHash(tokenHasher.hash(rawToken));
 token.setExpiresAt(Instant.now().plus(REFRESH_TOKEN_TTL));
 token.setCreatedAt(Instant.now());
 refreshTokenRepository.save(token);
 return rawToken;
 }

 /**
 * Émet les tokens normaux pour un utilisateur qui a validé son code MFA.
 *(session 14) — MFA login 2-step.
 */
 public LoginResult issueTokensForMfaUser(UUID userId, String email,
 List<Map<String, Object>> companies) {
 User user = userRepository.findById(userId)
 .orElseThrow(() -> new ForbiddenException("USER_NOT_FOUND", "User no longer exists"));
 String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), companies);
 String rawRefreshToken = issueRefreshToken(user.getId());
 return new LoginResult(accessToken, rawRefreshToken, user.getId(), user.getEmail(),
 user.getFullName(), companies);
 }

 public record LoginResult(String accessToken, String refreshToken, UUID userId, String email,
 String fullName, List<Map<String, Object>> companies) {}
}
