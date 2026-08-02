package jo.accountant.auth.dto;

import java.util.UUID;

/**
 * RegisterResponse.
 *
 * @author jo@Dev


 */

public record RegisterResponse(UUID userId, String email, String fullName) {}
