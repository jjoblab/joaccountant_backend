package jo.accountant.auth.dto;

import jo.accountant.auth.entity.User;
import java.time.Instant;
import java.util.UUID;

/**
 * UserResponse — réponse de {@code GET/PATCH /api/v1/auth/me}.
 
 *
 * @author jo@Dev


*/
public record UserResponse(
    UUID userId,
    String email,
    String fullName,
    Instant createdAt,
    Instant updatedAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
