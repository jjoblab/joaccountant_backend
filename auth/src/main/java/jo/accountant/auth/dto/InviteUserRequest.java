package jo.accountant.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jo.accountant.auth.entity.UserRole;

/**
 * Invitation d'un utilisateur dans une société (§3.4, §13.
 *
 * <p><b>Extension du payload</b> : ajout du champ
 * {@code fullName} pour permettre à un OWNER/ADMIN d'inviter un utilisateur
 * <em>n'ayant pas encore de compte</em>. Le service créera alors le compte
 * via {@link jo.accountant.auth.service.AuthService#register} avec un mot de
 * passe temporaire aléatoire (l'invité recevra un email d'invitation et
 * devra utiliser le flux « mot de passe oublié » pour définir son vrai mot
 * de passe).
 *
 * <p><b>Backward compatibility</b> : {@code fullName} est <strong>nullable</strong>.
 * Si l'invité existe déjà en base, {@code fullName} est ignoré (on conserve
 * le {@code fullName} existant). Si l'invité n'existe pas et {@code fullName}
 * est blank, le service lève 422 {@code FULL_NAME_REQUIRED}.
 *
 * <p>{@code role} ne peut pas être {@link UserRole#OWNER} — le service lève 403
 * {@code OWNER_NOT_INVITABLE}. Le seul OWNER d'une société est son créateur
 * (assigné automatiquement à {@code createCompany}).
 
 *
 * @author jo@Dev


*/
public record InviteUserRequest(
    @NotBlank @Email String email,
    /** Nullable — requis uniquement pour créer un nouvel utilisateur. */
    String fullName,
    @NotNull UserRole role
) {}
