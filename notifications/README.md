# Module : notifications

> Centre de notifications in-app + e-mail, préférences utilisateur et règles d'alerte configurables.

## Rôle du module

Le module `:notifications` est le centre de notification de l'application. Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`)
et fonctionne pour les 6 référentiels — les notifications sont agnostiques au framework.

Il joue deux rôles :
1. **Implémentation de référence de `NotificationChannelPort`** (posé en Phase 1 dans
   `:core`) — tout envoi d'email via le port aboutit à une `Notification` persistée.
2. **Notifications in-app** pour les événements de domaine (`InvoiceIssuedEvent`,
   `LowStockEvent`, `ApprovalRequestedEvent`, etc.).

Le pattern architectural est le même que `:audit-trail` — `:notifications` s'abonne aux
événements de domaine, aucun module métier ne l'appelle directement (principe 5).

## Ce qu'il fait précisément

### Entités principales

- `Notification` — notification individuelle. Champs : `recipientUserId` (null pour un
  email système), `companyId`, `type` (code du template — ex. `user-invitation`,
  `password-reset`, `approval-requested`, `invoice-issued`), `payloadJson`, `channel`
  (IN_APP/EMAIL), `status` (PENDING/SENT/FAILED), `createdAt`, `sentAt`, `readAt`.
- `NotificationTemplate` — gabarit de notification par type. Champs : `code`, `subject`,
  `bodyTemplate` (Thymeleaf), `defaultChannel`.
- `NotificationPreference` — préférence par utilisateur et par type. Champs :
  `userId`, `companyId`, `type`, `emailEnabled`, `inAppEnabled`.
- `AlertRule` — règle d'alerte par entreprise. Champs : `companyId`, `alertType`, `threshold`,
  `recipientRoles` (JSONB).
- `AlertType` (enum) — `INVOICE_OVERDUE`, `FISCAL_PERIOD_PAST_DUE`,
  `GRANT_THRESHOLD_REACHED`, `LOW_STOCK`, `APPROVAL_PENDING`.
- `NotificationChannel` (enum) — `IN_APP`, `EMAIL`.
- `NotificationStatus` (enum) — `PENDING`, `SENT`, `FAILED`.

### Règles métier clés

