package jo.accountant.auth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.core.port.ApproverEmailResolverPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Résout les emails des utilisateurs ayant un rôle donné dans une entreprise (Vague 1, item 1.2).
 *
 * <p>Implémente {@link ApproverEmailResolverPort} (défini dans :core) pour que les modules
 * métier (:accounting-engine, :invoicing, :funds-grants) puissent résoudre les emails
 * approbateurs sans dépendre de :auth.
 */
@Service
public class UserEmailResolver implements ApproverEmailResolverPort {

    private final UserCompanyRoleRepository ucrRepository;
    private final UserRepository userRepository;

    public UserEmailResolver(UserCompanyRoleRepository ucrRepository,
                             UserRepository userRepository) {
        this.ucrRepository = ucrRepository;
        this.userRepository = userRepository;
    }

    /**
     * Retourne les emails de tous les utilisateurs ayant accepté une invitation avec un des
     * rôles spécifiés dans l'entreprise donnée.
     *
     * @param companyId identifiant de l'entreprise
     * @param roles liste de noms de rôles (ex. ["ADMIN", "OWNER"])
     * @return liste d'emails
     */
    @Transactional(readOnly = true)
    public List<String> resolveEmailsByRoles(UUID companyId, List<String> roles) {
        if (roles == null || roles.isEmpty()) return List.of();

        List<UserCompanyRole> ucrs = ucrRepository.findByCompanyId(companyId);
        List<String> emails = new ArrayList<>();
        for (UserCompanyRole ucr : ucrs) {
            if (ucr.getAcceptedAt() == null) continue;  // invitation non acceptée
            if (!roles.contains(ucr.getRole().name())) continue;
            userRepository.findById(ucr.getUserId()).ifPresent(user -> {
                if (user.isActive()) emails.add(user.getEmail());
            });
        }
        return emails;
    }
}
