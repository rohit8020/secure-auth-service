package com.rohit8020.policyservice.security;

import com.rohit8020.policyservice.entity.UserRole;
import com.rohit8020.policyservice.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SecurityUtils {

    public AuthenticatedActor currentActor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        Number userId = jwt.getClaim("userId");
        String role = jwt.getClaimAsString("role");
        return new AuthenticatedActor(userId.longValue(), jwt.getSubject(), UserRole.valueOf(role));
    }
}
