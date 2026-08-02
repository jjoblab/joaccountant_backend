package jo.accountant.core.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import jo.accountant.core.exception.ForbiddenException;

/**
 * Vérificateur de rôle basé sur le JWT (S1 fix).
 *
 * <p>Le JWT contient un claim {@code companies} : {@code [{companyId, role}]}.
 * Cette classe permet de vérifier qu'un utilisateur a un rôle suffisant pour une entreprise
 * donnée, sans dépendre de :auth (principe 5).
 *
 * <p>le JWT est rafraîchi côté serveur au moment de la création de
 * company. Le claim JWT fait foi — si la company ou le rôle n'y figure pas, c'est que
 * l'utilisateur n'y a pas accès ou avec un rôle insuffisant. Pas de fall-back DB.
 
 *
 * @author jo@Dev


*/
@Component
public class RoleChecker {

    /**
     * Vérifie que l'utilisateur courant a au moins le rôle requis pour l'entreprise donnée.
     * Lance 403 Forbidden si insuffisant.
     *
     * <p>Ordre des rôles (du plus élevé au plus bas) :
     * OWNER > ADMIN > ACCOUNTANT > BOOKKEEPER > VIEWER > AUDITOR
     */
    public void ensureRole(UUID companyId, String minimumRole) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("NOT_AUTHENTICATED", "Authentification requise");
        }

        Object raw = jwt.getClaim("companies");
        if (!(raw instanceof List<?> list)) {
            throw new ForbiddenException("NO_COMPANY_ACCESS",
                "Aucun accès entreprise dans le token");
        }

        for (Object entry : list) {
            if (entry instanceof Map<?, ?> m) {
                Object cid = m.get("companyId");
                if (cid instanceof String s && s.equalsIgnoreCase(companyId.toString())) {
                    Object role = m.get("role");
                    if (role instanceof String r) {
                        if (roleLevel(r) <= roleLevel(minimumRole)) {
                            return; // OK — rôle suffisant
                        }
                    }
                }
            }
        }

        throw new ForbiddenException("INSUFFICIENT_ROLE",
            "Rôle requis : " + minimumRole + " pour l'entreprise " + companyId);
    }

    /** OWNER=0, ADMIN=1, ACCOUNTANT=2, BOOKKEEPER=3, VIEWER=4, AUDITOR=5 */
    private int roleLevel(String role) {
        return switch (role.toUpperCase()) {
            case "OWNER" -> 0;
            case "ADMIN" -> 1;
            case "ACCOUNTANT" -> 2;
            case "BOOKKEEPER" -> 3;
            case "VIEWER" -> 4;
            case "AUDITOR" -> 5;
            default -> 99;
        };
    }
}
