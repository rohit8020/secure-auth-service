package com.rohit8020.policyservice.security;

import com.rohit8020.policyservice.entity.UserRole;

public record AuthenticatedActor(
        Long userId,
        String username,
        UserRole role
) {
}
