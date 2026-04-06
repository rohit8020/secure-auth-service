package com.rohit8020.claimsservice.service;

import com.rohit8020.claimsservice.dto.OAuthTokenResponse;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class ClientCredentialsTokenProvider {

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(@Value("${app.auth-service.base-url}") String authServiceBaseUrl,
                                          @Value("${app.auth-service.client-id}") String clientId,
                                          @Value("${app.auth-service.client-secret}") String clientSecret,
                                          @Value("${app.auth-service.scope}") String scope) {
        this.restClient = RestClient.builder()
                .baseUrl(authServiceBaseUrl)
                .build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", scope);

        OAuthTokenResponse response = restClient.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(OAuthTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Failed to retrieve OAuth2 access token");
        }

        cachedToken = response.accessToken();
        expiresAt = Instant.now().plusSeconds(response.expiresIn());
        return cachedToken;
    }
}
