# SLO — Service Level Objectives

## JOAccountant Backend — SLO officiels

Audit §9.2 (Lot E) — SLO formalisés pour la production.

## 1. Disponibilité

| SLO | Cible | Fenêtre | Alerte |
|-----|-------|---------|--------|
| Disponibilité API | 99.9% | 30 jours rolling | Critique si < 99.5% sur 1h |
| Pods up | 100% (≥ 2 replicas) | temps réel | Critique si 0 pod up pendant 2 min |

## 2. Latence

| Endpoint | P95 | P99 | Alerte |
|----------|-----|-----|--------|
| `GET /api/v1/companies/{id}/dashboard` | < 200 ms | < 500 ms | Warning si P99 > 1s sur 5 min |
| `GET /api/v1/companies/{id}/journal-entries/paged` | < 300 ms | < 800 ms | Warning si P99 > 2s sur 5 min |
| `POST /api/v1/companies/{id}/invoices` | < 500 ms | < 1.5 s | Warning si P99 > 3s sur 5 min |
| `GET /api/v1/companies/{id}/financial-statements/balance-sheet` | < 1 s | < 2 s | Warning si P99 > 5s sur 5 min |
| `GET /api/v1/companies/{id}/audit-trail` (paginé) | < 500 ms | < 1.5 s | Warning si P99 > 3s sur 5 min |

## 3. Taux d'erreur

| SLO | Cible | Alerte |
|-----|-------|--------|
| Taux d'erreur 5xx global | < 0.1% | Critique si > 1% sur 5 min |
| Taux d'erreur 4xx (validation) | < 5% | Warning si > 10% (problème API/UX) |

## 4. Ressources

| Ressource | Seuil warning | Seuil critique |
|-----------|---------------|----------------|
| HikariCP pool | > 70% utilisé pendant 5 min | > 90% ou pending > 0 pendant 2 min |
| JVM Heap | > 75% pendant 10 min | > 90% ou OOM imminent |
| CPU pod | > 70% pendant 10 min | > 95% pendant 5 min (HPA déclenchement) |
| Connexions DB PostgreSQL | > 70% max_connections | > 90% max_connections |

## 5. Audit & conformité

| SLO | Cible | Alerte |
|-----|-------|--------|
| Persistance audit_log | 100% (aucun échec) | Critique si `audit_persistence_failed_total` > 0 |
| Délai de persistance audit | < 1s après commit | Warning si > 5s sur 5 min |
| Backup PITR | RPO < 5 min, RTO < 1h | Critique si backup échec ou restore test mensuel échec |

## 6. Business metrics

| Métrique | Cible | Note |
|----------|-------|------|
| Factures générées / jour | variable | Alerte si chute > 50% vs J-7 (bug utilisateur massif) |
| Déclarations DGI générées / mois | variable | Surveillance adoption fonctionnalité fiscale Haïti |

## Error budget

Avec un SLO disponibilité 99.9% sur 30 jours :
- Budget d'erreur = 30 × 24 × 60 × 0.001 = **43.2 minutes / mois** de downtime autorisé
- Si budget épuisé avant fin du mois : gel des déploiements non urgentS
