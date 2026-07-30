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
 *   <li>Ces credentials ne donnent accès qu'aux entreprises {@code is_demo=true}.
 *   <li>Le mot de passe "demo1234" respecte les règles de complexité (≥12 chars) — non, en fait il
 *       ne les respecte pas. Pour autoriser ce mot de passe faible, le seeder crée les users via
 *       {@code AuthService.register(...)} qui exige une politique de mot de passe stricte. En
 *       attendant, ce endpoint accepte le mot de passe "demo1234!" qui satisfait la politique (12
 *       chars, majuscule, minuscule, chiffre, spécial).
 *   <li>Toutes les opérations d'écriture sont auditées (audit-trail).
 * </ul>
 *
 * <p><strong>Important</strong> : ce endpoint est public (pas d'auth). Il ne doit JAMAIS être
 * déployé en production réelle — uniquement pour les environnements de démo publique (Render free
 * tier, démos commerciales).
 */
@RestController
@RequestMapping("/api/v1/demos")
@Tag(
    name = "Demos",
    description =
        "V9 — Module Démos : 4 entreprises fictives haïtiennes. "
            + "POST /api/v1/demos/login/{demoCode} = connexion rapide en un clic.")
public class DemoLoginController {

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
  public ResponseEntity<LoginResponse> demoLogin(@PathVariable String demoCode) {
    var company = demoService.findDemoCompany(demoCode);
    if (company.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    // Email démo prédictible : owner@<domain>.demo
    String email = DemoCredentials.ownerEmail(demoCode);
    try {
      var loginResult = authService.login(email, DEMO_PASSWORD);
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
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "Login démo échoué pour "
                  + demoCode
                  + " : "
                  + e.getMessage()
                  + " (le seeder a-t-il terminé ? Le user démo a-t-il été créé avec le mot de passe '"
                  + DEMO_PASSWORD
                  + "' ?)");
      return ResponseEntity.internalServerError().build();
    }
  }
}
