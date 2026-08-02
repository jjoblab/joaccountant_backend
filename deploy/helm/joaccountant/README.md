# JOAccountant Backend — Helm Chart

Chart Helm v1.0.0 pour le déploiement Kubernetes de JOAccountant Backend v5.2 (Spring Boot).
Finding #22 — Helm chart.

## Contenu

| Ressource | Description |
|-----------|-------------|
| `Deployment` | Pod Spring Boot stateless, 2 containers initiaux (pilotés par HPA). |
| `Service` (ClusterIP) | Expose le port 8080 (API + Swagger UI) sur le réseau interne. |
| `HorizontalPodAutoscaler` | Autoscaling CPU 70%, 2→10 replicas, scale-up agressif / scale-down conservateur. |
| `ConfigMap` | Variables d'environnement non-sensibles (`DB_URL`, `DB_USERNAME`, `JAVA_OPTS`, ...). |
| `Secret` | `DB_PASSWORD` + `APP_JWT_SECRET` (chart-managed, ou réutilise un secret externe). |
| `PersistentVolumeClaim` | Volume pour `APP_STORAGE_ROOT` (20 Gi par défaut). |

> **Note** : le port 8081 (Actuator / Prometheus / health probes) n'est **pas** exposé via le Service — il est scrapé directement par le Prometheus Operator via `podAnnotations`.

## Pré-requis

- Kubernetes ≥ 1.24
- Helm ≥ 3.10
- PostgreSQL 14+ déployé dans le cluster (ex: chart Bitnami `postgresql`)
- Une image Docker `joaccountant-backend:v5.2.0` publiée dans un registry accessible
- (Optionnel) `External Secrets Operator` ou `Sealed Secrets` pour gérer `DB_PASSWORD` +
  `APP_JWT_SECRET` + `APP_MFA_ENCRYPTION_KEY` en production

## Installation

### 1. Préparer les secrets

```bash
# Générer un JWT secret (min 256 bits — voir JwtService.@PostConstruct)
JWT_SECRET=$(openssl rand -base64 48)
DB_PASSWORD=$(openssl rand -base64 24)
```

### 2. Créer un fichier `my-values.yaml` par environnement

```yaml
image:
  repository: ghcr.io/joaccountant/joaccountant-backend
  tag: "5.2.0"

env:
  SPRING_PROFILES_ACTIVE: prod
  DB_URL: jdbc:postgresql://joaccountant-postgresql:5432/joaccountant
  DB_USERNAME: joaccountant
  CORS_ALLOWED_ORIGINS: https://app.joaccountant.com,https://admin.joaccountant.com
  APP_STORAGE_BACKEND: s3
  AWS_S3_BUCKET: joaccountant-prod-storage
  AWS_S3_REGION: eu-west-3
  # OpenTelemetry tracing (audit v4.7 §9.3) — OTLP collector sidecar
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
  OTEL_EXPORTER_OTLP_PROTOCOL: grpc
  OTEL_SERVICE_NAME: joaccountant
  OTEL_TRACES_SAMPLER_ARG: "0.1"   # 10% sampling
  # JWT RS256 (audit v4.7 §6.3) — optionnel, défaut HS256 si non positionné
  APP_JWT_ALGORITHM: RS256
  # MFA TOTP (audit v4.7 §6.3) — clé AES-256-GCM pour chiffrer les secrets MFA en base
  APP_MFA_ENCRYPTION_KEY: "<32-char-secret-from-Vault>"

secrets:
  dbPassword: "<your-db-password>"
  jwtSecret: "<your-jwt-secret-at-least-256-bits-long>"
  # Audit v4.7 §6.3 — clé de chiffrement AES-256-GCM pour les secrets TOTP MFA (V52)
  mfaEncryptionKey: "<32-char-secret-from-Vault>"

persistence:
  enabled: false   # S3 backend — pas besoin de PVC
```

### 3. Déployer PostgreSQL (chart Bitnami)

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install joaccountant-postgresql bitnami/postgresql \
  --set auth.username=joaccountant \
  --set auth.password=<your-db-password> \
  --set auth.database=joaccountant \
  --set primary.persistence.size=20Gi
```

### 4. Installer le chart JOAccountant

```bash
# Depuis la racine du repo
helm install joaccountant ./deploy/helm/joaccountant \
  --namespace joaccountant --create-namespace \
  --values my-values.yaml
```

### 5. Vérifier

```bash
kubectl -n joaccountant get pods,svc,hpa
kubectl -n joaccountant logs -f deployment/joaccountant
```

## Sécurité

### Secrets (DB_PASSWORD + APP_JWT_SECRET)

Le chart supporte deux modes :

**Mode A — chart-managed (dev/staging)**

```bash
helm install joaccountant ./deploy/helm/joaccountant \
  --set secrets.dbPassword=... \
  --set secrets.jwtSecret=...
