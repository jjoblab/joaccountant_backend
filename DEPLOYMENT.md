# Guide de déploiement — JOAccountant v8.3.1 (corrected)

## Prérequis

- **Java 17** (OpenJDK 17 ou Temurin 17)
- **PostgreSQL 14+** (ou utilisation du PostgreSQL embarqué Zonky via `DevLauncher`)
- **4 GB RAM** minimum (8 GB recommandé pour les démos avec seed data)

## Option 1 — Démarrage rapide avec PostgreSQL embarqué (dev/démo)

Le `DevLauncher` démarre un PostgreSQL embarqué in-process (Zonky) — aucune installation
PostgreSQL requise. Idéal pour dev local, démos, tests manuels.

```bash
# JDK 17 dans le PATH
export JAVA_HOME=/path/to/jdk-17
export PATH=$JAVA_HOME/bin:$PATH

# Démarrer le backend avec données de démo (4 entreprises fictives haïtiennes)
./gradlew :app:bootRun --args='--server.port=8080'

# OU sans données de démo (démarrage plus rapide, moins de RAM)
SKIP_DEMO=true ./gradlew :app:bootRun --args='--server.port=8080'
```

Le backend est accessible sur `http://localhost:8080`.
- Swagger UI : `http://localhost:8080/swagger-ui/index.html`
- Actuator health : `http://localhost:8080/actuator/health`

## Option 2 — Déploiement avec bootJar (production-like)

```bash
# 1. Construire le JAR
./gradlew :app:bootJar
# → app/build/libs/app-8.3.1.jar (197 MB, fat JAR)

# 2. Configurer les variables d'environnement
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/joaccountant
export SPRING_DATASOURCE_USERNAME=joaccountant
export SPRING_DATASOURCE_PASSWORD=<your-password>
export APP_JWT_SECRET=<your-256-bit-secret>
export SPRING_PROFILES_ACTIVE=prod

# 3. Lancer
java -jar app/build/libs/app-8.3.1.jar --server.port=8080
```

## Option 3 — Docker

```bash
# Construire l'image
docker build -t joaccountant:8.3.1 .

# Lancer avec un PostgreSQL externe
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/joaccountant \
  -e SPRING_DATASOURCE_USERNAME=joaccountant \
  -e SPRING_DATASOURCE_PASSWORD=<password> \
  -e APP_JWT_SECRET=<your-256-bit-secret> \
  -e SPRING_PROFILES_ACTIVE=prod \
  joaccountant:8.3.1
```

## Vérification post-démarrage

```bash
# 1. Health check
curl http://localhost:8080/actuator/health
# → {"status":"UP"}

# 2. Vérifier que Flyway a appliqué les 102 migrations
# (vérifier dans les logs de démarrage : "Successfully applied 102 migrations")

# 3. Tester l'authentification (compte démo si SKIP_DEMO=false)
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@joaccountant.ht","password":"Demo1234!2026"}'
# → {"accessToken":"...", "expiresIn":900, ...}

# 4. Tester un endpoint company-scoped (remplacer <token> et <companyId>)
curl http://localhost:8080/api/v1/companies/<companyId>/purchase-orders \
  -H "Authorization: Bearer <token>"
# → 403 MODULE_NOT_ENABLED (si le module PURCHASING n'est pas activé pour la company)
# → 200 [...] (si le module est activé et des POs existent)
```

## Profils Spring disponibles

| Profil | Usage | Description |
|--------|-------|-------------|
| `dev` | Développement local | Logs détaillés, HSTS désactivé, CORS permissif |
| `demo` | Démos commerciales | Active le seed de 4 entreprises fictives (is_demo=true) |
| `test` | Tests d'intégration | PostgreSQL embarqué Zonky, JWT secret de test |
| `staging` | Pré-production | Configuration proche de la prod, données de démo optionnelles |
| `prod` | Production | Sécurité maximale, CORS restrictif, HSTS activé |

## Variables d'environnement clés

| Variable | Défaut | Description |
|----------|--------|-------------|
| `SPRING_DATASOURCE_URL` | — | URL JDBC PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | — | Utilisateur PostgreSQL |
| `SPRING_DATASOURCE_PASSWORD` | — | Mot de passe PostgreSQL |
| `APP_JWT_SECRET` | — | Secret HS256 (256 bits minimum) pour signer les JWT |
| `SPRING_PROFILES_ACTIVE` | `dev` | Profil Spring actif |
| `SKIP_DEMO` | `false` | Si `true`, désactive le seed de données démo au démarrage |
| `SERVER_PORT` | `8080` | Port HTTP du backend |

## Migrations Flyway

Le backend applique automatiquement les **102 migrations Flyway** au démarrage
(V1 → V102). Aucune action manuelle requise.

Pour vérifier l'état des migrations en base :
```sql
SELECT version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;
```

## Tests

```bash
# Tous les tests d'intégration
./gradlew :app:test

# Tests d'un module spécifique
./gradlew :app:test --tests "jo.accountant.app.PurchaseOrdersSecurityIntegrationTest"

# Tests ArchUnit (règles architecturales)
./gradlew :app:test --tests "jo.accountant.app.ArchUnitTest"
```

## Comptes de démo (si SKIP_DEMO=false)

| Email | Mot de passe | Rôle | Entreprise |
|-------|--------------|------|------------|
| `demo@joaccountant.ht` | `Demo1234!2026` | OWNER | Retail Commerce Haïti |
| `admin@joaccountant.ht` | `Demo1234!2026` | ADMIN | ONG Humanitaire |
| `compta@joaccountant.ht` | `Demo1234!2026` | ACCOUNTANT | Professional Services |
| `viewer@joaccountant.ht` | `Demo1234!2026` | VIEWER | Free Zone Industry |

Les endpoints de login démo en un clic sont disponibles sur :
`POST /api/v1/demos/login/{demoCode}` (public, pas de mot de passe requis).
