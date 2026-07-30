package jo.accountant.auth.service;

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
    private final NotificationChannelPort notificationChannel;

    public UserCompanyRoleService(UserCompanyRoleRepository userCompanyRoleRepository,
                                  UserRepository userRepository,
                                  NotificationChannelPort notificationChannel) {
        this.userCompanyRoleRepository = userCompanyRoleRepository;
        this.userRepository = userRepository;
        this.notificationChannel = notificationChannel;
    }

    @Transactional
    public UserCompanyRole inviteUser(UUID companyId, String email, UserRole role) {
        if (email == null || email.isBlank()) {
            throw new ValidationException("EMAIL_REQUIRED", "Email is required");
        }
        if (role == UserRole.OWNER) {
            throw new ForbiddenException("OWNER_NOT_INVITABLE",
                "OWNER role cannot be assigned via invitation");
        }

        User invitee = userRepository.findByEmailIgnoreCase(email.trim())
            .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND",
                "No user is registered with email " + email));

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
