# Module : app

> Bootstrap Spring Boot de l'application JOAccountant — configuration globale, sécurité, tests d'intégration.

## Rôle du module

Le module `:app` est le point d'assemblage de tous les modules métier. Il contient la
classe `main()` qui démarre l'application, la configuration Spring Security globale, les
filtres de requête (auth, rate-limit, tenant), la configuration OpenAPI/Swagger, et
l'ensemble des tests d'intégration du projet.

Il ne contient **aucune logique métier** — il se borne à configurer l'application et à
exécuter les tests d'intégration qui valident l'assemblage des 21 modules métier.

## Ce qu'il fait précisément

### Composants clés

- `JoAccountantApplication` — classe `main()` avec `@SpringBootApplication`,
  `@EntityScan("jo.accountant")`, `@EnableJpaRepositories("jo.accountant")`,
  `@EnableAsync`, `@EnableScheduling`. Scanne tous les packages `jo.accountant.*` des 27
  modules métier.
- `SecurityConfig` — configuration Spring Security : JWT **stateless HS256 (défaut) ou RS256**
  (configurable via `app.jwt.algorithm`), CORS restrictif configurable, CSRF désactivé,
  session `STATELESS`. Headers de sécurité (HSTS, X-Frame-Options, X-Content-Type-Options,
  Referrer-Policy, CSP). Endpoints `permitAll` :
  - `/api/v1/auth/register`, `/login`, `/login/mfa` (V41), `/refresh`, `/logout`,
    `/forgot-password`, `/reset-password`.
  - `/api/v1/auth/mfa/setup`, `/mfa/verify`, `/mfa/check`, `/mfa/recovery-code` (V41 —
    l'utilisateur peut être dans l'entre-deux du flow 2-step ; le challenge token est validé
    par `MfaService`).
  - `/.well-known/jwks.json` (RFC 7517 — clé publique, jamais la privée).
  - `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`, `/actuator/info`.
  - Tout le reste `authenticated()`.
  Trois filtres :
  - `RateLimitFilter` (avant auth) — rate-limit sur `/auth/login`, `/auth/forgot-password`,
    `/auth/register` (10 tentatives/min/IP, 429).
  - `TenantContextFilter` (avant auth) — stamp `TenantContext.correlationId` et log MDC.
  - `TenantClaimFilter` (après auth) — extrait `userId` du `sub` JWT et `companyId` du
    path `/api/v1/companies/{companyId}/...`, valide l'appartenance de l'utilisateur à la
    société (404 si pas accès, pas 403 — anti-fuite §3.9).
- `BatchConfig` — **V52 — Spring Batch 5.x**. Définit 2 Jobs :
  - `payrollJob` — calcule les `Payslip` d'une campagne via `PayrollCalculator` (chunk de 50
    employés, retry 3× sur `IOException`). À la fin, le `PayrollRun` est mis à jour (totaux
    + `status=CALCULATED`).
  - `fiscalYearClosingJob` — exécute `AccountingEngineService.closeFiscalYear` (écriture de
    clôture + écriture d'ouverture N+1) puis `FinancialStatementsService.createClosingSnapshots`
    (fige bilan + CR). Retry 1× (la clôture est idempotente).
  Les Jobs ne sont PAS auto-démarrés au boot (`spring.batch.job.enabled=false`) — ils sont
  lancés manuellement via `BatchController` (ou par cron ShedLock en production). Schéma
  DB `BATCH_*` créé par `V52__spring_batch_schema.sql` (extraction standard
  `schema-postgresql.sql` de Spring Batch 5.2).
- `BatchController` — expose 2 endpoints d'administration (rôle ADMIN requis, voir
  « Endpoints exposés » ci-dessous).
- `RateLimitFilter` — 10 tentatives/min/IP sur les endpoints d'auth. Implémentation
  `ConcurrentHashMap` en mémoire (mono-instance uniquement — pour multi-instances,
  migrer vers Redis ou Bucket4j).
- `TenantClaimFilter` — valide que l'utilisateur a un rôle dans la société du path.
  Renvoie 404 (pas 403) si l'utilisateur n'a pas accès, pour ne pas fuiter l'existence
  de la société.
- `ScheduledAlertsConfig` — cron quotidien à 06:00 UTC pour vérifier les factures
  échues (implémentation simplifiée — le scan réel nécessite un service cross-tenant).
- `OpenApiConfig` — configuration Swagger/OpenAPI.
- `DevLauncher` / `JoAccountantDevApp` — mode dev avec PostgreSQL embarqué (Zonky) via
  `:test-support`.
