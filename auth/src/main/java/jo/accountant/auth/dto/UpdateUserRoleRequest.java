package jo.accountant.auth.dto;

import jakarta.validation.constraints.NotNull;
import jo.accountant.auth.entity.UserRole;

public record UpdateUserRoleRequest(@NotNull UserRole role) {}
