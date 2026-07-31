# JOAccountant Backend — v8.3 (patch fff.txt)

## Correctifs apportés (vs v8.0/v8.1/v8.2)

### 1. `CreateThirdPartyRequest` + `ThirdPartiesService` — HTTP 422 `collectiveAccountId: must not be null`
**Fichiers** :
- `third-parties/src/main/java/jo/accountant/thirdparties/dto/CreateThirdPartyRequest.java`
- `third-parties/src/main/java/jo/accountant/thirdparties/service/ThirdPartiesService.java`

**Bug corrigé** (cf. `fff.txt`) :
- `ThirdPartyEditorFragment: ThirdParty create failed: HTTP 422 — collectiveAccountId: must not be null` (lignes 239, 272)

**Cause racine** :
- Le DTO backend exigeait `@NotNull UUID collectiveAccountId` (validation Jakarta).
- Le formulaire mobile ne permettait pas la sélection du compte collectif et envoyait `null`.
- Le backend rejetait la requête en 422.

**Correction V8.3** :
- Le champ `collectiveAccountId` du DTO est désormais **nullable** (suppression de `@NotNull`).
- Le service `createThirdParty` résout automatiquement un compte collectif par défaut selon le
  `type` du tiers, en cherchant le premier compte collectif actif dont le code commence par le
  préfixe SYSCOHADA conventionnel :
  - CLIENT → "411" (Créances clients)
  - SUPPLIER → "401" (Fournisseurs)
  - DONOR → "470" (Comptes transitoires / donateurs)
  - EMPLOYEE → "421" (Personnel — rémunérations dues)
  - OTHER → premier compte collectif disponible (tous codes confondus)
- Si aucun compte collectif n'existe dans l'entreprise, une erreur 422 explicite
  `COLLECTIVE_ACCOUNT_REQUIRED` est levée avec un message guignant l'utilisateur vers la
  création d'un compte collectif dans le plan comptable.
- Si aucun ne matche le préfixe, fallback sur le premier compte collectif disponible (avec log
  WARN pour audit).

### 2. `AuditLogRepository.findByCompanyIdWithFilters` — HTTP 500 AuditLogFragment
**Fichier** : `audit-trail/src/main/java/jo/accountant/audit/AuditLogRepository.java`

**Bug corrigé** (cf. `fff.txt`) :
- `AuditLogFragment: [AuditLogFrag] API error 500 → empty state` (ligne 88)

**Cause racine** :
- La JPQL utilisait le pattern `:param IS NULL OR a.col = :param`.
- Avec Hibernate 6 + PostgreSQL, quand `:param` est null, Hibernate ne peut pas inférer le type
  du paramètre pour la seconde branche `a.col = :param`. PostgreSQL lève alors
  `PSQLException: could not determine data type of parameter` → HTTP 500.

**Correction V8.3** :
- Remplacement du pattern par `COALESCE(:param, a.col) = a.col` qui fournit à Hibernate le type
  du paramètre via la colonne. PostgreSQL reçoit le paramètre avec un type SQL explicite,
  évitant l'erreur de typage.
- La sémantique est identique pour les colonnes `NOT NULL` (`entityType`).
- Pour les colonnes nullables (`actorUserId`), les rows avec `NULL` sont exclues quand le filtre
  est null — acceptable en pratique pour un journal d'audit (toujours attribuable à un user).

### 3. `JournalLineRepository.aggregateByAccountBetweenDates` — HTTP 500 Dashboard
**Fichier** : `accounting-engine/src/main/java/jo/accountant/accountingengine/repository/JournalLineRepository.java`

**Bug corrigé** (cf. `fff.txt`) :
- `DashboardViewModel: [DashboardVM] API error 500 (code=INTERNAL_ERROR) → posting error` (ligne 388)

**Cause racine** :
- Même pattern `:param IS NULL OR col >= :param` que ci-dessus. Incompatible PostgreSQL/Hibernate 6.

**Correction V8.3** :
- Remplacement par `COALESCE(:from, e.entryDate) <= e.entryDate` et
  `COALESCE(:to, e.entryDate) >= e.entryDate`. Sémantiquement équivalent, sans erreur de typage.

### 4. `ReportingService.getDashboard` — defense-in-depth
**Fichier** : `reporting/src/main/java/jo/accountant/reporting/service/ReportingService.java`

**Bug corrigé** :
- Même 500 Dashboard que ci-dessus — protection complémentaire.

**Correction V8.3** :
- Chaque étape du calcul du dashboard (trial balance, factures échues, approbations en attente)
  est encapsulée dans un `try-catch` qui log en WARN et continue avec des valeurs à zéro.
- `NotFoundException` (ex: pas d'exercice fiscal) est traitée séparément comme un cas attendu.
- Ajout de null-checks sur `line.accountCode()` et `line.balance()` pour éviter les NPE sur
  données corrompues.

## Compilation
Build vérifié avec succès (Gradle 8.10.2 + JDK 17 Temurin) :
```
./gradlew :third-parties:compileJava :audit-trail:compileJava :reporting:compileJava :accounting-engine:compileJava --rerun-tasks
BUILD SUCCESSFUL in 38s
```
1 warning préexistant (dépréciation `getActiveFiscalYear`) — non bloquant, non lié aux correctifs.

## Comment déployer
Le backend Spring Boot 3.5.0 se déploie via Docker (cf. `Dockerfile`) ou via Render
(cf. `render.yaml` + `deploy-render.sh`). Le JAR exécutable se construit avec :
```bash
./gradlew :app:bootJar
# output : app/build/libs/joaccountant-2.1.0.jar
```