- **OpenTelemetry tracing** (audit v4.7 §9.3 Finding #3) — exporter OTLP configurable via
  `OTEL_EXPORTER_OTLP_ENDPOINT` (ex: `http://otel-collector:4317` pour gRPC),
  `OTEL_EXPORTER_OTLP_PROTOCOL` (`grpc` défaut, ou `http/protobuf`),
  `OTEL_SERVICE_NAME=joaccountant`, `OTEL_TRACES_SAMPLER_ARG=0.1` (10% des traces).
  En l'absence d'endpoint OTLP, les traces sont dropped (pas d'erreur). Un collector
  d'exemple est fourni dans `deploy/otel-collector-config.yaml` (receiver OTLP + exporter
  `logging` pour test local ; à remplacer par Tempo/Jaeger/Zipkin/Datadog en prod).

### Configuration (application.yml)

- **Datasource** : `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (variables d'env). HikariCP
  `maximum-pool-size: 10`.
- **JPA** : `ddl-auto: none` (Flyway est l'unique source de schéma, §3.2). Dialecte
  PostgreSQL. `jdbc.time_zone: UTC`. `open-in-view: false`.
- **Flyway** : activé, `classpath:db/migration`.
- **Threads virtuels** : `spring.threads.virtual.enabled: true` (Java 21).
- **Server** : port 8080 (API), port 8081 (management Actuator — port séparé §3.10).
- **Management** : `health`, `info`, `metrics` exposés. `health.show-details: when-authorized`.
- **Swagger** : `/swagger-ui.html`, `/v3/api-docs`.
- **JWT** : `app.jwt.secret` (256 bits min, à override en production), access token TTL
  900s (15 min).
- **Subscription** : `app.subscription.max-companies-per-user: 3` (défaut §12).
- **Storage** : `app.storage.fs.root` (défaut `./build/storage`).
- **Logging** : pattern avec `correlationId`, `userId`, `companyId` en MDC. Niveau
  `INFO` pour `jo.accountant`, `WARN` pour Hibernate SQL.

### Tests d'intégration

18 fichiers de tests d'intégration dans `src/test/java/jo/accountant/app/` :

| Fichier | @Test | Référentiel | Secteur | Couverture |
|---|---|---|---|---|
| `Phase1IntegrationTest` | ~19 | PCN_HAITI + SYSCOHADA | COMMERCE, SERVICE, ONG | Register, login, refresh, create company, wizard, complete, module activation |
| `ChartOfAccountsIntegrationTest` | — | SYSCOHADA + IFRS (négatif) | n/a | Initialize, create child, update, search, locked, balance guard |
| `ApprovalWorkflowIntegrationTest` | — | n/a | n/a | Create rule, evaluate, approve, reject, 4-eyes, multi-approvals |
| `AccountingEngineIntegrationTest` | ~28 | SYSCOHADA | n/a | Fiscal year, periods, journals, entries, post, reverse, ledger, trial balance, period lock |
| `FinancialStatementsIntegrationTest` | — | SYSCOHADA | n/a | Balance sheet, income statement, snapshot |
| `ThirdPartiesIntegrationTest` | — | SYSCOHADA | n/a | Tiers, lettrage, dé-lettrage, relevé, suggestions, balance âgée |
| `FixedAssetsIntegrationTest` | — | SYSCOHADA | n/a | Asset, schedule, post depreciation, dispose |
| `InventoryIntegrationTest` | 11 | SYSCOHADA | n/a | Warehouse, item, stock-moves, FIFO, COGS |
| `InvoicingIntegrationTest` | 12 | SYSCOHADA | n/a | Invoice DRAFT, issue, entry, payment, credit note, PDF |
| `TimeBillingIntegrationTest` | 11 | n/a | n/a | Project, rate, timesheet, approve, WIP |
| `BankReconciliationIntegrationTest` | 9 | SYSCOHADA | n/a | Bank account, CSV/OFX import, auto-match, manual match, status |
| `FundsGrantsIntegrationTest` | 9 | SYSCOHADA | n/a | Grant RESTRICTED/UNRESTRICTED, receipt, donor report, close fiscal year |
| `NotificationsIntegrationTest` | 7 | n/a | n/a | Preferences, alert rules, mark as read |
| `TaxIntegrationTest` | 5 | SYSCOHADA | n/a | TVA rule, withholding rule, declaration |
| `DocumentGenerationIntegrationTest` | 7 | SYSCOHADA | n/a | Template, PDF generation, immutability |
| `DocumentNumberingIntegrationTest` | — | n/a | n/a | Sequence config, atomic issue (50 threads), preview |
| `ReportingIntegrationTest` | 7 | SYSCOHADA | n/a | PDF balance sheet, CSV ledger, dashboard |
| `ArchUnitTest` | — | n/a | n/a | 30 règles d'architecture (dépendances entre modules, conventions) |
| `RecordingNotificationChannel` | 0 | n/a | n/a | Helper — capture les notifications pour assertion |

**Total** : ~200 `@Test`. Aucun `@ParameterizedTest` (audit 3.x — matrice référentiel ×
secteur largement non couverte).

### Configuration de test (application-test.yml)

- PostgreSQL embarqué (Zonky) via `:test-support`, port aléatoire
  `${embedded.postgres.port}`.
- HikariCP `maximum-pool-size: 5`.
- JWT secret de test (256 bits).
- Storage root `./build/storage-test`.

## Endpoints exposés

Ce module n'expose pas d'endpoint REST direct métier — il configure l'application et agrège les
endpoints exposés par les 27 modules métier. Cependant, il expose directement :

- **Spring Batch (V52 — rôle ADMIN requis)** :
  - `POST /api/v1/companies/{companyId}/admin/batch/payroll?runId=` — lance `payrollJob` sur
    la campagne `runId`. `202 Accepted` + `BatchJobResponse {jobExecutionId, jobName, status,
    exitCode, createdAt}` (le Job est synchrone — au retour, les payslips sont générés et le
    `PayrollRun` est en `CALCULATED`).
  - `POST /api/v1/companies/{companyId}/admin/batch/closing?fiscalYearId=` — lance
    `fiscalYearClosingJob` sur l'exercice. `202 Accepted` ou `200 OK` (NOOP —
    `JobInstanceAlreadyCompleteException`) si l'exercice a déjà été clôturé avec les mêmes
    paramètres (la clôture est idempotente).
- **Endpoints globaux** (non métier) :
  - `GET /swagger-ui.html` — interface Swagger UI.
  - `GET /v3/api-docs` — spec OpenAPI 3.
  - `GET /.well-known/jwks.json` — JWKS RFC 7517 (public, activé en RS256 — voir `:auth`).
  - `GET /actuator/health` — health check (port 8081).
  - `GET /actuator/info` — infos application (port 8081).
  - `GET /actuator/metrics` — métriques (port 8081).
  - `GET /actuator/prometheus` — exposition Prometheus (port 8081, scrapé par
    `podAnnotations` dans le Helm chart).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

Le module `:app` dépend des **27 modules métier** (`:core`, `:auth`, `:company`,
`:audit-trail`, `:document-numbering`, `:chart-of-accounts`, `:approval-workflow`,
`:analytics`, `:accounting-engine`, `:financial-statements`, `:third-parties`,
`:fixed-assets`, `:inventory`, `:time-billing`, `:document-generation`, `:invoicing`,
`:bank-reconciliation`, `:funds-grants`, `:notifications`, `:tax`, `:reporting`,
`:purchasing`, `:expenses`, `:employees`, `:payroll`, `:fx-operations`, `:purchase-orders`)
+ `:test-support` (PostgreSQL embarqué Zonky pour les tests et le mode dev). Dépendances
additionnelles : `spring-boot-starter-batch` (Jobs V52), `io.opentelemetry` (tracing OTLP).

### Modules qui dépendent de celui-ci

Aucun — `:app` est la racine du graphe d'exécution.

### Événements publiés / consommés

- **Publie** : aucun.
- **Consomme** : aucun directement. Les listeners d'événements sont dans les modules
  métier (`:audit-trail`, `:notifications`, `:accounting-engine`).

## Tables / migrations Flyway

- `src/main/resources/db/migration/V0_000__init_extensions.sql` — installation de
  l'extension `pgcrypto` et de la fonction PL/pgSQL `uuidv7()` (RFC 9562). Cette fonction
  est utilisée comme `DEFAULT` pour toutes les PK UUID du projet (meilleure localité
  d'index que v4). PostgreSQL < 18 n'expose pas `uuidv7()` nativement.
- `src/main/resources/db/migration/V37__shedlock_table.sql` — table `shedlock` pour
  ShedLock (verrous distribués multi-instances, audit v4.7 §9).
- `src/main/resources/db/migration/V52__spring_batch_schema.sql` — **V52**. Schéma Spring
  Batch 5.x (PostgreSQL) : tables `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`,
  `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`,
  `BATCH_JOB_EXECUTION_CONTEXT`. Extrait de `schema-postgresql.sql` livré avec
  `spring-batch-core` (alignment Spring Boot 3.5 = Spring Batch 5.2). Config
  `spring.batch.jdbc.initialize-schema=never` — Flyway est l'unique source du schéma.

## Points d'attention (hérités de l'audit)

- ⚠️ **B5 — Contrôle de rôles manquant sur 100/104 endpoints** — `RoleChecker` existe
  dans `:core` mais n'est appelé que sur 4 endpoints (`closeFiscalYear`,
  `postJournalEntry`, `reverseJournalEntry` dans `:accounting-engine`). Un `VIEWER` peut
  techniquement créer des écritures, factures, etc. La correction passe par l'ajout de
  `roleChecker.ensureRole(...)` sur tous les POST/PATCH/DELETE mutatifs, ou par migration
  vers `@PreAuthorize`. Le client mobile ne doit PAS supposer que le backend enforce les
  rôles — il doit désactiver les boutons d'action côté UI pour les `VIEWER`/`AUDITOR`.
- ⚠️ **A-4 — Codes d'erreur non documentés dans le Swagger** — 34 endpoints n'ont pas de
  `@ApiResponses` complet. Le client mobile doit gérer génériquement les `ProblemDetail`
  non prévus (cf. `GlobalExceptionHandler` qui produit toujours un `ProblemDetail` avec
  `code`, `correlationId`, `companyId`, `timestamp`).
- ⚠️ **`If-Match` collecté mais jamais utilisé** — `POST /journal-entries` accepte un
  header `If-Match` mais ne fait rien avec — pas de concurrence optimiste. Aucun ETag
  généré nulle part. Le client mobile ne doit pas compter sur `If-Match` pour la
  concurrence.
- ⚠️ **Rate-limiting en mémoire** — `RateLimitFilter` utilise un `ConcurrentHashMap` en
  mémoire. En multi-instances, chaque instance a son propre compteur — le rate-limit
  effectif est multiplié par le nombre d'instances. Migrer vers Redis ou Bucket4j pour
  la production.
- ⚠️ **JWT secret par défaut faible** — `app.jwt.secret` a une valeur par défaut
  `dev-only-secret-please-override-in-production-...`. **DOIT être override en
  production** via la variable d'env `APP_JWT_SECRET`. Le client mobile ne peut rien faire
  côté UI — c'est une configuration serveur.
- ⚠️ **CORS ouvert à `*`** — `CorsConfiguration.setAllowedOriginPatterns(List.of("*"))`
  avec `allowCredentials(true)`. En production, restreindre aux domaines du client mobile
  et web.
- ⚠️ **`ScheduledAlertsConfig` non implémenté** — le cron quotidien est en place mais le
  corps de la méthode ne fait que logger (le scan réel des factures échues n'est pas
  codé). Les alertes `INVOICE_OVERDUE` et `FISCAL_PERIOD_PAST_DUE` ne se déclenchent pas
  automatiquement.
- ⚠️ **Pas de test paramétré** — 0 `@ParameterizedTest`. La matrice référentiel × type métier
  (6 × 7 = 42 combinaisons) est largement non couverte (audit 3.3) : SYSCOHADA 53 % des
  tests, PCN_HAITI 10 %, IFRS_FULL ~1 %, IFRS_SME / PCG_FRANCE / PCGR_CANADA 0 %. Le type
  métier `CUSTOM` (qui remplace l'ancien secteur `MIXTE`) est désormais couvert
  (restructuration 2026-07-24 — voir `Phase1IntegrationTest.customBusinessTypeActivatesManuallySelectedModules`).
- ⚠️ **0 test sur `closeFiscalYear`** (audit 3.7) — la clôture d'exercice principale n'a
  aucun test d'intégration. Un bug dans le calcul du résultat ne serait pas attrapé.
- ⚠️ **`open-in-view: false`** — les lazy loading en dehors d'une transaction lèveront
  une `LazyInitializationException`. Le client mobile n'est pas impacté directement, mais
  les développeurs backend doivent être vigilants.

## Tests

Voir le tableau ci-dessus. ~200 `@Test` répartis sur 18 fichiers. Pour exécuter les tests :
`./gradlew :app:test`. Pour démarrer l'application en mode dev avec PostgreSQL embarqué :
`./gradlew :app:devRun`.
