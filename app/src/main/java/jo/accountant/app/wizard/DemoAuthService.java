package jo.accountant.app.wizard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.auth.service.AuthService;
import jo.accountant.auth.service.JwtService;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8.2 — Service de login démo.
 *
 * <p>Pour chaque entreprise démo (BOUTIK_LAKAY, MOISE_ASSOCIES, ESPWA_POU_AYITI, CARIBBEAN_TEXTILES),
 * un utilisateur démo est créé par le seeder avec un email prédictif.
 *
 * <p>Au login démo, le service :
 * 1. Trouve l'entreprise démo par son nom
 * 2. Trouve l'utilisateur OWNER de cette entreprise
 * 3. Génère un JWT token + refresh token
 * 4. Retourne le tout au mobile
 */
@Service
public class DemoAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(DemoAuthService.class);

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final UserCompanyRoleRepository userCompanyRoleRepository;
    private final AuthService authService;
    private final JwtService jwtService;

    public DemoAuthService(CompanyRepository companyRepository,
                            UserRepository userRepository,
                            UserCompanyRoleRepository userCompanyRoleRepository,
                            AuthService authService,
                            JwtService jwtService) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.userCompanyRoleRepository = userCompanyRoleRepository;
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @Transactional
    public Map<String, Object> demoLogin(String demoCode) {
        LOG.info("V8.2 — Demo login pour demoCode={}", demoCode);

        // 1. Trouver l'entreprise démo par son nom
        String companyName = demoCodeToCompanyName(demoCode);
        Company company = companyRepository.findAll().stream()
            .filter(c -> companyName.equals(c.getName()))
            .filter(c -> Boolean.TRUE.equals(c.getIsDemo()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("DEMO_COMPANY_NOT_FOUND",
                "Entreprise démo introuvable : " + demoCode));

        // 2. Trouver l'utilisateur OWNER de cette entreprise
        List<UserCompanyRole> roles = userCompanyRoleRepository.findByCompanyId(company.getId());
        if (roles.isEmpty()) {
            throw new NotFoundException("DEMO_USER_NOT_FOUND",
                "Aucun utilisateur associé à l'entreprise démo : " + demoCode);
        }

        UserCompanyRole ownerRole = roles.stream()
            .filter(r -> r.getRole() == UserRole.OWNER)
            .findFirst()
            .orElse(roles.get(0));

        User user = userRepository.findById(ownerRole.getUserId())
            .orElseThrow(() -> new NotFoundException("DEMO_USER_NOT_FOUND",
                "Utilisateur démo introuvable"));

        // 3. Générer les tokens
        List<Map<String, Object>> companiesClaim = authService.buildCompaniesClaimPublic(user.getId());
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), companiesClaim);
        String refreshToken = authService.issueRefreshTokenPublic(user.getId());

        // 4. Construire la réponse
        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        result.put("tokenType", "Bearer");
        result.put("expiresIn", 3600L);
        result.put("userId", user.getId().toString());
        result.put("email", user.getEmail());
        result.put("fullName", user.getFullName());
        result.put("companyId", company.getId().toString());
        result.put("companyName", company.getName());
        result.put("companies", companiesClaim);
        result.put("mfaRequired", false);

        LOG.info("V8.2 — Demo login réussi : user={}, company={}", user.getEmail(), company.getName());
        return result;
    }

    private String demoCodeToCompanyName(String demoCode) {
        return switch (demoCode) {
            case "BOUTIK_LAKAY" -> "Boutik Lakay S.A.";
            case "MOISE_ASSOCIES" -> "Moïse & Associés Conseil S.A.";
            case "ESPWA_POU_AYITI" -> "Espwa pou Ayiti";
            case "CARIBBEAN_TEXTILES" -> "Caribbean Textiles S.A.";
            default -> throw new NotFoundException("DEMO_CODE_UNKNOWN",
                "Code démo inconnu : " + demoCode);
        };
    }
}
