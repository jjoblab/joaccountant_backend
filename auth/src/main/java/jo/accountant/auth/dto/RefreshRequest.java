package jo.accountant.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RefreshRequest.
 *
 * @author jo@Dev


 */

public record RefreshRequest(@NotBlank String refreshToken) {}
