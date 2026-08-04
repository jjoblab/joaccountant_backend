package jo.accountant.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jo.accountant.auth.dto.LoginResponse;
import jo.accountant.auth.service.AuthService;
import jo.accountant.demo.service.DemoService;
import jo.accountant.demo.support.DemoCredentials;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/**
 * V9 — Endpoint de connexion rapide pour les entreprises démo.
 *
 * <p>Permet à un utilisateur de se connecter en un clic à une entreprise démo sans saisir
 * d'email/password. Le endpoint recherche le user OWNER de la démo, puis appelle {@link
 * AuthService#login(String, String)} avec le mot de passe démo connu.
 *
 * <p><strong>Sécurité</strong> :
 *
 * <ul>
 * <li>Ces credentials ne donnent accès qu'aux entreprises {@code is_demo=true}.
 * <li>Le mot de passe "demo1234" respecte les règles de complexité (≥12 chars) — non, en fait il
 * ne les respecte pas. Pour autoriser ce mot de passe faible, le seeder crée les users via
 * {@code AuthService.register(...)} qui exige une politique de mot de passe stricte. En
 * attendant, ce endpoint accepte le mot de passe "demo1234!" qui satisfait la politique (12
 * chars, majuscule, minuscule, chiffre, spécial).
 * <li>Toutes les opérations d'écriture sont auditées (audit-trail).
 * </ul>
 *
 * <p><strong>Important</strong> : ce endpoint est public (pas d'auth). Il ne doit JAMAIS être
 * déployé en production réelle — uniquement pour les environnements de démo publique (Render free
 * tier, démos commerciales).
 
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
 *   <li>{@code POST /}</li>
 * </ul>

 * @author jo@Dev


*/
@RestController
@RequestMapping("/api/v1/demos")
@Tag(
    name = "Demos",
    description =
        "V9 — Module Démos : 4 entreprises fictives haïtiennes. "
            + "POST /api/v1/demos/login/{demoCode} = connexion rapide en un clic.")
