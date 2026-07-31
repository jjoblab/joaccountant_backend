package jo.accountant.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jo.accountant.demo.DemoDataSeeder;
import jo.accountant.demo.dto.DemoCompanySummary;
import jo.accountant.demo.dto.DemoDashboard;
import jo.accountant.demo.service.DemoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V8.1 — Endpoints publics du module Démos.
 *
 * <p>Tous les endpoints {@code GET /api/v1/demos/**} sont <strong>publics</strong> (pas d'auth) —
 * accessibles sans login pour prospection commerciale et onboarding utilisateur.
 *
 * <p>Les données retournées sont filtrées sur {@code companies.is_demo = TRUE} : aucune fuite
 * possible vers les entreprises réelles.
 */
@RestController
@RequestMapping("/api/v1/demos")
@Tag(
    name = "Demos",
    description =
        "V8.1 — Module Démos : 4 entreprises fictives haïtiennes (Boutik Lakay retail, "
            + "Moïse & Associés services, Espwa pou Ayiti ONG, Caribbean Textiles zone franche) "
            + "sur 2 exercices fiscaux (FY2024-2025 + FY2025-2026). Endpoints publics.")
public class DemoController {

  private final DemoService demoService;
  // @Profile("demo") — optionnel car DemoDataSeeder n'est instancié qu'en profil demo.
  @Autowired(required = false)
  private DemoDataSeeder demoDataSeeder;

  public DemoController(DemoService demoService) {
    this.demoService = demoService;
  }

  @Operation(
      summary = "Lister les entreprises démos",
      description =
          "Retourne les 4 entreprises fictives haïtiennes avec leur profil "
              + "(segment, localisation, employés, CA, devise, modules actifs).")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = DemoCompanySummary.class)))
  @GetMapping
  public ResponseEntity<List<DemoCompanySummary>> listDemos() {
    return ResponseEntity.ok(demoService.listDemos());
  }

  @Operation(
      summary = "Détail d'une entreprise démo",
      description =
          "Retourne le profil complet d'une entreprise démo par son code "
              + "(BOUTIK_LAKAY, MOISE_ASSOCIES, ESPWA_POU_AYITI, CARIBBEAN_TEXTILES).")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Code démo inconnu")
  @GetMapping("/{demoCode}")
  public ResponseEntity<DemoCompanySummary> getDemo(@PathVariable String demoCode) {
    return demoService
        .getDemo(demoCode)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "Dashboard d'une entreprise démo",
      description =
          "KPIs (CA, charges, résultat net, IS, cash position) + alertes DGI + "
              + "transactions récentes. Filtre par exercice fiscal (FY2024-2025 ou FY2025-2026).")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Code démo inconnu")
  @GetMapping("/{demoCode}/dashboard")
  public ResponseEntity<DemoDashboard> getDashboard(
      @PathVariable String demoCode,
      @RequestParam(required = false, defaultValue = "FY2025-2026") String fy) {
    return demoService
        .getDashboard(demoCode, fy)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  // ────────────────────────────────────────────────────────────────────
  // v2.5.2 — Endpoints de diagnostic + re-seed manuel
  // ────────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Statut du seed démo",
      description =
          "v2.5.2 — Vérifie si le seed automatique a tourné au startup. Retourne le "
              + "nombre d'entreprises démo présentes en DB vs le nombre attendu. Utile "
              + "pour diagnostiquer un 404 sur POST /demos/login/{demoCode} (si 0 démo en "
              + "DB, le seeder n'a pas tourné — appeler POST /demos/seed pour le déclencher).")
  @GetMapping("/seed/status")
  public ResponseEntity<java.util.Map<String, Object>> seedStatus() {
    // v2.5.2 — wrap dans try/catch pour exposer l'erreur au lieu d'un 500 générique
    // (le GlobalExceptionHandler masque le détail). RLS peut bloquer findAll() si pas
    // de tenant context, ou la DB peut être indisponible.
    long actual;
    String error = null;
    try {
      actual = demoService.countDemoCompanies();
    } catch (Exception e) {
      actual = -1;
      error = e.getClass().getSimpleName() + " : " + e.getMessage();
    }
    int expected = demoService.expectedDemoCount();
    boolean seeded = actual >= 0 && actual >= expected;
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    body.put("seeded", seeded);
    body.put("actualCount", actual);
    body.put("expectedCount", expected);
    body.put("demoProfileActive", demoDataSeeder != null);
    body.put("message", error != null
        ? "Erreur lors du comptage des démos : " + error
        : seeded
            ? "Seed OK — " + actual + " entreprise(s) démo en DB."
            : "Seed INCOMPLET — " + actual + "/" + expected
                + " entreprise(s). Appeler POST /api/v1/demos/seed pour re-seed.");
    if (error != null) body.put("error", error);
    return ResponseEntity.ok(body);
  }

  @Operation(
      summary = "Déclencher le seed démo manuellement",
      description =
          "v2.5.2 — Déclenche le seed des 4 entreprises démo de façon synchrone. "
              + "Idempotent (les seeders vérifient l'existence par nom + isDemo=true). "
              + "Utile si le seed automatique au startup a échoué ou n'a pas tourné "
              + "(profil demo non actif, erreur DB, etc.).")
  @PostMapping("/seed")
  public ResponseEntity<java.util.Map<String, Object>> seedManually() {
    if (demoDataSeeder == null) {
      return ResponseEntity.status(503).body(
          java.util.Map.of(
              "error", "DemoDataSeeder non disponible",
              "cause", "Le profil Spring 'demo' n'est pas actif. "
                  + "Ajouter 'demo' à SPRING_PROFILES_ACTIVE sur Render."));
    }
    int total = demoDataSeeder.seedAllManually();
    long actual = demoService.countDemoCompanies();
    int expected = demoService.expectedDemoCount();
    return ResponseEntity.ok(
        java.util.Map.of(
            "totalRecords", total,
            "actualCount", actual,
            "expectedCount", expected,
            "seeded", actual >= expected,
            "message", "Seed terminé — " + actual + "/" + expected
                + " entreprise(s) démo en DB."));
  }
}
