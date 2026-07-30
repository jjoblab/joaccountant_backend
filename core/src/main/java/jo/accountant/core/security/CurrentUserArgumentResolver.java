package jo.accountant.core.security;

import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Résout les paramètres {@code @CurrentUser UUID} depuis le claim JWT {@code sub}.
 *
 * <p>Rejette les requêtes non authentifiées avec 403 (pas d'accès anonyme sur les endpoints qui
 * prennent un paramètre {@code @CurrentUser}).
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
            && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("JWT 'sub' claim missing");
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException ex) {
            throw new AuthenticationCredentialsNotFoundException("JWT 'sub' is not a valid UUID: " + sub);
        }
    }
}