```

⚠️ Les secrets sont alors stockés en base64 dans le Secret K8s (pas chiffrés au repos par défaut — activer `etcd encryption at rest` côté API server).

**Mode B — external secret (production, recommandé)**

```yaml
secrets:
  existingSecret: "joaccountant-secrets"  # géré par External Secrets Operator / Vault / Sealed Secrets
```

Le secret externe doit contenir les clés `DB_PASSWORD` et `APP_JWT_SECRET` (overridable via `secrets.keys`).

### Pod Security

- `runAsNonRoot: true` + `runAsUser: 1000` (user Dockerfile non-root).
- `allowPrivilegeEscalation: false` + `capabilities.drop: ["ALL"]` (seccomp default).
- `readOnlyRootFilesystem: false` (uniquement pour permettre les heapdumps dans `/tmp`).

## Autoscaling (HPA)

| Paramètre | Défaut | Description |
|-----------|--------|-------------|
| `autoscaling.enabled` | `true` | Active le HPA. |
| `autoscaling.minReplicas` | `2` | Floor — toujours ≥ 2 pour HA. |
| `autoscaling.maxReplicas` | `10` | Ceiling — borne le coût. |
| `autoscaling.targetCPUUtilizationPercentage` | `70` | Seuil CPU moyen avant scale-up. |
| `autoscaling.behavior.scaleDown.stabilizationWindowSeconds` | `300` | Évite le thrashing (5 min). |

## Upgrade / Rollback

```bash
# Upgrade avec nouvelle version d'image
helm upgrade joaccountant ./deploy/helm/joaccountant \
  --namespace joaccountant \
  --values my-values.yaml \
  --set image.tag=5.2.1

# Rollback en cas de souci
helm history joaccountant -n joaccountant
helm rollback joaccountant <REVISION> -n joaccountant
```

La stratégie `RollingUpdate` avec `maxSurge=1, maxUnavailable=0` garantit le **zero-downtime** (audit v4.7 §9 Finding #5 — graceful shutdown 30s).

## Schémas DB additionnels (v5.x)

Le backend v5.2 introduit 3 schémas DB additionnels à considérer pour le sizing :

- **Spring Batch** (V63) — tables `BATCH_*` (8 tables : job_instance, job_execution,
  job_execution_params, step_execution, step_execution_context, job_execution_context).
  Volume attendu : 1 ligne par exécution de Job (lancés via `BatchController`). Pour 1 an
  d'exploitation avec ~12 campagnes de paie + 4 clôtures d'exercice : ~16 lignes. Négligeable.
- **RLS PostgreSQL** (V62) — `ENABLE ROW LEVEL SECURITY` + `FORCE` sur 6 tables financières.
  Pas de table additionnelle — la policy est attachée à la table existante. Aucun impact
  sizing, mais le rôle Flyway doit disposer de `BYPASSRLS`.
- **MFA** (V52) — table `mfa_secret` (1 ligne par utilisateur ayant activé la MFA).
  Volume attendu : ~10-100 lignes pour une PME. Négligeable.

## Secrets additionnels (v5.x)

Outre `DB_PASSWORD` et `APP_JWT_SECRET`, le chart doit fournir (via ConfigMap/Secret) :

- `APP_MFA_ENCRYPTION_KEY` (V52) — clé AES-256-GCM (32 caractères) pour chiffrer les
  secrets TOTP MFA en base. **À externaliser dans Vault/KMS en prod** (ne JAMAIS committer
  en clair dans le chart). Si non positionné, fallback sur une valeur dev-only
  (`dev-only-mfa-key-please-override-32-chars`) — refuser le démarrage en profil prod.
- `APP_JWT_ALGORITHM` (audit v4.7 §6.3) — `HS256` (défaut) ou `RS256`. Si `RS256` :
  - `APP_JWT_RSA_PRIVATE_KEY_PATH` — chemin vers la clé privée RSA (signature).
  - `APP_JWT_RSA_PUBLIC_KEY_PATH` — chemin vers la clé publique RSA (vérification + JWKS).
  - `APP_JWT_RSA_KEY_ID` — identifiant de la clé (exposé dans le JWKS `kid`).
  Monter les clés via un `Secret` K8s monté en volume read-only.

## Désinstallation

```bash
helm uninstall joaccountant -n joaccountant
# Les PVC ne sont pas supprimés automatiquement — nettoyer manuellement si besoin :
kubectl -n joaccountant delete pvc -l app.kubernetes.io/name=joaccountant
```

## Values

Voir [`values.yaml`](./values.yaml) pour la liste exhaustive. Principaux axes :
- `image` — repository, tag, pullPolicy, pullSecrets
- `replicaCount` — floor (HPA pilote le scaling réel)
- `resources` — requests/limits CPU + memory
- `probes` — liveness / readiness / startup (Actuator port 8081)
- `env` — toutes les variables d'environnement non-sensibles
- `secrets` — chart-managed ou existingSecret
- `persistence` — PVC pour `APP_STORAGE_ROOT`
- `autoscaling` — HPA CPU 70%, 2→10 replicas
- `podAnnotations` — Prometheus scrape, etc.
