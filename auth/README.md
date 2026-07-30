# Module : auth

> Authentification JWT stateless, gestion des utilisateurs, rôles par société et tokens de rafraîchissement rotatifs.

## Rôle du module

Le module `:auth` porte l'identité des utilisateurs et leurs rôles par société. Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`) et
ne dépend d'aucun référentiel comptable — les rôles et tokens sont agnostiques au framework.

Le module expose les endpoints d'authentification **hors** de l'espace d'URL company-scoped
(`/api/v1/auth/*`) car un utilisateur non encore rattaché à une société doit pouvoir se
connecter. Les endpoints de gestion des rôles par société sont en revanche company-scoped
(`/api/v1/companies/{companyId}/users/*`) — ils sont exposés via le contrôleur
`UserCompanyRoleController` mais la logique service réside dans `:auth`.

Le JWT contient un claim `companies` : `[{companyId, role}]` qui sera lu par `RoleChecker`
du `:core` à chaque requête ultérieure, sans dépendance cyclique vers `:auth`.

## Ce qu'il fait précisément

### Entités principales

- `User` — utilisateur (NON TenantAware). `email` (unique insensible à la casse via index
  fonctionnel `lower(email)`), `passwordHash` (Argon2), `fullName`, `locale` ( défaut `fr`),
  `active`, `maxCompaniesOverride` (null = limite par défaut).
- `UserCompanyRole` — ligne `(userId, companyId, role)`. NON TenantAware (doit être queryable
  par `userId` seul). `invitedAt`, `acceptedAt` (null tant que l'invitation n'est pas acceptée).
- `RefreshToken` — token de rafraîchissement rotatif. `tokenHash` (SHA-256), `expiresAt`
  (30 jours), `revokedAt` (null = actif). Unique sur `token_hash`.
- `PasswordResetToken` — token à usage unique pour la réinitialisation. `tokenHash`,
  `expiresAt` (1 heure), `usedAt` (null = non consommé).
- `MfaSecret` — **V41 — audit v4.7 §6.3** : secret TOTP (RFC 6238) d'un utilisateur.
  Champs : `userId` (unique — un secret par utilisateur), `secretEncrypted` (Base32 chiffré
  **AES-256-GCM**, clé dans `app.mfa.encryption-key` à externaliser dans Vault/KMS en prod),
  `issuer` (défaut `JOAccountant`), `period` (30 s), `digits` (6), `algorithm` (HmacSHA1),
  `enabledAt` (null = setup en attente — la MFA n'est active qu'après validation du premier
  code), `recoveryCodes` (JSONB : 10 codes à usage unique hashés SHA-256, format
  `[{"hash":"...","usedAt":null}]`). `@Version` pour concurrence optimiste.
- `UserRole` (enum) — `OWNER > ADMIN > ACCOUNTANT > BOOKKEEPER > VIEWER > AUDITOR`.

### Règles métier clés

1. **Email unique insensible à la casse** via index fonctionnel `uc_users_email_lower`
   (V2_001) — la contrainte ne peut pas être une `UNIQUE` classique sur expression PostgreSQL.
2. **Mot de passe** validé par `PasswordValidator` (≥12 chars, majuscule, minuscule, chiffre,
   spécial) et hashé en **Argon2** (`Argon2PasswordEncoder`).
3. **Refresh token rotatif** — à chaque `POST /auth/refresh`, l'ancien token est révoqué et un
   nouveau est émis. **Réutilisation d'un token révoqué → révocation immédiate de toutes les
   sessions actives** de l'utilisateur (`REFRESH_TOKEN_REUSED` 403 — détection de vol).
4. **`/auth/forgot-password` anti-énumération** — renvoie toujours 202, que l'email existe ou
   non. Le reset email est envoyé asynchroniquement via `NotificationChannelPort`.
5. **Reset password = logout forcé** — `consumePasswordReset` révoque toutes les sessions
   actives de l'utilisateur.
6. **`OWNER` non invitable** — ni via `inviteUser`, ni via `updateRole`
   (`OWNER_NOT_INVITABLE` 403). Le OWNER est uniquement le créateur de la société.
