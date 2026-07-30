# OPERATIONS — JOAccountant Backend v4.7

> Runbook d'exploitation — audit v4.7 §9.7 (Quick win)
>
> Ce document décrit les procédures opérationnelles pour déployer, monitorer, et récupérer
> JOAccountant Backend en production. Il doit être maintenu à jour à chaque évolution
> significative de l'infrastructure.

---

## 1. Architecture de déploiement

```
                    ┌──────────────────────────────────┐
                    │       Load Balancer (TLS)        │
                    │   (HAProxy / AWS ALB / Nginx)    │
                    └──────────────┬───────────────────┘
                                   │
                  ┌────────────────┼────────────────┐
                  │                │                │
            ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
            │  App #1   │   │  App #2   │   │  App #N   │
            │  :8080    │   │  :8080    │   │  :8080    │
            │  :8081    │   │  :8081    │   │  :8081    │
            │ (mgmt)    │   │ (mgmt)    │   │ (mgmt)    │
            └─────┬─────┘   └─────┬─────┘   └─────┬─────┘
                  │                │                │
                  └────────────────┼────────────────┘
                                   │
                          ┌────────▼─────────┐
                          │  PostgreSQL 16+  │
                          │   (Primary +     │
                          │    Read replica) │
                          └────────┬─────────┘
                                   │
                   ┌───────────────┼───────────────┐
                   │               │               │
             ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
             │  S3 / MinIO│   │ Prometheus │   │   Loki    │
             │ (PDF WORM) │   │  + Grafana │   │ (logs)    │
             └───────────┘   └───────────┘   └───────────┘
```

### Composants
- **App** : conteneur Docker (Dockerfile multi-stage), 3 replicas minimum en prod
- **PostgreSQL** : 16+, avec streaming replication + pgBackRest pour PITR
- **S3 / MinIO** : stockage PDF factures/bulletins (Object Lock mode Compliance, 10 ans)
- **Prometheus + Grafana** : scraping `/actuator/prometheus` (port 8081)
- **Loki / ELK** : ingestion des logs JSON (logback-spring.xml à ajouter)

---

## 2. Procédure de déploiement

### 2.1 Déploiement rolling (recommandé)

```bash
# 1. Build de l'image
docker build -t registry.example.com/joaccountant:v4.7.1 .

# 2. Push vers registry
docker push registry.example.com/joaccountant:v4.7.1

# 3. Mise à jour K8s (rolling update)
kubectl set image deployment/joaccountant \
  joaccountant=registry.example.com/joaccountant:v4.7.1 \
  --namespace=joaccountant-prod

# 4. Suivi du rollout
kubectl rollout status deployment/joaccountant -n joaccountant-prod

# 5. Vérification smoke test
curl -fsS https://api.joaccountant.com/actuator/health | jq .
```

### 2.2 Variables d'environnement obligatoires (production)

| Variable | Description | Exemple |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Profil Spring (doit être `prod`) | `prod` |
| `APP_JWT_SECRET` | Secret JWT (≥ 32 caractères aléatoires) | `$(openssl rand -base64 48)` |
| `DB_URL` | URL JDBC PostgreSQL | `jdbc:postgresql://pg-primary:5432/joaccountant` |
| `DB_USERNAME` | User DB | `joaccountant_app` |
| `DB_PASSWORD` | Password DB (depuis Vault/KMS) | `********` |
| `CORS_ALLOWED_ORIGINS` | Liste origines CORS (jamais `*` en prod) | `https://app.joaccountant.com` |
| `APP_STORAGE_ROOT` | Chemin volume persistent OU configurer S3 | `/var/lib/joaccountant/storage` |
| `HIKARI_MAX_POOL_SIZE` | Taille pool (défaut 30) | `50` |

### 2.3 Critères de décision rollback automatique

- Erreur 5xx > 5% pendant 5 min après déploiement
- Latence P99 > 3s sur endpoints lecture
- `/actuator/health` → `DOWN` pendant > 30s

---

## 3. Procédure de rollback

### 3.1 Rollback application

```bash
# K8s rollout undo (revient au tag N-1)
kubectl rollout undo deployment/joaccountant -n joaccountant-prod

# Ou redéploiement explicite du tag précédent
kubectl set image deployment/joaccountant \
  joaccountant=registry.example.com/joaccountant:v4.7.0 \
  -n joaccountant-prod
```

### 3.2 Rollback base de données (Flyway)

