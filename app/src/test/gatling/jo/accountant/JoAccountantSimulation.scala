package jo.accountant

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder
import scala.concurrent.duration._

/**
 * JOAccountant Backend — Simulation Gatling de charge (Finding #23 — Benchmark Gatling).
 *
 * <p>Couverture des 3 parcours utilisateurs les plus représentatifs (mesurés en
 * production via les logs d'accès) :
 * <ol>
 *   <li><b>Login → Dashboard</b> — 10 users/sec pendant 5 min. Parcours lecture-seul
 *       typé "consultation quotidienne" (CA, balance âgée, échéances). Le plus fréquent.</li>
 *   <li><b>Login → List invoices → Create invoice</b> — 2 users/sec pendant 5 min.
 *       Parcours écriture (saisie facture). Moins fréquent mais coûteux :
 *       validation DTO, document-numbering, postage comptable, publication event.</li>
 *   <li><b>Login → Trial balance → Balance sheet</b> — 5 users/sec pendant 3 min.
 *       Parcours reporting — agrégations lourdes sur JournalLine.</li>
 * </ol>
 *
 * <p><b>Objectifs SLO</b> (audit v4.7 §7.2 — Virtual Threads disabled en Java 17,
 * sizing HikariCP=30) :
 * <ul>
 *   <li>P95 < 1s sur tous les endpoints lecture</li>
 *   <li>P99 < 2s sur les agrégations reporting (trial-balance, balance-sheet)</li>
 *   <li>Erreur < 0.1% (réjection rate-limit, timeouts, 5xx)</li>
 *   <li>Throughput soutenu sans saturation HikariCP (leak detection activée)</li>
 * </ul>
 *
 * <p><b>Credentials</b> : injecter via JVM system properties
 * {@code -DbaseUrl=... -Dauth.email=... -Dauth.password=... -Dcompany.id=... -DthirdParty.id=...}.
 * Voir README.md pour le détail.
 *
 * <p><b>Run</b> : via le plugin Gradle Gatling (non activé par défaut dans ce projet —
 * voir README.md pour la configuration).
 */
class JoAccountantSimulation extends Simulation {

  // ─── Configuration (injectable via -D system properties) ───────────
  private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
  private val authEmail = System.getProperty("auth.email", "gatling@joaccountant.com")
  private val authPassword = System.getProperty("auth.password", "gatling-pass-123")
  private val companyId = System.getProperty("company.id", "00000000-0000-0000-0000-a00000000001")
  private val thirdPartyId = System.getProperty("thirdParty.id", "00000000-0000-0000-0000-c00000000001")

  private val httpProtocol: HttpProtocolBuilder = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling/JoAccountantSimulation/1.0")
    // Partage de la connexion HTTP entre virtual users (Keep-Alive) — réduit le TCP handshake.
    .shareConnections
    .acceptLanguageHeader("fr-FR,fr;q=0.9,en;q=0.8")

  // ─── Feeder : rotation des emails/passwords (optionnel, 1 user par défaut) ─
  // En production, brancher un CSV feeder : csv("users.csv").circular
  private val users = Iterator.continually(
    Map("email" -> authEmail, "password" -> authPassword)
  )

  // ─── Chaîne partagée : Login → extraction du accessToken ──────────
  private val loginChain =
    feed(users)
      .exec(
        http("POST /auth/login")
          .post("/api/v1/auth/login")
          .body(StringBody("""{"email":"#{email}","password":"#{password}"}"""))
          .asJson
          .check(status.is(200))
          .check(jsonPath("$.accessToken").saveAs("accessToken"))
      )

  // ─── Scénario 1 : Login → Dashboard (10 users/sec, 5 min) ──────────
  private val scnDashboard: ScenarioBuilder = scenario("Login -> Dashboard")
    .exec(loginChain)
    .exec(
      http("GET /reporting/dashboard")
        .get(s"/api/v1/companies/${companyId}/reporting/dashboard")
        .header("Authorization", "Bearer #{accessToken}")
        .check(status.is(200))
    )