7. **`OWNER` immuable** — `updateRole` refuse de modifier le rôle d'un OWNER existant
   (`OWNER_ROLE_IMMUTABLE` 403).
8. **Contrôle de rôle local** via `UserCompanyRoleService.ensureRole(userId, companyId,
   minimumRole)` — lève `INVITATION_PENDING` 403 si `acceptedAt == null`.
9. **MFA TOTP (audit v4.7 §6.3 — NIST 800-63B AAL2)** — `MfaService` (RFC 6238) :
   - **Setup** : `POST /auth/mfa/setup` génère un secret Base32 + l'URL `otpauth://` pour le QR
     code. Le secret est persisté chiffré AES-256-GCM (`app.mfa.encryption-key`),
     `enabledAt=null` (en attente de validation).
   - **Activation** : `POST /auth/mfa/verify?code=123456` valide le premier code TOTP. Si OK,
     `enabledAt=now()` et **10 codes de récupération** sont générés (hashés SHA-256, affichés
     **une seule fois** côté client).
   - **Login 2-step** : `POST /auth/login` retourne `mfaRequired=true` + un `mfaChallengeToken`
     (JWT court) au lieu des tokens normaux si la MFA est activée. Le client doit alors appeler
     `POST /auth/login/mfa?mfaChallengeToken=&code=` avec un code TOTP valide pour obtenir les
     tokens normaux.
   - **Re-vérification pour opérations sensibles** : `POST /auth/mfa/check?code=`.
   - **Code de récupération** : `POST /auth/mfa/recovery-code?code=` (usage unique).
   - **Désactivation** : `POST /auth/mfa/disable` (recommandé : exiger un code TOTP valide ou
     un re-authentification au préalable).
   - **Statut** : `GET /auth/mfa/status` → `{mfaEnabled, mfaRequired}`. `mfaRequired=true` si
     l'utilisateur détient un rôle `OWNER` ou `ADMIN` (accepté) dans au moins une société — la
     MFA est alors **obligatoire**.
10. **JWKS endpoint (RFC 7517)** — `GET /.well-known/jwks.json` expose la clé publique RSA
    au format JWK (uniquement `kty=RSA`, `use=sig`, `alg=RS256`, `kid`, `n`, `e`). Endpoint
    **public** (`permitAll` dans `SecurityConfig`) — ne jamais exposer la clé privée. Actif
    uniquement si `app.jwt.algorithm=RS256` ET `app.jwt.rsa.public-key-path` configuré. Sinon,
    renvoie `{"keys":[]}`. Le contrôleur est `@Profile("!dev & !test")` (HS256 en dev/test).

### Cycle de vie des objets

- `User` : `REGISTERED` → éventuellement `DISABLED` (via `active = false` par un admin).
- `UserCompanyRole` : `INVITED` (acceptedAt null) → `ACCEPTED` (acceptedAt renseigné).
- `RefreshToken` : `ACTIVE` → `REVOKED` (via logout, rotation, reset password ou détection
  de réutilisation).
- `PasswordResetToken` : `ISSUED` → `USED` (usedAt renseigné) ou `EXPIRED`.
- `MfaSecret` (V41) : `PENDING` (`enabledAt=null`) → `ENABLED` (`enabledAt=now()` après
  validation du premier code) → éventuellement `DISABLED` (suppression du secret via
  `POST /auth/mfa/disable`). Les codes de récupération sont consommés unitairement
  (`usedAt` renseigné).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Crée un utilisateur sans société | 409 `EMAIL_ALREADY_REGISTERED`, 422 `EMAIL_INVALID`/`FULL_NAME_REQUIRED`/weak password |
