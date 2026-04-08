package com.rohit8020.policyservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rohit8020.policyservice.entity.UserRole;
import com.rohit8020.policyservice.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SecurityUtilsTest {

    private final SecurityUtils securityUtils = new SecurityUtils();

    @Test
    void currentActorBuildsActorFromJwtClaims() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("agent")
                .claim("userId", 44L)
                .claim("role", "AGENT")
                .build();

        AuthenticatedActor actor = securityUtils.currentActor(new JwtAuthenticationToken(jwt));

        assertThat(actor).isEqualTo(new AuthenticatedActor(44L, "agent", UserRole.AGENT));
    }

    @Test
    void currentActorRejectsNullAuthentication() {
        assertThatThrownBy(() -> securityUtils.currentActor(null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentActorRejectsNonJwtPrincipal() {
        Authentication authentication = Mockito.mock(Authentication.class);
        Mockito.when(authentication.getPrincipal()).thenReturn("not-a-jwt");

        assertThatThrownBy(() -> securityUtils.currentActor(authentication))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
