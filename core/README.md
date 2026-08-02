# Module : core

> Socle transverse du projet JOAccountant — référentiels, multi-tenant, exceptions, ports et sécurité partagée.

## Rôle du module

Le module `:core` est le socle commun de tous les autres modules. Il est **always-on** (activé
pour tous les secteurs via `BusinessTypeModuleService.alwaysOnModules()` — implicitement, car tous les
modules en dépendent). Il ne porte aucun secteur métier et ne sait rien de la comptabilité ; il
fournit uniquement des briques techniques partagées.

Il définit notamment :

- les **données de référence** des 6 référentiels comptables supportés (`IFRS_FULL`,
  `IFRS_SME`, `SYSCOHADA_REVISED`, `PCG_FRANCE`, `PCN_HAITI`, `PCGR_CANADA`) via l'entité
  `AccountingFramework` et la migration `V3__core_seeds.sql` ;
- les **devises ISO 4217** (`Currency` — HTG/USD/EUR/XOF/XAF/CAD/JPY) avec leur nombre de
  décimales (`decimals`) qui devrait piloter tous les arrondis (voir point d'attention M14) ;
- le **multi-tenant** via `TenantAwareEntity`, `TenantContext` (ThreadLocal), un
  `EntityListener` qui stamp `company_id` et un `CurrentTenantIdentifierResolver` Hibernate ;
- les **exceptions métier** standardisées et le `GlobalExceptionHandler` qui les mappe en
  `ProblemDetail` (RFC 7807) avec `code`, `correlationId`, `companyId`, `timestamp` ;
- les **ports** sortants (`NotificationChannelPort`, `ApproverEmailResolverPort`,
  `FileStoragePort`) qui permettent aux modules métier de dépendre d'abstractions sans boucler
  sur `:auth`, `:notifications` ou un SDK de stockage ;
- un **vérificateur de rôle JWT** (`RoleChecker`) qui lit le claim `companies` du jeton.

## Ce qu'il fait précisément

### Entités principales

- `AccountingFramework` — référentiel comptable seed-only (NON TenantAware). Porte `code`,
  `numberingMode` (FREE/MANDATED), `label`, `mandatedClassSeedJson` (JSON des classes niveau 1)
  et `mandatoryStatements`.
- `Currency` — devise ISO 4217 seed-only. `code` (PK, ex. `HTG`), `label`, `decimals` (0 pour
  XOF/XAF/JPY, 2 pour HTG/USD/EUR/CAD).
- `ExchangeRate` — taux de change par `(companyId, fromCurrency, toCurrency, asOfDate)`.
- `TenantAwareEntity` (abstract, `@MappedSuperclass`) — `id` (UUID v7), `companyId`,
  `createdAt`/`updatedAt`, `createdBy`/`updatedBy`, `version` (`@Version`).

### Composants clés

- `TenantContext` — ThreadLocal portant `companyId`, `userId`, `correlationId`.
- `TenantAwareEntityListener` — stamp `company_id` sur chaque entité à l'insert.
- `CurrentTenantIdentifierResolverImpl` — borne les requêtes Hibernate au tenant courant.
- `RoleChecker.ensureRole(companyId, minimumRole)` — vérifie le rôle dans le JWT ;
  ordre `OWNER > ADMIN > ACCOUNTANT > BOOKKEEPER > VIEWER > AUDITOR` ; lève 403 sinon.
- `GlobalExceptionHandler` — mappe `NotFoundException`→404, `ConflictException`→409,
  `ValidationException`→422, `ForbiddenException`→403, et toute autre `Exception`→500 avec
  log + `correlationId` dans le `ProblemDetail`.
- `ExchangeRateService.convert(...)` — recherche taux direct puis inverse, conversion en
  `setScale(4, HALF_UP)` (voir point d'attention M14).
- `AuditEvent` (record) + `AuditableAction` (interface marqueur) — événements d'audit
  publiés par les modules métier, consommés par `:audit-trail`.

### Règles métier clés

1. `company_id` n'est **jamais** accepté dans le corps d'une requête HTTP ; il est injecté
   par le `TenantContextFilter` depuis le claim JWT.
2. Toute entité métier hérite de `TenantAwareEntity` — sauf `AuditLog`, `AccountingFramework`,
   `Currency`, `UserCompanyRole` (qui doivent survivre à la suppression d'un tenant ou être
   queryable hors contexte tenant).
3. Une erreur métier prévisible ne renvoie **jamais** 500 — elle lève une sous-classe de
   `BusinessException` mappée par `GlobalExceptionHandler`.
4. Les 6 référentiels sont seedés en V3 et ne sont pas éditables par les utilisateurs.

### Cycle de vie des objets

Pas de cycle de vie propre (entités de référence et composants techniques).

## Endpoints exposés

Ce module n'expose pas d'endpoint REST direct — il est consommé par d'autres modules via
injection de service (`RoleChecker`, `ExchangeRateService`, `AccountingFrameworkRepository`,
`CurrencyRepository`) et via l'infrastructure Spring (`TenantContextFilter`,
`GlobalExceptionHandler`, `CurrentTenantIdentifierResolverImpl`).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

Aucune dépendance métier — `:core` ne dépend que de Spring Boot, Spring Security, Hibernate et
PostgreSQL. C'est la racine du graphe de modules.

### Modules qui dépendent de celui-ci

Tous les autres modules (`:auth`, `:company`, `:audit-trail`, `:chart-of-accounts`,
`:accounting-engine`, `:invoicing`, `:fixed-assets`, `:inventory`, `:funds-grants`,
`:time-billing`, `:tax`, `:third-parties`, `:bank-reconciliation`, `:notifications`,
`:reporting`, `:financial-statements`, `:approval-workflow`, `:analytics`,
`:document-numbering`, `:document-generation`, `:app`).

### Événements publiés / consommés

- **Publie** : `AuditEvent` (record) — publié par les modules métier, pas par `:core` lui-même.
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V2__core_audit_log.sql` — table `audit_log` (la SEULE
  entité du projet qui n'est PAS `TenantAwareEntity` ; `company_id` est nullable pour survivre
  à la suppression d'un tenant).
- `src/main/resources/db/migration/V3__core_seeds.sql` — création des tables
  `accounting_framework` et `currency`, et seed des 6 référentiels + 7 devises.
- `src/main/resources/db/migration/V42__exchange_rate.sql` — table `exchange_rate`
  (Vague 2 item 2.5 — multi-devises actif). Contrainte unique sur
  `(company_id, from_currency, to_currency, as_of_date)`.

## Points d'attention (hérités de l'audit)

- ⚠️ **Arrondis codés en dur à 4 décimales** dans `ExchangeRateService.convert` (lignes 74 et
  83 — `setScale(4, HALF_UP)`). Pour une devise 0-décimales (XOF/XAF/JPY), le montant converti
  est stocké avec 4 décimales au lieu de 0. `Currency.decimals` existe mais n'est jamais lu
  pour piloter l'échelle (audit M14). Le client mobile doit adapter l'affichage.
- ⚠️ **`RoleChecker` existe mais n'est pas appelé** sur la grande majorité des endpoints
  (audit B5 / A-4) — 100/104 endpoints n'ont aucune garde de rôle. Un `VIEWER` peut
  techniquement créer des écritures, factures, etc. jusqu'à correction.
- ⚠️ **`ApproverEmailResolverPort` défini mais rarement utilisé** — les modules métier qui
  déclenchent des écritures passent `approverEmails = List.of()` au moteur, ce qui désactive
  silencieusement le workflow 4-yeux pour les écritures automatiques (audit M12).
- ⚠️ **`NotificationChannelPort`** est l'unique point d'entrée pour l'envoi d'email — tout
  envoi direct via un SDK depuis un module métier est un anti-pattern (§3.12).

## Tests

Aucun test d'intégration dans `:core/src/test` — le module est couvert indirectement par les
tests d'intégration globaux dans `:app/src/test` (notamment `Phase1IntegrationTest` qui
exerce le flux register → create company → complete wizard).
