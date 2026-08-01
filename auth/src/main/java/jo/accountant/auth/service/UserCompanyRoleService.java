package jo.accountant.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.auth.entity.User;
import jo.accountant.auth.entity.UserCompanyRole;
import jo.accountant.auth.entity.UserRole;
import jo.accountant.auth.repository.UserCompanyRoleRepository;
import jo.accountant.auth.repository.UserRepository;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.core.exception.ForbiddenException;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gère les lignes {@link UserCompanyRole} : invitation, mise à jour de rôle, listing des rôles.
 *
 * <p>§3.4 : un utilisateur peut avoir un rôle différent dans chaque société. Le rôle est stocké
 * par (userId, companyId) et le JWT transporte la liste au login.
 */
@Service
public class UserCompanyRoleService {

    private final UserCompanyRoleRepository userCompanyRoleRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final NotificationChannelPort notificationChannel;
    private final SecureRandom rng = new SecureRandom();

    public UserCompanyRoleService(UserCompanyRoleRepository userCompanyRoleRepository,
                                  UserRepository userRepository,
                                  AuthService authService,
                                  NotificationChannelPort notificationChannel) {
        this.userCompanyRoleRepository = userCompanyRoleRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.notificationChannel = notificationChannel;
    }

    /**
     * Invite un utilisateur dans une société (V2.6.0 — wizard refonte).
     *
     * <p>Deux cas :
     * <ol>
     *   <li><b>Utilisateur existant</b> ({@code email} trouvé en base) : on crée juste la
     *       ligne {@link UserCompanyRole} ({@code acceptedAt = null} en attente d'acceptation).
     *       {@code fullName} est ignoré (on conserve le {@code fullName} existant).</li>
     *   <li><b>Utilisateur inexistant</b> : on crée le compte via
     *       {@link AuthService#register} avec un mot de passe temporaire aléatoire fort
     *       (24 chars, 4 classes de caractères — passe {@code PasswordValidator}).
     *       {@code fullName} est alors <strong>requis</strong> (422
     *       {@code FULL_NAME_REQUIRED} si blank). L'utilisateur devra utiliser le flux
     *       « mot de passe oublié » pour définir son vrai mot de passe.</li>
     * </ol>
     *
     * <p>Dans les deux cas, un email d'invitation est envoyé via
     * {@link NotificationChannelPort} (template {@code user-invitation}).
     *
     * @throws ForbiddenException  si {@code role == OWNER} ({@code OWNER_NOT_INVITABLE}).
     * @throws ValidationException si l'utilisateur n'existe pas et {@code fullName} est blank.
     * @throws ConflictException   si l'utilisateur a déjà un rôle dans la société.
     */
    @Transactional
    public UserCompanyRole inviteUser(UUID companyId, String email, String fullName, UserRole role) {
        if (email == null || email.isBlank()) {
            throw new ValidationException("EMAIL_REQUIRED", "Email is required");
        }
        if (role == UserRole.OWNER) {
            throw new ForbiddenException("OWNER_NOT_INVITABLE",
                "OWNER role cannot be assigned via invitation");
        }

        // Résolution de l'utilisateur : lookup par email, sinon création à la volée.
        User invitee = userRepository.findByEmailIgnoreCase(email.trim())
            .orElseGet(() -> {
                if (fullName == null || fullName.isBlank()) {
                    throw new ValidationException("FULL_NAME_REQUIRED",
                        "fullName is required to invite a user that does not have an account yet "
                        + "(email=" + email + ")");
                }
                // register() lève ConflictException si l'email existe déjà (race condition avec
                // le findByEmailIgnoreCase ci-dessus — peu probable mais géré proprement).
                // Mot de passe temporaire aléatoire fort : l'invité ne le connaît jamais, il
                // devra faire un « forgot password » pour définir le sien.
                String tempPassword = generateTempPassword();
                return authService.register(email, tempPassword, fullName, "fr");
            });

        if (userCompanyRoleRepository.findByUserIdAndCompanyId(invitee.getId(), companyId).isPresent()) {
            throw new ConflictException("USER_ALREADY_IN_COMPANY",
                "User already has a role in this company");
        }

        UserCompanyRole ucr = new UserCompanyRole();
        ucr.setId(UUID.randomUUID());
        ucr.setUserId(invitee.getId());
        ucr.setCompanyId(companyId);
        ucr.setRole(role);
        ucr.setInvitedAt(Instant.now());
        ucr.setCreatedAt(Instant.now());
        ucr.setUpdatedAt(Instant.now());
        ucr.setCreatedBy(TenantContext.getUserId());
        ucr.setUpdatedBy(TenantContext.getUserId());
        UserCompanyRole saved = userCompanyRoleRepository.save(ucr);

        Map<String, Object> vars = new HashMap<>();
        vars.put("email", invitee.getEmail());
        vars.put("fullName", invitee.getFullName());
        vars.put("role", role.name());
        vars.put("companyId", companyId.toString());
        notificationChannel.sendEmail(invitee.getEmail(), "user-invitation", vars);

        return saved;
    }