| POST | `/api/v1/auth/login` | Échange credentials contre access + refresh token. **Si MFA activée** : retourne `mfaRequired=true` + `mfaChallengeToken` (les champs `accessToken`/`refreshToken` sont `null`). | 403 `INVALID_CREDENTIALS`/`ACCOUNT_DISABLED` |
| POST | `/api/v1/auth/login/mfa?mfaChallengeToken=&code=` | **Étape 2 du login MFA** (audit v4.7 §6.3) — valide le code TOTP et retourne les tokens normaux. | 403 `MFA_CODE_INVALID` (code TOTP invalide), 403 challenge token invalide |
| POST | `/api/v1/auth/refresh` | Rotation du refresh token ; émet un nouveau pair | 403 `REFRESH_TOKEN_INVALID`/`REFRESH_TOKEN_REUSED`/`REFRESH_TOKEN_EXPIRED`/`ACCOUNT_DISABLED` |
| POST | `/api/v1/auth/logout` | Révoque le refresh token courant (idempotent) | — |
| POST | `/api/v1/auth/forgot-password` | Initie un reset password (anti-énumération : toujours 202) | — |
| POST | `/api/v1/auth/reset-password` | Consomme un reset token, change le mot de passe, révoque toutes les sessions | 403 `RESET_TOKEN_INVALID`/`RESET_TOKEN_ALREADY_USED`/`RESET_TOKEN_EXPIRED`, 422 weak password |
| POST | `/api/v1/auth/mfa/setup` | **V41** — Génère un secret TOTP chiffré AES-256-GCM + URL `otpauth://` pour le QR code. `enabledAt` reste null. | — |
| POST | `/api/v1/auth/mfa/verify?code=` | **V41** — Valide le 1er code TOTP, active la MFA (`enabledAt=now()`), génère **10 codes de récupération** (à afficher 1×). | 403 `MFA_CODE_INVALID` |
| POST | `/api/v1/auth/mfa/check?code=` | **V41** — Re-vérification d'un code TOTP pour opérations sensibles (retourne `{valid:bool}`). | — |
| POST | `/api/v1/auth/mfa/recovery-code?code=` | **V41** — Consomme un code de récupération à usage unique (fallback si perte du téléphone). | 403 code invalide/déjà utilisé |
| POST | `/api/v1/auth/mfa/disable` | **V41** — Désactive la MFA (révoque le secret + les codes). | — |
| GET | `/api/v1/auth/mfa/status` | **V41** — Retourne `{mfaEnabled, mfaRequired}`. `mfaRequired=true` si l'utilisateur est OWNER/ADMIN (accepté) sur au moins une société. | — |
| GET | `/.well-known/jwks.json` | **RFC 7517** — JWKS endpoint pour valider les JWT RS256. **Public** (pas de Bearer). Renvoie `{"keys":[]}` si RS256 non configuré. | — |
| POST | `/api/v1/companies/{companyId}/users` | Invite un utilisateur existant par email (ADMIN/OWNER requis) | 403 `OWNER_NOT_INVITABLE`/`INSUFFICIENT_ROLE`, 404 `USER_NOT_FOUND`, 409 `USER_ALREADY_IN_COMPANY` |
| PATCH | `/api/v1/companies/{companyId}/users/{userId}/role` | Modifie le rôle d'un utilisateur (ADMIN/OWNER requis) | 403 `OWNER_NOT_INVITABLE`/`OWNER_ROLE_IMMUTABLE`, 404 not found |
| POST | `/api/v1/companies/{companyId}/users/{userId}/accept` | Accepte une invitation | 404 not found, 409 `INVITATION_ALREADY_ACCEPTED` |
| GET | `/api/v1/companies/{companyId}/users` | Liste les utilisateurs + rôles de la société | — |

**Tokens** : access token TTL = 15 minutes ; refresh token TTL = 30 jours rotatif ; reset
token TTL = 1 heure à usage unique. `mfaChallengeToken` TTL court (5 min recommandé).

### MFA 2-step login flow

1. `POST /api/v1/auth/login` → si MFA activée : `200` avec
   `{mfaRequired:true, mfaChallengeToken:"eyJ...", accessToken:null, refreshToken:null, ...}`.
2. Le client affiche l'écran « Entrez votre code TOTP » et envoie
   `POST /api/v1/auth/login/mfa?mfaChallengeToken=eyJ...&code=123456`.
