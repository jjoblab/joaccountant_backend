# JOAccountant Backend — Benchmarks Gatling

Simulations de charge [Gatling](https://gatling.io) pour valider les SLO de performance du
backend Spring Boot. **Finding #23 — Benchmark Gatling.**

## Sommaire

- [Scénarios](#scénarios)
- [Pré-requis](#pré-requis)
- [Préparation des données de test](#préparation-des-données-de-test)
- [Exécution](#exécution)
  - [Option A — Via Gradle (recommandé)](#option-a--via-gradle-recommandé)
  - [Option B — Via la CLI Gatling (bundle officiel)](#option-b--via-la-cli-gatling-bundle-officiel)
- [Résultats & assertions SLO](#résultats--assertions-slo)
- [Variables de configuration](#variables-de-configuration)
- [Dépannage](#dépannage)

---

## Scénarios

| # | Scénario | Taux | Durée | Endpoints touchés |
|---|----------|------|-------|-------------------|
| 1 | Login → Dashboard | 10 users/sec | 5 min | `POST /auth/login`, `GET /reporting/dashboard` |
| 2 | Login → List invoices → Create invoice | 2 users/sec | 5 min | `POST /auth/login`, `GET /invoicing/invoices`, `POST /invoicing/invoices` |
| 3 | Login → Trial balance → Balance sheet | 5 users/sec | 3 min | `POST /auth/login`, `GET /accounting-engine/trial-balance`, `GET /financial-statements/balance-sheet` |

**Total (~8 min)** : ~3000 logins, ~3000 dashboards, ~600 créations de facture,
~900 trial-balances + ~900 balance-sheets.

---

## Pré-requis

- JDK 17 (`JAVA_HOME` pointant vers JDK 17)
- Le backend JOAccountant démarré et accessible sur `http://localhost:8080`
  (voir `./gradlew :app:devRun`)
- Une base de données de test avec au moins :
  - 1 utilisateur (email/password valides — non-MFA pour simplifier)
  - 1 entreprise (`company.id`)
  - 1 tiers facturable (`thirdParty.id`) — client ou fournisseur
- Gatling 3.9+ (bundled via le plugin Gradle, ou télécharger le [bundle officiel](https://gatling.io/releases/))

---

## Préparation des données de test

Avant la première exécution, créer un jeu de données de test via l'API ou via les scripts
de seed Python fournis à la racine du repo :

```bash
# Option 1 : seed commerce (factures, clients, écritures)
python3 seed_commerce.py --api http://localhost:8080

# Option 2 : créer l'utilisateur Gatling à la main via l'API
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "gatling@joaccountant.com",
    "password": "gatling-pass-123",
    "fullName": "Gatling Load Test"
  }'
```

Récupérer ensuite les IDs nécessaires :

```bash
# Login pour récupérer le companyId et un thirdPartyId
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"gatling@joaccountant.com","password":"gatling-pass-123"}' \
  | jq -r .accessToken)

# Company ID (première company de l'utilisateur)
COMPANY_ID=$(curl -s http://localhost:8080/api/v1/auth/me \
  -H "Authorization: Bearer $TOKEN" | jq -r '.companies[0].id')

# Third party ID (premier client de la company)
THIRDPARTY_ID=$(curl -s "http://localhost:8080/api/v1/companies/$COMPANY_ID/third-parties?page=0&size=1" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.content[0].id')
```

---

## Exécution

### Option A — Via Gradle (recommandé)

Le plugin Gatling Gradle n'est pas activé par défaut dans ce projet (pour éviter d'alourdir
le classpath de build standard). Pour l'activer temporairement, ajouter à `app/build.gradle.kts` :

```kotlin
plugins {
    id("io.gatling.gradle") version "3.10.5"
}

gatling {
    // Simulations à exécuter avec `./gradlew :app:gatlingRun`
    simulations = {
        include(project.findProperty("gatlingSimu") ?: "**/JoAccountantSimulation.scala")
    }
    jvmArgs = listOf(
        "-DbaseUrl=${System.getenv("GATLING_BASE_URL") ?: "http://localhost:8080"}",
        "-Dauth.email=${System.getenv("GATLING_EMAIL") ?: "gatling@joaccountant.com"}",
        "-Dauth.password=${System.getenv("GATLING_PASSWORD") ?: "gatling-pass-123"}",
        "-Dcompany.id=${System.getenv("GATLING_COMPANY_ID") ?: "00000000-0000-0000-0000-a00000000001"}",
        "-DthirdParty.id=${System.getenv("GATLING_THIRDPARTY_ID") ?: "00000000-0000-0000-0000-c00000000001"}"
    )
}

dependencies {
    gatling("io.gatling.highcharts:gatling-charts-highcharts:3.10.5")
}
```

Puis lancer :

```bash
GATLING_BASE_URL=http://localhost:8080 \
GATLING_EMAIL=gatling@joaccountant.com \
GATLING_PASSWORD=gatling-pass-123 \
GATLING_COMPANY_ID=<your-company-uuid> \
GATLING_THIRDPARTY_ID=<your-thirdparty-uuid> \
./gradlew :app:gatlingRun
```

Le rapport HTML est généré dans `app/build/reports/gatling/joaccountantsimulation-<timestamp>/index.html`.

### Option B — Via la CLI Gatling (bundle officiel)

Télécharger Gatling 3.10+ depuis https://gatling.io/releases/, puis :

```bash
export GATLING_HOME=/opt/gatling-charts-highcharts-bundle-3.10.5
export SIMU_DIR=$(pwd)/app/src/test/gatling

# Copier la simulation dans l'arborescence attendue par Gatling
mkdir -p $GATLING_HOME/user-files/simulations/jo/accountant
cp $SIMU_DIR/jo/accountant/JoAccountantSimulation.scala \
   $GATLING_HOME/user-files/simulations/jo/accountant/

# Lancer
$GATLING_HOME/bin/gatling.sh \
  -rm local \
  -rd "jo-accountant-v4.8" \
  -sf $GATLING_HOME/user-files/simulations \
  -s jo.accountant.JoAccountantSimulation \
  -bf $GATLING_HOME/user-files/bodies \
  -rf $(pwd)/build/gatling-results \
  -JbaseUrl=http://localhost:8080 \
  -Jauth.email=gatling@joaccountant.com \
  -Jauth.password=gatling-pass-123 \
  -Jcompany.id=<your-company-uuid> \
  -JthirdParty.id=<your-thirdparty-uuid>
```

Le rapport HTML est généré dans `build/gatling-results/joaccountantsimulation-<timestamp>/index.html`.

---

## Résultats & assertions SLO

La simulation embarque 3 assertions Gatling qui font échouer le build si elles sont violées :

| Assertion | Seuil | Raison |
|-----------|-------|--------|
| `global.failedRequests.percent` | < 0.1% | Pas d'erreur 5xx, timeout, ou ratelimit |
| `global.responseTime.percentile(95)` | < 1000 ms | P95 lecture < 1s |
| `global.responseTime.percentile(99)` | < 2000 ms | P99 agrégation reporting < 2s |

> ⚠️ Le scénario 3 (trial-balance + balance-sheet) est le plus exigeant — agrégations SQL
> sur `JournalLine`. Si le P99 > 2s, envisager un index composit `(company_id, posted, account_id)`
> ou un cache L2 sur `getTrialBalance` (audit v4.7 §7.2).

Exemple de rapport Gatling :

```
---- Global Information --------------------------------------------------------
> request count                                    7200 (OK=7199  KO=1     )
> min response time                                   5 (OK=5      KO=500   )
> max response time                                1850 (OK=1840   KO=1850  )
> mean response time                                142 (OK=140    KO=1175  )
> std deviation                                     198 (OK=195    KO=675   )
> response time 50th percentile                     85 (OK=84     KO=1175  )
> response time 75th percentile                    165 (OK=164    KO=1500  )
> response time 95th percentile                    480 (OK=478    KO=1800  )
> response time 99th percentile                    920 (OK=915    KO=1850  )
> mean requests/sec                                15.0 (OK=15.0   KO=0.0   )
---- Response Time Distribution ------------------------------------------------
> response time < 100 ms                            3500 ( 49%)
> 100 ms < response time < 300 ms                  2400 ( 33%)
> 300 ms < response time < 500 ms                   850 ( 12%)
> 500 ms < response time < 1 s                       380 (  5%)
> 1 s < response time < 2 s                          69 (  1%)
> 2 s < response time < 5 s                           0 (  0%)
> 5 s < response time < 10 s                          0 (  0%)
> response time > 10 s                                0 (  0%)
---- Assertions ----------------------------------------------------------------
> global.failedRequests.percent: 0.01% < 0.1%                              ✓
> global.responseTime.percentile(95): 480 < 1000                          ✓
> global.responseTime.percentile(99): 920 < 2000                          ✓
================================================================================
```

---

## Variables de configuration

Toutes les variables sont injectées via `-D` JVM system properties :

| Propriété | Défaut | Description |
|-----------|--------|-------------|
| `baseUrl` | `http://localhost:8080` | URL de base du backend (sans trailing slash). |
| `auth.email` | `gatling@joaccountant.com` | Email du user de test (non-MFA). |
| `auth.password` | `gatling-pass-123` | Mot de passe du user de test. |
| `company.id` | UUID statique de test | Company sur laquelle les requêtes sont jouées. |
| `thirdParty.id` | UUID statique de test | Tiers facturable pour le scénario 2 (create invoice). |

> Pour des tests multi-utilisateurs réaliste, brancher un feeder CSV
> (`csv("users.csv").circular`) en remplaçant le `Iterator.continually` du `users` feeder.
> Le CSV doit contenir 2 colonnes `email` et `password`.

---

## Dépannage

### `check(status.is(200))` failed on `POST /auth/login`

→ Vérifier que l'utilisateur `auth.email` existe et n'a pas de MFA activée. Si MFA activée,
désactiver via `PUT /api/v1/users/{id}/mfa/disable` ou créer un nouvel utilisateur dédié
Gatling sans MFA.

### `check(jsonPath("$.accessToken").saveAs("accessToken"))` returns null

→ Le login retourne `mfaRequired: true` au lieu de `accessToken`. Voir point précédent.

### `400 Bad Request` sur `POST /invoicing/invoices`

→ Vérifier que `thirdParty.id` existe dans la company. Si la company n'a pas encore de tiers,
en créer un via `POST /api/v1/companies/{companyId}/third-parties`.

### Rate limit (`429 Too Many Requests`) sur scénario 1 (10 users/sec)

→ Le `RateLimitFilter` (audit v4.7 §6.2) limite à ~100 req/min par IP. Désactiver pour le test
via `-Dapp.security.rateLimit.enabled=false` ou monter la limite via
`-Dapp.security.rateLimit.rpm=10000`.

### HikariCP connection timeout

→ Le pool Hikari est dimensionné à 30 (audit v4.7 §7.2). Si saturé, augmenter via
`-DHIKARI_MAX_POOL_SIZE=100` et/ou activer les virtual threads (Java 21+).

---

## Roadmap (v4.9+)

- Feeder CSV multi-utilisateurs (rotation des credentials pour stresser le JWT issuer)
- Scénario 4 : clôture d'exercice (1 user / min — opération lourde mais rare)
- Scénario 5 : import MT940 (10 fichiers / min — parser + reconciliation auto)
- Intégration CI/CD : exécuter en smoke mode (1 min, 1 user/sec) sur chaque PR via GitHub Actions
