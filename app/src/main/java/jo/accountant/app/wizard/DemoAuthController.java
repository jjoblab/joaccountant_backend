package jo.accountant.app.wizard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * V8.2 — Endpoint de login démo (sans mot de passe).
 *
 * <p>Permet à l'application mobile de se connecter en mode démo sans inscription.
 * L'utilisateur sélectionne une entreprise démo sur l'écran de login, le mobile appelle
 * cet endpoint avec le demoCode, et reçoit un vrai JWT token + companyId.
 *
 * <p>Les utilisateurs démo sont créés par le seeder (DemoDataSeeder) avec un email
 * prédictif : demo_boutik_lakay@joaccountant.ht, demo_moise@joaccountant.ht, etc.
 * Le mot de passe est "demo1234" (hashé Argon2id au seed).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "DemoAuth",
     description = "V8.2 — Authentification démo (sans inscription)")
public class DemoAuthController {

    private final DemoAuthService demoAuthService;

    public DemoAuthController(DemoAuthService demoAuthService) {
        this.demoAuthService = demoAuthService;
    }

    @Operation(summary = "V8.2 — Login démo (sans mot de passe)")
    @PostMapping(value = "/demo-login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> demoLogin(@RequestBody DemoLoginRequest req) {
        Map<String, Object> result = demoAuthService.demoLogin(req.demoCode());
        return ResponseEntity.ok(result);
    }

    public record DemoLoginRequest(
        @NotBlank String demoCode
    ) {}
}
