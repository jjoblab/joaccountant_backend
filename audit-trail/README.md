# Module : audit-trail

> Journal d'audit append-only qui persiste asynchroniquement chaque action métier significative.

## Rôle du module

Le module `:audit-trail` est un consommateur passif d'événements d'audit. Il **n'est jamais
appelé directement** par aucun contrôleur ; il écoute les `AuditEvent` publiés par les modules
métier (via `ApplicationEventPublisher`) et les persiste dans la table `audit_log`.

Il est **always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`).
Il ne dépend d'aucun référentiel comptable et fonctionne pour les 6 frameworks.

Sa particularité architecturale : `AuditLog` est la **seule entité du projet qui n'est PAS une
`TenantAwareEntity`**. `company_id` y est une simple colonne nullable, parce que les lignes
d'audit doivent survivre même si le tenant qui les a produites est supprimé plus tard
(§3.6 — exception sanctionnée).

## Ce qu'il fait précisément

### Entités principales

- `AuditLog` — ligne du journal d'audit. Colonnes : `id` (UUID), `companyId` (nullable),
  `actorUserId`, `entityType` (ex. `Company`, `JournalEntry`), `entityId`, `action`
  (ex. `CREATE`, `UPDATE`, `POST`, `VOID`), `oldValueJson`/`newValueJson` (JSONB),
  `occurredAt`, `correlationId`, `version`.

### Règles métier clés

1. **Persistance asynchrone après commit** — le listener utilise
   `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`. Une transaction métier
   échouée ne pollue donc jamais le journal d'audit avec des lignes fantômes.
2. **Échec d'audit non bloquant** — si la persistance de l'`AuditLog` échoue, la transaction
   métier d'origine n'est PAS rollbackée (l'audit est de l'observabilité, pas de la cohérence).
   L'échec est loggé au niveau ERROR avec le `correlationId` pour replay manuel.
3. **Pas de lecture exposée** — le module ne fournit aucun endpoint de consultation du journal
   d'audit. L'accès se fait par requête SQL directe sur `audit_log` (outils d'investigation).
4. **`AuditableAction` (interface marqueur)** — un module métier qui veut publier un événement
   d'audit typé peut implémenter cette interface ; le listener `onAuditableAction` le
   convertit en `AuditEvent` et le persiste via le même chemin.

### Cycle de vie des objets

Une `AuditLog` est immuable après insertion — il n'y a ni update ni delete exposé.

## Endpoints exposés

Ce module n'expose pas d'endpoint REST direct — il est consommé par d'autres modules via
injection de service (en réalité via `ApplicationEventPublisher.publishEvent(new AuditEvent(...))`
ou `publishEvent(eventImplementingAuditableAction)`).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `AuditEvent`, `AuditableAction`, et c'est tout. Le module est intentionnellement
  minimal pour ne pas créer de couplage.

### Modules qui dépendent de celui-ci

Aucun ne dépend directement de `:audit-trail`. La dépendance est inversée : ce sont les
modules métier qui **publient** des `AuditEvent` (ou des événements implémentant
`AuditableAction`) que `:audit-trail` consomme.

### Événements publiés / consommés

- **Publie** : aucun.
- **Consomme** : `AuditEvent` (record du `:core`) et tout `AuditableAction` (interface
  marqueur du `:core`). Écoute après commit, asynchroniquement.

## Tables / migrations Flyway

- `core/src/main/resources/db/migration/V2__core_audit_log.sql` — table `audit_log` et
  3 index : `(company_id, entity_type, entity_id)`, `occurred_at`, `correlation_id`. La table
  est créée dans le module `:core` (pas dans `:audit-trail`) car la migration V2 est
  antérieure à la séparation des modules dans le cycle de build.

## Points d'attention (hérités de l'audit)

- ⚠️ **Aucun endpoint de consultation** — le journal d'audit n'est pas exposé via l'API. Le
  client mobile ne peut PAS afficher l'historique d'audit d'une entité. L'investigation se fait
  côté serveur par requête SQL directe. Si l'UI mobile a besoin d'un fil d'audit, il faudra
  ajouter un endpoint `GET /audit-log?entityType=...&entityId=...` (backlog).
- ⚠️ **Pas d'auth sur la lecture** — N/A puisque pas d'endpoint de lecture.
- ⚠️ **`AuditLog.companyId` nullable** — par conception (survie à la suppression du tenant).
  Toute requête d'agrégation doit gérer ce cas.

## Tests

Aucun test dans `:audit-trail/src/test`. Le mécanisme est exercé indirectement par les tests
d'intégration de `:app` qui vérifient que les actions métier déclenchent bien la persistance
d'`AuditLog` (vérification via select sur la table en base).
