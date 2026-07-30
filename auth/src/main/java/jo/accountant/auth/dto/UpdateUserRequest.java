package jo.accountant.auth.dto;

/**
 * UpdateUserRequest — corps de {@code PATCH /api/v1/auth/me}.
 *
 * @param fullName nouveau nom complet (null = inchangé, vide = effacé)
 * @param phone    numéro de téléphone (null = inchangé — non persisté pour l'instant, accepté pour compat future)
 */
public record UpdateUserRequest(
    String fullName,
    String phone
) {}
