package jo.accountant.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jo.accountant.demo.dto.DemoCompanySummary;
import jo.accountant.demo.dto.DemoDashboard;
import jo.accountant.demo.service.DemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
