package jo.accountant.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jo.accountant.demo.dto.DemoCompanySummary;
import jo.accountant.demo.dto.DemoDashboard;
import jo.accountant.demo.service.DemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V8.1 — Endpoints publics du module Démos (12 endpoints).
 *
 * <p>GET endpoints publics (sans auth) pour exploration démos.
 * POST endpoints nécessitent auth ADMIN (seed/clone).
 */
@RestController
@RequestMapping("/api/v1/demos")
@Tag(name = "Demos",
     description = "V8.1 — Module Démos : 4 entreprises fictives haïtiennes sur 2 exercices fiscaux.")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    // === ENDPOINTS PUBLICS (sans auth) ===

    @Operation(summary = "1. Lister les entreprises démos")
    @GetMapping
    public ResponseEntity<List<DemoCompanySummary>> listDemos() {
        return ResponseEntity.ok(demoService.listDemos());
    }

    @Operation(summary = "2. Détail d'une entreprise démo")
    @GetMapping("/{demoCode}")
    public ResponseEntity<DemoCompanySummary> getDemo(@PathVariable String demoCode) {
        return demoService.getDemo(demoCode)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "3. Dashboard d'une entreprise démo (KPIs + alertes + transactions)")
    @GetMapping("/{demoCode}/dashboard")
    public ResponseEntity<DemoDashboard> getDashboard(
            @PathVariable String demoCode,
            @RequestParam(required = false, defaultValue = "FY2025-2026") String fy) {
        return demoService.getDashboard(demoCode, fy)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "4. Factures démo (paginé)")
    @GetMapping("/{demoCode}/invoices")
    public ResponseEntity<?> getInvoices(
            @PathVariable String demoCode,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(demoService.getInvoices(demoCode, page, size));
    }

    @Operation(summary = "5. Bulletins de paie démo")
    @GetMapping("/{demoCode}/payroll")
    public ResponseEntity<?> getPayroll(
            @PathVariable String demoCode,
            @RequestParam(required = false) String fy) {
        return ResponseEntity.ok(demoService.getPayroll(demoCode, fy));
    }

    @Operation(summary = "6. États financiers démo (bilan, CR, cash-flow, SCE)")
    @GetMapping("/{demoCode}/financial-statements/{type}")
    public ResponseEntity<?> getFinancialStatement(
            @PathVariable String demoCode,
            @PathVariable String type,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String presentationCurrency) {
        return ResponseEntity.ok(demoService.getFinancialStatement(demoCode, type, asOf, from, to, presentationCurrency));
    }

    @Operation(summary = "7. Déclarations DGI démo (TVA, TCA, RS, acompte IS)")
    @GetMapping("/{demoCode}/tax-declarations")
    public ResponseEntity<?> getTaxDeclarations(
            @PathVariable String demoCode,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(demoService.getTaxDeclarations(demoCode, year, month));
    }

    @Operation(summary = "8. Audit trail démo")
    @GetMapping("/{demoCode}/audit-trail")
    public ResponseEntity<?> getAuditTrail(
            @PathVariable String demoCode,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return ResponseEntity.ok(demoService.getAuditTrail(demoCode, limit));
    }

    @Operation(summary = "9. Timeline interactive des événements démo")
    @GetMapping("/{demoCode}/timeline")
    public ResponseEntity<?> getTimeline(
            @PathVariable String demoCode,
            @RequestParam(required = false, defaultValue = "FY2025-2026") String fy) {
        return ResponseEntity.ok(demoService.getTimeline(demoCode, fy));
    }

    // === ENDPOINTS ADMIN (auth requis) ===

    @Operation(summary = "10. [ADMIN] Re-seed toutes les démos")
    @PostMapping("/seed")
    public ResponseEntity<String> seedAll() {
        demoService.seedAll();
        return ResponseEntity.ok("Re-seed démarré pour toutes les démos");
    }

    @Operation(summary = "11. [ADMIN] Re-seed une démo spécifique")
    @PostMapping("/{demoCode}/seed")
    public ResponseEntity<String> seedOne(@PathVariable String demoCode) {
        demoService.seedOne(demoCode);
        return ResponseEntity.ok("Re-seed démarré pour " + demoCode);
    }

    @Operation(summary = "12. Clone une démo pour un client prospect")
    @PostMapping("/{demoCode}/clone")
    public ResponseEntity<?> cloneDemo(
            @PathVariable String demoCode,
            @RequestParam String newCompanyName,
            @RequestParam String newNif,
            @RequestParam(required = false, defaultValue = "false") boolean keepTransactions) {
        return ResponseEntity.ok(demoService.cloneDemo(demoCode, newCompanyName, newNif, keepTransactions));
    }
}
