package jo.accountant.core.port;

import java.util.List;
import java.util.UUID;

/**
 * Port de résolution des emails d'approbateurs (Vague 1, item 1.2).
 *
 * <p>Défini dans :core pour que les modules métier (:accounting-engine, :invoicing,
 * :funds-grants) puissent l'utiliser sans dépendre de :auth.
 *
 * <p>Implémenté dans :auth par {@code UserEmailResolver} qui a accès à UserRepository
 * et UserCompanyRoleRepository.
 */
public interface ApproverEmailResolverPort {

    /**
     * Retourne les emails des utilisateurs ayant accepté un des rôles spécifiés
     * dans l'entreprise donnée.
     *
     * @param companyId identifiant de l'entreprise
     * @param roles liste de noms de rôles (ex. ["ADMIN", "OWNER"])
     * @return liste d'emails
     */
    List<String> resolveEmailsByRoles(UUID companyId, List<String> roles);
}