3. Si code valide : `200` avec `{accessToken, refreshToken, mfaRequired:false, ...}`.
   Si code invalide : `403 MFA_CODE_INVALID` (le challenge token reste valide — le client peut
   réessayer sans refaire le login complet).
4. Si l'utilisateur a perdu son téléphone : utiliser `POST /api/v1/auth/mfa/recovery-code?code=`
   avec l'un des 10 codes de récupération (affichés une seule fois à l'activation).

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `NotificationChannelPort` (envoi des emails d'invitation / reset),
  `TenantContext`, exceptions (`ConflictException`, `ForbiddenException`,
  `ValidationException`, `NotFoundException`), `BusinessException`.

### Modules qui dépendent de celui-ci

- `:company` — dépend de `UserCompanyRoleRepository`, `UserCompanyRoleService` et des entités
  `UserCompanyRole`/`UserRole` pour assigner le rôle OWNER au créateur d'une société.
- `:app` — implémente `ApproverEmailResolverPort` du `:core` via `UserEmailResolver` (ce
  module) qui a accès à `UserRepository` + `UserCompanyRoleRepository`.

### Événements publiés / consommés

- **Publie** : `UserRegisteredEvent` (lors de `POST /auth/register`).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V2_001__auth_users.sql` — tables `users`, `refresh_token`,
  `password_reset_token`. Index fonctionnel unique `uc_users_email_lower` (insensible à la
  casse). FK `refresh_token.user_id → users(id) ON DELETE CASCADE`.
- `src/main/resources/db/migration/V2_002__auth_user_company_role.sql` — table
  `user_company_role`. Contrainte unique `(user_id, company_id)`. CHECK sur `role` ∈ les 6
  valeurs. La FK vers `companies(id)` est ajoutée dans `V3_002` (la table companies n'existe
  pas encore au moment de V2_002 — dépendance cyclique résolue par FK différée).
- `src/main/resources/db/migration/V41__mfa_secret.sql` — **V41 — audit v4.7 §6.3**. Crée la
  table `mfa_secret` (un secret TOTP par `user_id`, chiffré AES-256-GCM). Colonnes : `id`,
  `user_id` (UNIQUE), `secret_encrypted` (VARCHAR 500), `issuer`, `period` (CHECK 10-300,
  défaut 30), `digits` (CHECK ∈ {6, 8}, défaut 6), `algorithm` (CHECK ∈ {HmacSHA1, HmacSHA256,
  HmacSHA512}), `enabled_at` (null = setup en attente), `recovery_codes` (JSONB), `created_at`,
  `updated_at`, `version` (optimistic locking).

## Points d'attention (hérités de l'audit)

- ⚠️ **Rate-limiting sur `/auth/login` et `/auth/forgot-password`** — implémenté dans
  `:app/security/RateLimitFilter.java` (10 tentatives/min/IP, 429). Côté mobile, gérer le 429
  en backoff exponentiel (audit B5 — pas de garde de rôle métier, mais ce filtre protège
  l'auth).
- ⚠️ **`forgot-password` ne révèle jamais si l'email existe** — le client mobile doit afficher
  un message uniforme "Si l'email existe, un lien a été envoyé" et ne pas tenter de déduire
  l'existence du compte (sinon : contournement de l'anti-énumération).
- ⚠️ **Refresh token rotation : stratégie client** — après un 403 `REFRESH_TOKEN_REUSED`, le
  client mobile DOIT forcer un re-login complet (toutes les sessions ont été révoquées côté
  serveur). Ne pas retenter automatiquement.
- ⚠️ **`acceptInvitation` ne vérifie pas que l'appelant est l'utilisateur cible** —
  `UserCompanyRoleController.acceptInvitation` accepte n'importe quel `userId` dans l'URL. Un
  utilisateur malveillant avec un JWT valide pour la société pourrait accepter l'invitation
  d'un autre utilisateur (audit B5 — contrôle d'accès incomplet). À corriger.

## Tests

Aucun test dans `:auth/src/test`. Le module est couvert indirectement par
`Phase1IntegrationTest` dans `:app` qui exerce le flux complet register → login → refresh →
forgot-password → reset-password.
