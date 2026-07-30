package jo.accountant.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jo.accountant.auth.entity.UserRole;

public record InviteUserRequest(
    @NotBlank @Email String email,
    @NotNull UserRole role
) {}
