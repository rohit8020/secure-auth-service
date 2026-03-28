package com.rohit8020.claimsservice.security;

import com.rohit8020.claimsservice.entity.UserRole;

public record AuthenticatedActor(
        Long userId,
        String username,
        UserRole role
) {
}
