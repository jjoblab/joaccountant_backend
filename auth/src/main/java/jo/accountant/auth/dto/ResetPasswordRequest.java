package jo.accountant.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ResetPasswordRequest.
 *
 * @author jo@Dev


 */

public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank String newPassword
) {}
