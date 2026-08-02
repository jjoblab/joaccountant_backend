package jo.accountant.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * LoginRequest.
 *
 * @author jo@Dev


 */

public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}