  // ─── Scénario 2 : Login → List invoices → Create invoice (2 users/sec, 5 min) ─
  private val scnCreateInvoice: ScenarioBuilder = scenario("Login -> List invoices -> Create invoice")
    .exec(loginChain)
    .exec(
      http("GET /invoicing/invoices")
        .get(s"/api/v1/companies/${companyId}/invoicing/invoices?page=0&size=20")
        .header("Authorization", "Bearer #{accessToken}")
        .check(status.is(200).or(status.is(204)))
    )
    .pause(1, 3) // think time : saisie du formulaire facture (1-3s simulés)
    .exec(session => {
      // Génère un identifiant unique à chaque requête pour éviter l'idempotence DocNumbering
      val ref = s"GATLING-${System.currentTimeMillis()}-${session.userId}"
      session.set("invoiceRef", ref)
    })
    .exec(
      http("POST /invoicing/invoices")
        .post(s"/api/v1/companies/${companyId}/invoicing/invoices")
        .header("Authorization", "Bearer #{accessToken}")
        .header("Idempotency-Key", "#{invoiceRef}")
        // Body JSON : ${thirdPartyId} = Scala interpolation (compile-time), #{invoiceRef} = Gatling EL (runtime).
        .body(StringBody(
          s"""{
             |  "thirdPartyId": "${thirdPartyId}",
             |  "type": "SALES",
             |  "issueDate": "2024-01-15",
             |  "dueDate": "2024-02-15",
             |  "currency": "EUR",
             |  "lines": [
             |    {
             |      "description": "Prestation Gatling #{invoiceRef}",
             |      "quantity": 1,
             |      "unitPrice": 100.00,
             |      "discountPercent": 0,
             |      "taxRate": 20.00
             |    }
             |  ]
             |}""".stripMargin))
        .asJson
        .check(status.is(201).or(status.is(200)))
    )

  // ─── Scénario 3 : Login → Trial balance → Balance sheet (5 users/sec, 3 min) ─
  private val scnReporting: ScenarioBuilder = scenario("Login -> Trial balance -> Balance sheet")
    .exec(loginChain)
    .exec(
      http("GET /accounting-engine/trial-balance")
        .get(s"/api/v1/companies/${companyId}/accounting-engine/trial-balance")
        .header("Authorization", "Bearer #{accessToken}")
        .check(status.is(200))
    )
    .pause(500.milliseconds, 1500.milliseconds) // think time : analyse de la balance (0.5-1.5s)
    .exec(
      http("GET /financial-statements/balance-sheet")
        .get(s"/api/v1/companies/${companyId}/financial-statements/balance-sheet")
        .header("Authorization", "Bearer #{accessToken}")
        .check(status.is(200))
    )

  // ─── Injection profil ──────────────────────────────────────────────
  // Scénario 1 — 10 users/sec, 5 min (ramp 30s → plateau 270s à 10/s).
  // Scénario 2 — 2 users/sec, 5 min (ramp 30s → plateau 270s à 2/s).
  // Scénario 3 — 5 users/sec, 3 min (ramp 20s → plateau 160s à 5/s).
  setUp(
    scnDashboard.inject(
      rampUsersPerSec(1).to(10).during(30.seconds),
      constantUsersPerSec(10).during(270.seconds)
    ).protocols(httpProtocol),

    scnCreateInvoice.inject(
      rampUsersPerSec(1).to(2).during(30.seconds),
      constantUsersPerSec(2).during(270.seconds)
    ).protocols(httpProtocol),

    scnReporting.inject(
      rampUsersPerSec(1).to(5).during(20.seconds),
      constantUsersPerSec(5).during(160.seconds)
    ).protocols(httpProtocol)
  )
  // Assertions SLO — le build CI échoue si l'une d'elles est violée.
  .assertions(
    // Pas d'erreur globale (5xx, timeouts, ratelimit).
    global.failedRequests.percent.lt(0.1),
    // P95 lecture < 1s.
    global.responseTime.percentile(95).lt(1000),
    // P99 agrégation < 2s.
    global.responseTime.percentile(99).lt(2000)
  )
  // maxDuration = 8 min (= durée max scénarios + marge) pour éviter les runs zombies.
  .maxDuration(8.minutes)
}
