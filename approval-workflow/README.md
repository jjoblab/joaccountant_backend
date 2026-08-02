# Module : approval-workflow

> Seuils d'approbation transverses et mécanisme "quatre yeux" appliqués aux actions financières.

## Rôle du module

Le module `:approval-workflow` implémente le mécanisme de validation à seuil pour les actions
financières sensibles (postage d'écriture, émission de facture, proposition de décaissement
de subvention). Il est **always-on** (activé pour tous les types métier via
`BusinessTypeModuleService.alwaysOnModules()`) et fonctionne pour les 6 référentiels comptables —
les seuils sont en devise fonctionnelle, indépendamment du framework.

Le module expose des endpoints de gestion des règles et des demandes, mais **l'évaluation**
d'une action (`ApprovalWorkflowService.evaluate`) est appelée directement par les modules
consommateurs (`:accounting-engine`, `:invoicing`, `:funds-grants`) avant la transition qui
rend l'action définitive — pas par l'utilisateur final.

## Ce qu'il fait précisément

### Entités principales

- `ApprovalRule` — règle de seuil pour un `actionType` donné. Une règle active par
  `(companyId, actionType)` (UNIQUE INDEX partiel `uc_approval_rule_active` sur
  `active = TRUE`). Champs : `actionType`, `thresholdAmount` (NUMERIC 19,4),
  `requiredApproverRoles` (JSONB), `minApprovals` (≥ 1), `active`.
- `ApprovalRequest` — demande d'approbation créée par `evaluate` quand le montant dépasse le
  seuil. Champs : `actionType`, `resourceType` (ex. `"JournalEntry"`), `resourceId`,
  `amount`, `requestedBy`, `requestedAt`, `status` (PENDING/APPROVED/REJECTED/CANCELLED),
  `decidedBy`, `decidedAt`, `comment`, `approvalCount`, `approverUserIds` (JSONB).
- `ApprovalActionType` (enum) — `JOURNAL_ENTRY_POST`, `INVOICE_ISSUE`,
  `GRANT_DISBURSEMENT_PROPOSAL`.
- `ApprovalStatus` (enum) — `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`.

### Règles métier clés

1. **Absence de règle active = aucune approbation requise** — pas de blocage surprise pour
   une petite structure (§7).
2. **Montant ≤ seuil = postage direct** — `EvaluateResult.autoApproved()`.
3. **Montant > seuil = `ApprovalRequest` PENDING** — l'action cible doit être mise à l'état
   intermédiaire `PENDING_APPROVAL` côté consommateur. `EvaluateResult.pending(requestId)`.
4. **Règle des quatre yeux** — `requestedBy == decidedBy` → 403 `SELF_APPROVAL_FORBIDDEN`
   sur `approve`/`reject`. L'annulation est explicitement hors périmètre (le demandeur peut
   annuler sa propre demande avant décision).
5. **Rejet → DRAFT côté consommateur** — avec motif horodaté et visible. Le consommateur
   doit écouter `ApprovalDecidedEvent` pour faire la transition (audit B2 — voir
   `:accounting-engine`).
6. **Re-décision interdite** — une demande dans un état terminal (APPROVED/REJECTED/CANCELLED)
   ne peut plus changer de statut : 409 `APPROVAL_REQUEST_ALREADY_DECIDED`.
7. **Multi-approbateurs (`minApprovals > 1`)** — depuis Vague 3 item 3.1. La demande reste
   PENDING jusqu'à ce que `approvalCount >= minApprovals`. Un même utilisateur ne peut pas
   approuver deux fois (403 `ALREADY_APPROVED_BY_USER`).
8. **Notification best-effort** — les approbateurs éligibles sont notifiés via
   `NotificationChannelPort`. Si la liste `approverEmails` est vide (résolution
   userId→email échouée côté appelant), un warning est loggé mais la demande est créée
   quand même. **C'est le point critique de l'audit M12** : si les modules métier passent
   `approverEmails = List.of()`, aucun approbateur n'est notifié et la demande reste
   bloquée à vie (sauf correction B2).

### Cycle de vie des objets