> ⚠️ **Flyway Community n'a pas d'undo.** Le rollback DB nécessite `pg_restore` du backup
> pré-migration, puis `flyway repair` pour réaligner `flyway_schema_history`.

```bash
# 1. Stopper l'app (évite nouvelles écritures)
kubectl scale deployment/joaccountant --replicas=0 -n joaccountant-prod

# 2. Restaurer le backup pré-migration
pgbackrest --stanza=joaccountant --type=time \
  --target="2026-07-26 10:00:00+00" restore

# 3. Réaligner Flyway (NE JAMAIS éditer flyway_schema_history manuellement)
flyway -url=$DB_URL -user=$DB_USERNAME -password=$DB_PASSWORD repair

# 4. Redémarrer l'app
kubectl scale deployment/joaccountant --replicas=3 -n joaccountant-prod
```

### 3.3 Politique de migrations cassantes

- **Toute migration doit être additive** (pas de `DROP COLUMN/TABLE` direct)
- Pour supprimer : V_n renomme (expand), V_n+1 supprime après stabilization (contract)
- Pour un `UPDATE` massif > 10k lignes : batcher par 10k avec sleep, hors transaction Flyway

---

## 4. Backup & Disaster Recovery

### 4.1 Stratégie de backup PostgreSQL

| Type | Fréquence | Rétention | Stockage |
|------|-----------|-----------|----------|
| Full backup (pg_basebackup) | Quotidien 02:00 | 7 daily | S3 cross-region chiffré |
| WAL streaming | Continu | 30 jours | S3 cross-region chiffré |
| Weekly snapshot | Dimanche 03:00 | 4 weekly | S3 cold storage |
| Monthly snapshot | 1er du mois 04:00 | 12 monthly | S3 cold storage |
| Yearly snapshot | 1er janvier 05:00 | 10 yearly (fiscal FR) | S3 Glacier Deep Archive |

### 4.2 RPO / RTO cibles

- **RPO** (Recovery Point Objective) : < 5 minutes (via WAL streaming)
- **RTO** (Recovery Time Objective) : < 1 heure (pgBackRest restore)

### 4.3 Test de restauration mensuel (obligatoire)

> Un backup non testé = pas de backup (Gartner : 30% des backups entreprise sont corrompus).

```bash
# Script automatisé à exécuter le 1er samedi du mois
./scripts/test-restore.sh
# → restaure le dernier backup sur instance staging
# → exécute un smoke test (CRUD user + écriture comptable)
# → alerte Slack si échec
```

### 4.4 Conservation PDF fiscaux (10 ans — LPF art. L102B)

- Stockage : **S3 Object Lock mode Compliance** (impossible à supprimer, même par root)
- Horodatage : à ajouter — service TSA qualifié eIDAS (Chronodoc/Universign)
- Signature : à ajouter — PAdES-B-LT (eIDAS qualifié)

---

## 5. Procédure d'onboarding tenant

```bash
# 1. L'OWNER s'inscrit via POST /api/v1/auth/register
# 2. L'OWNER complète le wizard 1-9 via POST /api/v1/companies
# 3. Le plan comptable sectoriel est auto-généré (SectorAccountTemplate)
# 4. L'OWNER invite ses collaborateurs via POST /api/v1/companies/{id}/users

# Vérification post-onboarding :
curl -fsS -H "Authorization: Bearer $TOKEN" \
  https://api.joaccountant.com/api/v1/companies/$COMPANY_ID \
  | jq '.modules | length'  # doit être > 0
```

---

## 6. Procédure d'offboarding tenant (RGPD)

> Conflit entre droit à l'oubli (RGPD) et obligation fiscale (10 ans rétention).

### 6.1 Suppression utilisateur (RGPD)

```sql
-- L'utilisateur demande la suppression de son compte
-- Cascade : refresh_token, password_reset_token, user_company_role supprimés
-- Audit log et écritures comptables CONSERVÉS (obligation fiscale)
DELETE FROM users WHERE id = $1;

-- Pseudonymisation des références dans audit_log et journal_entry
UPDATE audit_log SET actor_user_id = '00000000-0000-0000-0000-000000000000'
  WHERE actor_user_id = $1;
UPDATE journal_entry SET created_by = '00000000-0000-0000-0000-000000000000',
                         updated_by = '00000000-0000-0000-0000-000000000000'
  WHERE created_by = $1 OR updated_by = $1;
```

