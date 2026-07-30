package jo.accountant.core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Résolu vers l'UUID de l'utilisateur courant (depuis le claim JWT {@code sub}).
 *
 * <p>Usage : {@code void handler(@CurrentUser UUID userId, ...)}.
 * Résolu par {@link CurrentUserArgumentResolver} — lève 403 si aucun utilisateur authentifié n'est
 * présent.
 */
@Target({ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
