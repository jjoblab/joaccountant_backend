package jo.accountant.company.service;

import java.util.UUID;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.core.exception.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applique {@code app.subscription.max-companies-per-user} (§12).
 *
 * <p>§12 : vérifié AVANT toute écriture en DB dans {@code CompanyService.createCompany()}.
 * Défaut = 3. Un override au niveau utilisateur ({@code User.maxCompaniesOverride}) lève la
 * limite par utilisateur — pour un futur tier payant — sans redévelopper le guard.
 *
 * <p>Dépassement → 409 Conflict avec un code stable pour que le frontend puisse afficher un
 * prompt d'upgrade.
 
 *
 * @author jo@Dev


*/
@Component
public class MaxCompaniesGuard {

    private final UserRepository userRepository;
    private final int defaultMaxCompanies;

    public MaxCompaniesGuard(UserRepository userRepository,
                             @Value("${app.subscription.max-companies-per-user:3}") int defaultMaxCompanies) {
        this.userRepository = userRepository;
        this.defaultMaxCompanies = defaultMaxCompanies;
    }

    public int limitFor(UUID userId) {
        return userRepository.findById(userId)
            .map(User::getMaxCompaniesOverride)
            .map(o -> Math.max(o, defaultMaxCompanies))
            .orElse(defaultMaxCompanies);
    }

    /** Lève 409 si la création d'une société supplémentaire dépasserait la limite de l'utilisateur. */
    public void ensureCanCreateOneMore(UUID userId, long currentCount) {
        int limit = limitFor(userId);
        if (currentCount >= limit) {
            throw new ConflictException("MAX_COMPANIES_REACHED",
                "You have reached the maximum number of companies (" + limit + "). " +
                "Current count: " + currentCount + ". " +
                "Consider upgrading your subscription to create more.");
        }
    }

    int defaultMaxCompanies() {
        return defaultMaxCompanies;
    }
}