public class DemoLoginController {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DemoLoginController.class);

  /** Mot de passe démo partagé par les 4 users OWNER démo. */
  static final String DEMO_PASSWORD = DemoCredentials.DEMO_PASSWORD;

  private final DemoService demoService;
  private final AuthService authService;

  public DemoLoginController(DemoService demoService, AuthService authService) {
    this.demoService = demoService;
    this.authService = authService;
  }

  /**
   * Login rapide pour une entreprise démo.
   *
   * <p>Recherche le user OWNER de la démo {@code demoCode}, puis appelle {@link
   * AuthService#login(String, String)} avec le mot de passe démo partagé.
   *
   * <p>Retourne les mêmes champs que {@code POST /api/v1/auth/login} : accessToken, refreshToken,
   * companies, etc. Le client peut ensuite appeler les endpoints protégés avec le Bearer token
   * obtenu.
   */
  @Operation(
      summary = "Login démo en un clic",
      description =
          "Connecte l'utilisateur à une entreprise démo (BOUTIK_LAKAY, MOISE_ASSOCIES, "
              + "ESPWA_POU_AYITI, CARIBBEAN_TEXTILES) sans saisir d'email/password. "
              + "Retourne un vrai JWT utilisable sur tous les endpoints protégés. "
              + "⚠️ Public, à désactiver en production réelle.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Login réussi",
        content =
            @Content(
                schema = @Schema(implementation = LoginResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            """
                    {
                      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
                      "refreshToken": "rT-0192a8d4-...",
                      "tokenType": "Bearer",
                      "expiresIn": 900,
                      "userId": "0192a8d4-7b1c-7d8e-9f01-234567890abc",
                      "email": "owner@boutik-lakay.demo",
                      "fullName": "Boutik Lakay Owner",
                      "companies": [
                        {"companyId":"...","role":"OWNER","name":"Boutik Lakay S.A."}
                      ],
                      "mfaRequired": false,
                      "mfaChallengeToken": null
                    }
                    """))),
    @ApiResponse(
        responseCode = "404",
        description = "Code démo inconnu ou user démo absent",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Erreur interne (seeder pas encore terminé ?)",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @PostMapping(value = "/login/{demoCode}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> demoLogin(@PathVariable String demoCode,
                                      HttpServletRequest httpRequest) {
    // FIX v9.4.1 (audit T1.4) — Logging systématique des appels démo avec IP + User-Agent.
    // Ces logs permettent d'auditer qui utilise les endpoints démo publics et de détecter
    // un abus (DoS sur Render free tier, extraction massive de JWT, etc.).
    String clientIp = extractClientIp(httpRequest);
    String userAgent = httpRequest.getHeader("User-Agent");
    LOG.info("Demo login attempt — demoCode='{}' ip='{}' userAgent='{}'",
        demoCode, clientIp, userAgent);
    var company = demoService.findDemoCompany(demoCode);
    if (company.isEmpty()) {
      LOG.warn("Demo login failed (company not found) — demoCode='{}' ip='{}' userAgent='{}'",
          demoCode, clientIp, userAgent);
      // message explicite : la company démo n'existe pas en DB.
      // Le seeder n'a probablement pas tourné → appeler POST /api/v1/demos/seed.
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.NOT_FOUND,
              "Entreprise démo '" + demoCode + "' introuvable en DB. "
                  + "Le seed automatique a-t-il tourné au startup ? "
                  + "Appeler POST /api/v1/demos/seed pour déclencher le seed manuellement.");
      problem.setProperty("demoCode", demoCode);
      problem.setProperty("hint", "POST /api/v1/demos/seed");
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    // Email démo prédictible : owner@<domain>.demo
    String email = DemoCredentials.ownerEmail(demoCode);
    try {
      // FIX v9.4.1 (audit T2.6) — utilise loginDemo() au lieu de login() pour émettre
      // un JWT avec le claim demo=true. Permet de distinguer les sessions démo des
      // sessions réelles dans l'audit trail et de révoquer en masse si nécessaire.
      var loginResult = authService.loginDemo(email, DEMO_PASSWORD);
      // LoginResponse sans MFA (les users démo n'ont pas de MFA activée)
      LoginResponse response =
          new LoginResponse(
              loginResult.accessToken(),
              loginResult.refreshToken(),
              "Bearer",
              900L,
              loginResult.userId(),
              loginResult.email(),
              loginResult.fullName(),
              loginResult.companies(),
              false,
              null);
      LOG.info("Demo login success — demoCode='{}' userId='{}' ip='{}' userAgent='{}'",
          demoCode, loginResult.userId(), clientIp, userAgent);
      return ResponseEntity.ok(response);
    } catch (jo.accountant.core.exception.ForbiddenException e) {
      // 403 avec détail au lieu de 500 générique. Cause probable :
      // user démo non créé (seeder incomplet) ou password mismatch.
      LOG.warn("Demo login forbidden — demoCode='{}' email='{}' ip='{}' userAgent='{}' error='{}'",
          demoCode, email, clientIp, userAgent, e.getMessage());
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.FORBIDDEN,
              "Login démo échoué pour '" + demoCode + "' (email=" + email + ") : "
                  + e.getMessage() + " (code=" + e.getCode() + "). "
                  + "Causes probables : (1) le user démo n'existe pas en DB — appeler "
                  + "POST /api/v1/demos/seed ; (2) le password ne matche pas — vérifier "
                  + "que DemoCredentials.DEMO_PASSWORD ('" + DEMO_PASSWORD
                  + "') correspond au password utilisé par le seeder.");
      problem.setProperty("demoCode", demoCode);
      problem.setProperty("email", email);
      problem.setProperty("errorCode", e.getCode());
      problem.setProperty("hint", "POST /api/v1/demos/seed");
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    } catch (Exception e) {
      LOG.error("Demo login error — demoCode='{}' email='{}' ip='{}' userAgent='{}'",
          demoCode, email, clientIp, userAgent, e);
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "Login démo échoué pour '" + demoCode + "' (email=" + email + ") : "
                  + e.getClass().getSimpleName() + " : " + e.getMessage());
      problem.setProperty("demoCode", demoCode);
      problem.setProperty("email", email);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
  }

  /**
   * FIX v9.4.1 (audit T1.4) — Extrait l'IP réelle du client depuis la requête HTTP.
   *
   * <p>Tient compte des headers X-Forwarded-For et X-Real-IP posés par les proxies
   * (Render, Cloudflare, nginx). Si plusieurs IPs sont présentes dans X-Forwarded-For
   * (chaîne "client, proxy1, proxy2"), on prend la première (= le client original).
   *
   * @param request la requête HTTP entrante
   * @return l'IP du client, ou "unknown" si impossible à déterminer
   */
  private static String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      // X-Forwarded-For peut contenir plusieurs IPs séparées par virgule — on prend la 1ère.
      return xff.split(",")[0].trim();
    }
    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null && !xRealIp.isBlank()) {
      return xRealIp.trim();
    }
    return request.getRemoteAddr();
  }
}
