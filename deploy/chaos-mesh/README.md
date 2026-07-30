# Chaos Mesh — JOAccountant Backend

R-38 (lot-F-ops-docs) — Expériences chaos engineering pour valider la résilience du backend en production.

## Prérequis

- Cluster Kubernetes ≥ 1.24
- Chaos Mesh ≥ 2.6 installé : `kubectl apply -f https://mirrors.chaos-mesh.org/v2.6.0/chaos-mesh.yaml`
- Namespace `joaccountant` déployé avec le Helm chart

## Expériences disponibles

| Fichier | Type | Objectif | Durée |
|---------|------|----------|-------|
| `pod-kill.yaml` | PodChaos | Valider que le HPA recrée les pods tués | 30s |
| `network-latency-db.yaml` | NetworkChaos | Valider résilience latence DB (200ms ajouté) | 60s |
| `network-partition.yaml` | NetworkChaos | Valider circuit breaker Redis/PG | 30s |
| `cpu-stress.yaml` | StressChaos | Valider HPA scale-up sur CPU burn | 60s |
| `dns-error.yaml` | DNSChaos | Valider fallback cache Caffeine | 30s |

## Lancement

```bash
# Lancer une expérience
kubectl apply -f deploy/chaos-mesh/pod-kill.yaml -n joaccountant

# Suivre l'exécution
kubectl describe chaosengine pod-kill -n joaccountant

# Nettoyer
kubectl delete -f deploy/chaos-mesh/pod-kill.yaml -n joaccountant
```

## Calendrier recommandé

- **Hebdomadaire** (jeudi 14h, hors heures de pointe) : 1 expérience rotative
- **Game day trimestriel** : exécuter toutes les expériences en cascade pour valider la résilience globale
- **Avant chaque release majeure** : exécuter `pod-kill.yaml` + `network-latency-db.yaml`

## Métriques à surveiller pendant les expériences

- `up{job="joaccountant"}` — doit rester ≥ 1 pod
- `http_server_requests_seconds_count{status=~"5.."}` — doit rester < 1% sur la durée
- `hikaricp_connections_pending` — doit revenir à 0 après la fin de l'expérience
- `joaccountant_audit_persistence_failed_total` — doit rester à 0

## Runbook post-incident

Si une expérience provoque une dégradation non prévue :
1. `kubectl delete chaosengine <name> -n joaccountant` (arrête l'expérience immédiatement)
2. Collecter les métriques Prometheus sur la fenêtre de l'expérience
3. Documenter le comportement observé dans `docs/chaos-engineering-results.md`
4. Ouvrir un ticket pour corriger la résilience (circuit breaker, retry, fallback)

## Limitations

- Les expériences ne s'exécutent qu'en namespace `joaccountant` (sécurité)
- `pod-kill.yaml` ne tue qu'un seul pod à la fois (PDB minAvailable=1 respecté)
- Les expériences NetworkChaos ne ciblent que le trafic TCP (pas UDP DNS — utiliser DNSChaos séparément)
