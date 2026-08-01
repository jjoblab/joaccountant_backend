package jo.accountant.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Réponse standard pour un utilisateur d'une société (V2.6.0 — wizard refonte).
 *
 * <p>Utilisé par :
 * <ul>
 *   <li>{@code POST /api/v1/companies/{companyId}/users} — invitation/création d'utilisateur.</li>
 *   <li>{@code GET  /api/v1/companies/{companyId}/users} — listing des utilisateurs.</li>
 * </ul>
 *
 * <p>Jointure {@code user_company_role} ⟕ {@code users} : on renvoie l'email et le
 * fullName de l'utilisateur (pas seulement son {@code userId}) pour éviter au client
 * une seconde requête {@code GET /api/v1/users/{userId}} qui n'existe d'ailleurs pas.
 *
 * <p>{@code acceptedAt} est {@code null} tant que l'invité n'a pas explicitement accepté
 * l'invitation (endpoint {@code POST /api/v1/companies/{companyId}/users/{userId}/accept}).
 * Avant cela, la ligne existe mais l'utilisateur n'a aucun accès effectif à la société.
 *
 * @param userId     identifiant de l'utilisateur (existant ou fraîchement créé).
 * @param email      email de l'utilisateur (lowercase normalisé par {@code AuthService.register}).
 * @param fullName   nom complet de l'utilisateur.
 * @param role       rôle dans la société (OWNER/ADMIN/ACCOUNTANT/BOOKKEEPER/VIEWER/AUDITOR).
 * @param invitedAt  instant d'invitation (toujours non-null).
 * @param acceptedAt instant d'acceptation, ou {@code null} si invitation en attente.
 */
public record CompanyUserResponse(
    UUID userId,
    String email,
    String fullName,
    String role,
    Instant invitedAt,
    Instant acceptedAt
) {}