1. **`NotificationsService implements NotificationChannelPort`** — la méthode `sendEmail(to,
   templateCode, variables)` crée une `Notification EMAIL` avec `recipientUserId = null`
   (l'email ne permet pas de résoudre l'userId sans dépendre de `:auth` — choix
   architectural pour préserver l'indépendance du module).
2. **`createInAppNotification`** crée une `Notification IN_APP` pour un `recipientUserId`
   précis — appelée par les listeners d'événements de domaine.
3. **Préférences par utilisateur** — chaque utilisateur peut désactiver un type de
   notification par canal (`emailEnabled=false`, `inAppEnabled=true`). Les préférences
   sont par `(userId, companyId, type)`.
4. **Règles d'alerte par entreprise** — `AlertRule` déclenche des notifications
  automatiques quand un seuil est atteint (ex. `INVOICE_OVERDUE` après 30 jours,
  `GRANT_THRESHOLD_REACHED` à 80 % de consommation, `LOW_STOCK` sous le seuil).
5. **Pattern asynchrone** — comme `:audit-trail`, les notifications sont créées dans un
   listener `@Async` `@TransactionalEventListener(AFTER_COMMIT)` pour ne pas bloquer la
   transaction métier.

### Cycle de vie des objets

- `Notification` : `PENDING → SENT` (ou `→ FAILED` si envoi email échoue) → `READ`
  (quand l'utilisateur la marque comme lue via `PATCH /{id}/read`).
- `AlertRule` : créée → active (pas d'endpoint public de désactivation — opération DB).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| GET | `/api/v1/companies/{companyId}/notifications?page=&size=` | **Paginé (Finding #3)** — retourne une `Page<NotificationResponse>`. `?page=0&size=20` (défaut, `size` capped à 200). | — |
| PATCH | `/api/v1/companies/{companyId}/notifications/{notificationId}/read` | Marque comme lue | 404 `Notification`/403 (pas le propriétaire) |
| GET | `/api/v1/companies/{companyId}/notifications/preferences` | Liste mes préférences | — |
| PATCH | `/api/v1/companies/{companyId}/notifications/preferences` | Met à jour mes préférences. Corps : `{type, emailEnabled?, inAppEnabled?}` | 404 `NotificationPreference` |
| POST | `/api/v1/companies/{companyId}/notifications/alert-rules` | Crée une règle d'alerte | 422 champs invalides |
| GET | `/api/v1/companies/{companyId}/notifications/alert-rules` | Liste les règles d'alerte de l'entreprise | — |

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `NotificationChannelPort` (que ce module implémente), `TenantContext`,
  `ApplicationEventPublisher`.

### Modules qui dépendent de celui-ci

- Aucun ne dépend directement de `:notifications`. La dépendance est inversée : ce sont
  les modules métier qui publient des événements de domaine que `:notifications` consomme.
- `:auth`, `:approval-workflow`, `:company` — utilisent `NotificationChannelPort` (du
  `:core`) pour envoyer des emails ; le port est implémenté par `:notifications` au runtime
  via injection Spring.

### Événements publiés / consommés

- **Publie** : aucun.
- **Consomme** (via `NotificationChannelPort.sendEmail`) : `UserRegisteredEvent`,
  `CompanyCreatedEvent`, `ApprovalRequestedEvent`, `LowStockEvent`, etc. — tous les
  événements de domaine qui déclenchent un envoi d'email via le port.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V39__notifications.sql` — tables
  `notification_template`, `notification`, `notification_preference`, `alert_rule`. CHECK
  sur `channel` (2 valeurs), `status` (3 valeurs), `alert_type` (5 valeurs). Index sur
  `(recipient_user_id, status)` et `(company_id, alert_type)`.

## Points d'attention (hérités de l'audit)

- ⚠️ **`recipientUserId = null` pour les emails** — les notifications `EMAIL` créées via
  `sendEmail` ont `recipientUserId = null` car le port ne reçoit qu'un email. Conséquence :
  elles n'apparaissent pas dans `GET /notifications` (qui filtre par `recipientUserId`).
  Le client mobile ne voit donc QUE les notifications `IN_APP` — pas l'historique des
  emails envoyés. À corriger en résolvant l'email → userId (mais cela introduirait une
  dépendance vers `:auth`).
- ⚠️ **Pas de WebSocket / SSE** — les notifications ne sont pas poussées en temps réel vers
  le client mobile. Le client doit poller `GET /notifications` régulièrement (ex. toutes
  les 60s) pour récupérer les nouvelles notifications.
- ⚠️ **Aucune pagination sur `GET /notifications`** — **CORRIGÉ (Finding #3)** : `GET /notifications`
  retourne désormais une `Page<NotificationResponse>` avec `?page=&size=` (défaut 0/20,
  size capped à 200). Le client peut toujours marquer comme lues au fur et à mesure pour
  réduire la liste.
- ⚠️ **Pas d'envoi réel d'email** — `sendEmail` persiste la notification mais n'appelle
  pas de SDK SMTP. L'envoi réel doit être branché (ex. SendGrid, Mailgun) avant production.
  En Phase 15, le `LoggingNotificationChannelAdapter` du `:core` logge simplement l'envoi.
- ⚠️ **`AlertRule` non évaluée automatiquement** — il n'y a pas de scheduler qui évalue
  les `AlertRule` périodiquement. Les alertes ne sont déclenchées que par les événements
  de domaine (ex. `LowStockEvent` pour `LOW_STOCK`). Pour `INVOICE_OVERDUE` ou
  `FISCAL_PERIOD_PAST_DUE`, aucun scheduler n'est branché — l'alerte ne se déclenche pas.
  Le client mobile doit afficher ces cas d'alerte lui-même (en comparant les dates).

## Tests

Couvert par `NotificationsIntegrationTest` dans `:app` (7 tests) — préférences (création,
mise à jour), règles d'alerte (création, listing), marquage comme lu. `RecordingNotificationChannel`
(helper de test dans `:app`) capture les notifications pour assertion.
