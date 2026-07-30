package jo.accountant.demo.seeders;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.auth.config.Argon2PasswordEncoder;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.company.entity.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * V8.2 — Crée les utilisateurs démo pour les 4 entreprises fictives.
 *
 * <p>Pour chaque entreprise démo, crée un utilisateur OWNER avec un email prédictif
 * et un mot de passe "demo1234" (hashé Argon2id).
 *
 * <p>Ces utilisateurs permettent au endpoint POST /auth/demo-login de générer
 * de vrais JWT tokens pour le mode démo mobile.
 */
@Component
public class DemoUserSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DemoUserSeeder.class);
    private static final String DEMO_PASSWORD = "demo1234";

    private final UserRepository userRepository;
    private final UserCompanyRoleRepository userCompanyRoleRepository;
    private final Argon2PasswordEncoder passwordEncoder;

    public DemoUserSeeder(UserRepository userRepository,
                           UserCompanyRoleRepository userCompanyRoleRepository,
                           Argon2PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userCompanyRoleRepository = userCompanyRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Crée un utilisateur OWNER pour une entreprise démo (idempotent).
     *
     * @param company l'entreprise démo
     * @param email l'email prédictif (ex: demo_boutik_lakay@joaccountant.ht)
     * @param fullName le nom complet de l'utilisateur démo
     */
    public void seedDemoUser(Company company, String email, String fullName) {
        // Vérifier si l'utilisateur existe déjà
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            LOG.info("V8.2 — Utilisateur démo {} existe déjà", email);
            // Vérifier le rôle OWNER
            ensureOwnerRole(company.getId(), userRepository.findByEmailIgnoreCase(email).get().getId());
            return;
        }

        // Créer l'utilisateur
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        user.setFullName(fullName);
        user.setLocale("fr");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user = userRepository.save(user);

        // Créer le rôle OWNER
        ensureOwnerRole(company.getId(), user.getId());

        LOG.info("V8.2 — Utilisateur démo créé : {} (OWNER de {})", email, company.getName());
    }

    private void ensureOwnerRole(UUID companyId, UUID userId) {
        if (userCompanyRoleRepository.findByUserIdAndCompanyId(userId, companyId).isPresent()) {
            return;  // déjà existe
        }
        UserCompanyRole role = new UserCompanyRole();
        role.setId(UUID.randomUUID());
        role.setUserId(userId);
        role.setCompanyId(companyId);
        role.setRole(UserRole.OWNER);
        role.setInvitedAt(Instant.now());
        role.setAcceptedAt(Instant.now());
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        role.setCreatedBy(userId);
        role.setUpdatedBy(userId);
        userCompanyRoleRepository.save(role);
    }

    /** Email prédictif pour une entreprise démo. */
    public static String demoEmail(String demoCode) {
        return "demo_" + demoCode.toLowerCase().replace("_", ".") + "@joaccountant.ht";
    }

    /** Nom complet pour une entreprise démo. */
    public static String demoUserName(String demoCode) {
        return switch (demoCode) {
            case "BOUTIK_LAKAY" -> "Marie-Carmel Joseph";
            case "MOISE_ASSOCIES" -> "Frantz Moïse";
            case "ESPWA_POU_AYITI" -> "Nadège Saintilus";
            case "CARIBBEAN_TEXTILES" -> "Carlo Philippe";
            default -> "Utilisateur Démo";
        };
    }
}
