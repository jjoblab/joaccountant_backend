package jo.accountant.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * ForgotPasswordRequest.
 *
 * @author jo@Dev


 */

public record ForgotPasswordRequest(@NotBlank @Email String email) {}