- `ApprovalRequest` : `PENDING → APPROVED` (via `POST /requests/{id}/approve` si
  `approvalCount >= minApprovals`) / `→ REJECTED` (via `POST /requests/{id}/reject`, motif
  obligatoire) / `→ CANCELLED` (via `cancel` interne — pas d'endpoint public).
- Une fois terminal, plus de transition possible (409 sur toute re-décision).
- **Effet de bord sur la ressource cible** (consommateur) :
  - `APPROVED` → l'action cible peut être finalisée (ex. `JournalEntry` passe à `POSTED`
    via `@EventListener(ApprovalDecidedEvent)` dans `:accounting-engine` — audit B2).
  - `REJECTED` ou `CANCELLED` → l'action cible revient à `DRAFT` côté consommateur.

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/approval-workflow/rules` | Crée une règle active. Corps : `{actionType, thresholdAmount, requiredApproverRoles, minApprovals}` | 409 `APPROVAL_RULE_ALREADY_EXISTS`, 422 `ACTION_TYPE_REQUIRED`/`THRESHOLD_INVALID`/`REQUIRED_APPROVER_ROLES_REQUIRED`/`UNKNOWN_ROLE`/`MIN_APPROVALS_INVALID` |
| GET | `/api/v1/companies/{companyId}/approval-workflow/rules` | Liste les règles (actives et inactives) | — |
| GET | `/api/v1/companies/{companyId}/approval-workflow/requests?status=` | Liste les demandes (filtrage optionnel par statut) | — |
| POST | `/api/v1/companies/{companyId}/approval-workflow/requests/{requestId}/approve` | Approuve une demande. Corps : `{comment?}` | 403 `SELF_APPROVAL_FORBIDDEN`/`ALREADY_APPROVED_BY_USER`, 404 `ApprovalRequest`, 409 `APPROVAL_REQUEST_ALREADY_DECIDED` |
| POST | `/api/v1/companies/{companyId}/approval-workflow/requests/{requestId}/reject` | Rejette une demande. Corps : `{comment}` (obligatoire) | 403 `SELF_APPROVAL_FORBIDDEN`, 404, 409, 422 `REJECT_COMMENT_REQUIRED` |

> Il n'y a **pas** d'endpoint `evaluate`. L'évaluation se fait via
> `ApprovalWorkflowService.evaluate(companyId, actionType, resourceType, resourceId, amount,
> approverEmails)`, appelé directement par les modules Phase 5/12/14 avant la transition
> définitive.
> Il n'y a pas non plus d'endpoint `cancel` public — la cancellation se fait par les modules
> métier en cas d'erreur de saisie détectée avant décision.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `NotificationChannelPort` (notification des approbateurs), exceptions,
  `TenantContext`, `ApplicationEventPublisher`.

### Modules qui dépendent de celui-ci

- `:accounting-engine` — appelle `evaluate(JOURNAL_ENTRY_POST, ...)` au postage d'une
  écriture ; consomme `ApprovalDecidedEvent` via `@EventListener` pour la transition
  `PENDING_APPROVAL → POSTED` (audit B2).
- `:invoicing` — appelle `evaluate(INVOICE_ISSUE, ...)` à l'émission d'une facture.
- `:funds-grants` — appelle `evaluate(GRANT_DISBURSEMENT_PROPOSAL, ...)` à la proposition
  d'écriture de fonds dédiés (seul module à appeler `evaluate` avec une vraie liste
  d'`approverEmails` résolue — les autres passent `List.of()`, voir M12).

### Événements publiés / consommés

- **Publie** : `ApprovalRuleCreatedEvent`, `ApprovalRequestedEvent` (à la création d'une
  demande), `ApprovalDecidedEvent` (à toute décision — approve/reject/cancel). Ce dernier
  est **consommé par `:accounting-engine`** (audit B2) pour finaliser ou re-draft l'écriture
  cible.
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V14__approval_workflow.sql` — tables `approval_rule`
  et `approval_request`. UNIQUE INDEX partiel `uc_approval_rule_active` sur
  `(company_id, action_type) WHERE active = TRUE`. CHECK sur `action_type` (3 valeurs),
  `status` (4 valeurs), `threshold_amount >= 0`, `min_approvals >= 1`. CHECK
  `chk_approval_request_decision` : si `status != 'PENDING'` alors `decided_by` et
  `decided_at` sont requis.
- `src/main/resources/db/migration/V43__approval_count.sql` — Fix S1-FIN : ajout des
  colonnes `approval_count` (INT, default 0) et `approver_user_ids` (JSONB) sur
  `approval_request`, pour supporter le multi-approbateurs (`minApprovals > 1`).

## Points d'attention (hérités de l'audit)

- ⚠️ **M12 — `approverEmails = List.of()` systématique** chez `invoicing`, `fixed-assets`,
  `inventory` (écritures automatiques). Si une `ApprovalRule` `JOURNAL_ENTRY_POST` s'active
  pour une de ces écritures, l'`ApprovalRequest` est créée PENDING **sans qu'aucun
  approbateur ne soit notifié**. La demande reste bloquée jusqu'à ce qu'un approbateur
  consulte manuellement `GET /approval-workflow/requests?status=PENDING`. Seul `:funds-grants`
  fournit une vraie liste d'emails. Le client mobile doit proposer un écran "Demandes en
  attente" consultable régulièrement par les ADMIN/OWNER.
- ⚠️ **B2 — Transition `PENDING_APPROVAL → POSTED` corrigée** : avant la correction, une
  écriture passée à `PENDING_APPROVAL` restait bloquée à vie même après `APPROVED` de la
  demande. Désormais, `:accounting-engine` écoute `ApprovalDecidedEvent` et finalise
  automatiquement. Côté mobile, un polling sur `GET /journal-entries/{id}` ou un refresh
  manuel est nécessaire pour voir la transition (pas de WebSocket).
- ⚠️ **Pas de suppression de règle** — seule la désactivation (`deactivateRule`) est
  permise, mais l'endpoint public n'existe pas (uniquement la méthode service). Pour
  désactiver une règle, il faut passer par une opération DB manuelle ou un endpoint à
  ajouter. Le client mobile doit indiquer à l'utilisateur qu'une règle active ne peut pas
  être supprimée directement — il faut la désactiver côté backend (ou créer une nouvelle
  règle qui écrase la précédente via `409 APPROVAL_RULE_ALREADY_EXISTS` — pas non plus
  idéal).

## Tests

Couvert par `ApprovalWorkflowIntegrationTest` dans `:app` — test de création de règle,
évaluation auto-approved/pending, règle des quatre yeux, rejet avec motif, multi-approbateurs
(`minApprovals > 1`).