### 6.2 Suppression tenant complète

⚠️ À n'exécuter qu'après validation juridique et expiration du délai de rétention fiscal.

---

## 7. Rotation des secrets

### 7.1 Rotation JWT secret

```bash
# 1. Générer un nouveau secret
NEW_SECRET=$(openssl rand -base64 48)

# 2. Mettre à jour Vault/AWS Secrets Manager
vault kv patch secret/joaccountant/prod APP_JWT_SECRET="$NEW_SECRET"

# 3. Redémarrer les pods (rolling)
kubectl rollout restart deployment/joaccountant -n joaccountant-prod

# ⚠️ Tous les JWT en circulation (access token 15min + refresh 30j) sont invalidés.
# Les utilisateurs doivent se reconnecter.
```

### 7.2 Rotation password DB

```sql
ALTER USER joaccountant_app WITH PASSWORD '$NEW_PASSWORD';
-- Mettre à jour Vault + redémarrer les pods
```

---

## 8. Runbooks par symptôme

### 8.1 Latence P99 > 5s sur endpoints lecture

1. Vérifier `/actuator/metrics/hikaricp.connections.pending` — si > 0, saturation pool
2. Vérifier `/actuator/metrics/jvm.threads.live` — si > 500, pic de charge
3. Logs : `kubectl logs -l app=joaccountant | grep "slow query"`
4. Action : augmenter `HIKARI_MAX_POOL_SIZE` (max 100 pour 1 instance PG)
5. Si récurrent : ajouter index manquants (audit v4.7 §7.3)

### 8.2 Erreurs 5xx > 1%

1. Récupérer un `correlationId` d'erreur dans les logs
2. `kubectl logs -l app=joaccountant | grep $CORRELATION_ID`
3. Si `DataIntegrityViolationException` : contrainte DB violée, probablement idempotency key
4. Si `OptimisticLockException` : contention, 2 utilisateurs modifient la même entité
5. Si `IllegalStateException: JWT secret` : rotation de secret en cours, patienter

### 8.3 `audit_log` > 1M lignes/jour

1. Vérifier qu'il n'y a pas de boucle d'audit (event qui se déclenche en boucle)
2. Activer le partitionnement mensuel : `PARTITION BY RANGE (occurred_at)`
3. Archiver les partitions > 1 an vers S3 cold storage

### 8.4 Pool DB saturé

```bash
# Vérifier le nombre de connexions actives PostgreSQL
psql -c "SELECT count(*) FROM pg_stat_activity WHERE datname='joaccountant';"

# Si > 80% du max_connections (100 défaut), investiguer :
# - Requêtes longues : SELECT * FROM pg_stat_activity WHERE state='active' AND query_start < now() - interval '30s';
# - Locks : SELECT * FROM pg_locks WHERE NOT granted;
```

---

## 9. Contacts on-call

| Rôle | Nom | Contact | Escalade |
|------|-----|---------|----------|
| On-call L1 | À compléter | Slack `#oncall-joaccountant` | 24/7 |
| On-call L2 | À compléter | PagerDuty `joaccountant-l2` | 24/7 |
| DBA | À compléter | Slack `#dba` | Heures ouvrées |
| Security | À compléter | `security@joaccountant.com` | 24/7 pour incident |

---

## 10. Changelog ops

| Date | Auteur | Changement |
|------|--------|------------|
| 2026-07-26 | Z.ai (audit v4.7) | Création initiale du runbook |

---

## 11. À implémenter (roadmap)

Référence : audit v4.7 §12 (Roadmap 3 / 6 / 12 mois).

- [ ] micrometer-registry-prometheus + dashboards Grafana
- [ ] logback-spring.xml JSON + ingestion Loki
- [ ] Sentry pour error tracking
- [ ] OpenTelemetry + Tempo pour tracing distribué
- [ ] Bucket4j Redis pour rate-limit distribué
- [ ] ShedLock pour `ScheduledAlertsConfig`
- [ ] S3FileStorageAdapter (remplacer FileSystemFileStorageAdapter en prod)
- [ ] Helm chart + HPA + PDB + ServiceMonitor
- [ ] HashiCorp Vault ou AWS Secrets Manager
- [ ] pgBackRest + PITR + test de restauration mensuel automatisé
- [ ] Spring Batch pour paie mensuelle + clôture annuelle
- [ ] PIT mutation testing
- [ ] Gatling benchmark + SLO définis