    /**
     * Génère un mot de passe temporaire aléatoire fort (24 caractères, 4 classes garanties).
     *
     * <p>Objectif : passer {@link jo.accountant.auth.validator.PasswordValidator} (longueur ≥ 12,
     * majuscule + minuscule + chiffre + spécial, hors blacklist). L'invité ne voit jamais ce
     * mot de passe — il devra utiliser le flux « forgot password » pour définir le sien.
     *
     * <p>Caractères ambigus retirés des alphabets ({@code I, O, l, o, 0, 1}) pour faciliter
     * la lecture humaine si jamais le mot de passe devait être communiqué par téléphone
     * (cas marginal — normalement le flux forgot password suffit).
     */
    private String generateTempPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";  // no I, O
        String lower = "abcdefghijkmnpqrstuvwxyz";  // no l, o
        String digit = "23456789";                   // no 0, 1
        String special = "!@#$%^&*-_=+";
        String all = upper + lower + digit + special;
        StringBuilder sb = new StringBuilder(24);
        // Garantir une occurrence de chaque classe pour passer PasswordValidator.
        sb.append(upper.charAt(rng.nextInt(upper.length())));
        sb.append(lower.charAt(rng.nextInt(lower.length())));
        sb.append(digit.charAt(rng.nextInt(digit.length())));
        sb.append(special.charAt(rng.nextInt(special.length())));
        for (int i = 4; i < 24; i++) {
            sb.append(all.charAt(rng.nextInt(all.length())));
        }
        // Fisher-Yates shuffle pour éviter que les 4 premiers chars soient toujours dans
        // le même ordre (l'analyse de la position des classes révélerait le pattern).
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    @Transactional
    public UserCompanyRole updateRole(UUID companyId, UUID userId, UserRole newRole) {
        if (newRole == UserRole.OWNER) {
            throw new ForbiddenException("OWNER_NOT_INVITABLE",
                "OWNER role cannot be assigned via update");
        }
        UserCompanyRole ucr = userCompanyRoleRepository
            .findByUserIdAndCompanyId(userId, companyId)
            .orElseThrow(() -> new NotFoundException("UserCompanyRole", userId + "/" + companyId));

        if (ucr.getRole() == UserRole.OWNER) {
            throw new ForbiddenException("OWNER_ROLE_IMMUTABLE",
                "Cannot change the role of an OWNER");
        }

        ucr.setRole(newRole);
        ucr.setUpdatedAt(Instant.now());
        ucr.setUpdatedBy(TenantContext.getUserId());
        return userCompanyRoleRepository.save(ucr);
    }

    @Transactional
    public UserCompanyRole acceptInvitation(UUID userId, UUID companyId) {
        UserCompanyRole ucr = userCompanyRoleRepository
            .findByUserIdAndCompanyId(userId, companyId)
            .orElseThrow(() -> new NotFoundException("UserCompanyRole", userId + "/" + companyId));
        if (ucr.getAcceptedAt() != null) {
            throw new ConflictException("INVITATION_ALREADY_ACCEPTED",
                "Invitation has already been accepted");
        }
        ucr.setAcceptedAt(Instant.now());
        ucr.setUpdatedAt(Instant.now());
        return userCompanyRoleRepository.save(ucr);
    }

    @Transactional(readOnly = true)
    public List<UserCompanyRole> listForCompany(UUID companyId) {
        return userCompanyRoleRepository.findByCompanyId(companyId);
    }

    @Transactional(readOnly = true)
    public List<UserCompanyRole> listForUser(UUID userId) {
        return userCompanyRoleRepository.findByUserId(userId);
    }

    /**
     * Convertit une ligne {@link UserCompanyRole} en {@link jo.accountant.auth.dto.CompanyUserResponse}
     * en résolvant l'utilisateur joint (email + fullName).
     *
     * <p>V2.6.0 (wizard refonte) — exposé pour que les controllers {@code :company} n'aient
     * pas à dépendre directement de {@link UserRepository} (principe de séparation des
     * couches : le module {@code :company} parle à {@code :auth} via ses services, pas via
     * ses repositories).
     *
     * <p>Fait un lookup {@link UserRepository#findById} par ligne. Pour un listing complet
     * ({@link #listForCompanyAsResponse}), cela fait N+1 requêtes — acceptable car le nombre
     * d'utilisateurs par société est typiquement < 20 (limite souple métier). Si besoin
     * d'optimiser, ajouter une requête JPA JOIN dans le repository.
     */
    @Transactional(readOnly = true)
    public jo.accountant.auth.dto.CompanyUserResponse toCompanyUserResponse(UserCompanyRole ucr) {
        User user = userRepository.findById(ucr.getUserId())
            .orElseThrow(() -> new NotFoundException("User", ucr.getUserId()));
        return new jo.accountant.auth.dto.CompanyUserResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            ucr.getRole().name(),
            ucr.getInvitedAt(),
            ucr.getAcceptedAt()
        );
    }

    /**
     * Liste les utilisateurs d'une société sous forme de {@link jo.accountant.auth.dto.CompanyUserResponse}
     * (avec email + fullName résolus). Voir {@link #toCompanyUserResponse} pour la note perf.
     */
    @Transactional(readOnly = true)
    public List<jo.accountant.auth.dto.CompanyUserResponse> listForCompanyAsResponse(UUID companyId) {
        return userCompanyRoleRepository.findByCompanyId(companyId).stream()
            .map(this::toCompanyUserResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public void ensureRole(UUID userId, UUID companyId, UserRole minimumRole) {
        UserCompanyRole ucr = userCompanyRoleRepository
            .findByUserIdAndCompanyId(userId, companyId)
            .orElseThrow(() -> new ForbiddenException("NO_ROLE_IN_COMPANY",
                "You have no access to this company"));
        if (ucr.getAcceptedAt() == null) {
            throw new ForbiddenException("INVITATION_PENDING",
                "Invitation has not been accepted yet");
        }
        if (ucr.getRole().ordinal() > minimumRole.ordinal()) {
            throw new ForbiddenException("INSUFFICIENT_ROLE",
                "Required role: " + minimumRole + " — you have: " + ucr.getRole());
        }
    }

    @Transactional(readOnly = true)
    public boolean hasAccess(UUID userId, UUID companyId) {
        return userCompanyRoleRepository
            .findByUserIdAndCompanyId(userId, companyId)
            .map(ucr -> ucr.getAcceptedAt() != null)
            .orElse(false);
    }
}
