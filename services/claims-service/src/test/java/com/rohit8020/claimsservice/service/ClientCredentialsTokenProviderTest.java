package com.rohit8020.claimsservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class ClientCredentialsTokenProviderTest {

    private MockRestServiceServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.reset();
        }
    }

    @Test
    void getAccessTokenCachesSuccessfulResponse() {
        ClientCredentialsTokenProvider provider = provider();
        server = bind(provider);
        server.expect(requestTo("http://auth-service/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm()))
                .andRespond(withSuccess(tokenResponse("token-1"), MediaType.APPLICATION_JSON));

        assertThat(provider.getAccessToken()).isEqualTo("token-1");
        assertThat(provider.getAccessToken()).isEqualTo("token-1");
        server.verify();
    }

    @Test
    void getAccessTokenRefreshesWhenTokenIsNearExpiry() {
        ClientCredentialsTokenProvider provider = provider();
        server = bind(provider);
        server.expect(requestTo("http://auth-service/oauth2/token"))
                .andRespond(withSuccess(tokenResponse("token-1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://auth-service/oauth2/token"))
                .andRespond(withSuccess(tokenResponse("token-2"), MediaType.APPLICATION_JSON));

        assertThat(provider.getAccessToken()).isEqualTo("token-1");
        ReflectionTestUtils.setField(provider, "cachedToken", "old-token");
        ReflectionTestUtils.setField(provider, "expiresAt", Instant.now().plusSeconds(20));

        assertThat(provider.getAccessToken()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void getAccessTokenRejectsMissingAccessToken() {
        ClientCredentialsTokenProvider provider = provider();
        server = bind(provider);
        server.expect(requestTo("http://auth-service/oauth2/token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(provider::getAccessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retrieve OAuth2 access token");
        server.verify();
    }

    private ClientCredentialsTokenProvider provider() {
        return new ClientCredentialsTokenProvider(
                "http://auth-service",
                "claims-service",
                "claims-service-secret",
                "policy.projection.read"
        );
    }

    private MockRestServiceServer bind(ClientCredentialsTokenProvider provider) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-service");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(provider, "restClient", builder.build());
        return mockServer;
    }

    private MultiValueMap<String, String> expectedForm() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "claims-service");
        form.add("client_secret", "claims-service-secret");
        form.add("scope", "policy.projection.read");
        return form;
    }

    private String tokenResponse(String accessToken) {
        return """
                {
                  "access_token": "%s",
                  "token_type": "Bearer",
                  "expires_in": 120,
                  "scope": "policy.projection.read"
                }
                """.formatted(accessToken);
    }
}
