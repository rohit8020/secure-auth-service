package com.rohit8020.authservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.JWKSet;
import com.rohit8020.authservice.dto.ClientCredentialsTokenResponse;
import com.rohit8020.authservice.exception.ApiException;
import com.rohit8020.authservice.security.JwtUtil;
import com.rohit8020.authservice.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@ExtendWith(MockitoExtension.class)
class OAuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private JwtUtil jwtUtil;

    @Test
    void tokenRejectsUnsupportedGrantType() {
        OAuthController controller = new OAuthController(authService, jwtUtil);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");

        assertThatThrownBy(() -> controller.token(null, form))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tokenUsesFormCredentialsWhenAuthorizationHeaderMissing() {
        OAuthController controller = new OAuthController(authService, jwtUtil);
        MultiValueMap<String, String> form = form("client_credentials", "claims-service", "claims-secret", "claims.write");
        ClientCredentialsTokenResponse response = new ClientCredentialsTokenResponse(
                "access", "Bearer", 300, "claims.write");
        when(authService.issueClientCredentialsToken("claims-service", "claims-secret", "claims.write"))
                .thenReturn(response);

        assertThat(controller.token(null, form).getBody()).isEqualTo(response);
        verify(authService).issueClientCredentialsToken("claims-service", "claims-secret", "claims.write");
    }

    @Test
    void tokenUsesBasicAuthorizationCredentialsWhenPresent() {
        OAuthController controller = new OAuthController(authService, jwtUtil);
        MultiValueMap<String, String> form = form("client_credentials", "ignored", "ignored", "claims.write");
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString("claims-service:claims-secret".getBytes(StandardCharsets.UTF_8));
        ClientCredentialsTokenResponse response = new ClientCredentialsTokenResponse(
                "access", "Bearer", 300, "claims.write");
        when(authService.issueClientCredentialsToken("claims-service", "claims-secret", "claims.write"))
                .thenReturn(response);

        assertThat(controller.token(authorization, form).getBody()).isEqualTo(response);
        verify(authService).issueClientCredentialsToken("claims-service", "claims-secret", "claims.write");
    }

    @Test
    void jwksReturnsSerializedJwkSet() {
        OAuthController controller = new OAuthController(authService, jwtUtil);
        Map<String, Object> jwks = new JWKSet().toJSONObject();
        when(jwtUtil.jwkSet()).thenReturn(new JWKSet());

        assertThat(controller.jwks().getBody()).isEqualTo(jwks);
    }

    private MultiValueMap<String, String> form(String grantType, String clientId, String clientSecret, String scope) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", scope);
        return form;
    }
}
