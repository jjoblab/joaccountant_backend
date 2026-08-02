package jo.accountant.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * RegisterRequest.
 *
 * @author jo@Dev


 */

public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    @NotBlank String fullName,
    String locale
) {}
