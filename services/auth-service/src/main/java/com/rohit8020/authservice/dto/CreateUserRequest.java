package com.rohit8020.authservice.dto;

import com.rohit8020.authservice.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotNull UserRole role
) {
}
