package com.rohit8020.claimsservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rohit8020.claimsservice.dto.PolicyProjectionResponse;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import com.rohit8020.claimsservice.exception.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class PolicyProjectionClientTest {

    @Mock
    private ClientCredentialsTokenProvider tokenProvider;

    @Mock
    private PolicyProjectionCache policyProjectionCache;

    @Test
    void getProjectionCallsPolicyServiceWithBearerToken() throws Exception {
        PolicyProjectionClient client = new PolicyProjectionClient(
                "http://policy-service",
                2_000,
                3_000,
                tokenProvider,
                policyProjectionCache
        );
        MockRestServiceServer server = bind(client);
        when(tokenProvider.getAccessToken()).thenReturn("machine-token");
        server.expect(requestTo("http://policy-service/internal/policies/policy-1/projection"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer machine-token"))
                .andRespond(withSuccess(
                        """
                                {
                                  "policyId": "policy-1",
                                  "policyholderId": 7,
                                  "assignedAgentId": 99,
                                  "status": "ISSUED",
                                  "claimable": true
                                }
                                """,
                        MediaType.APPLICATION_JSON));

        PolicyProjectionResponse response = client.getProjection("policy-1");

        assertThat(response).isEqualTo(new PolicyProjectionResponse("policy-1", 7L, 99L, "ISSUED", true));
        server.verify();
    }

    @Test
    void fallbackReturnsCachedProjection() {
        PolicyProjection projection = new PolicyProjection();
        projection.setId("policy-1");
        projection.setPolicyholderId(7L);
        projection.setAssignedAgentId(99L);
        projection.setStatus("ISSUED");
        projection.setClaimable(true);
        when(policyProjectionCache.get("policy-1")).thenReturn(Optional.of(projection));

        PolicyProjectionClient client = new PolicyProjectionClient(
                "http://localhost:8082",
                500,
                500,
                tokenProvider,
                policyProjectionCache
        );

        PolicyProjectionResponse response = ReflectionTestUtils.invokeMethod(
                client, "fallbackGetProjection", "policy-1", new RuntimeException("boom"));

        assertThat(response).isEqualTo(new PolicyProjectionResponse("policy-1", 7L, 99L, "ISSUED", true));
    }

    @Test
    void fallbackThrowsServiceUnavailableWhenCacheMisses() {
        when(policyProjectionCache.get("policy-1")).thenReturn(Optional.empty());
        PolicyProjectionClient client = new PolicyProjectionClient(
                "http://localhost:8082",
                500,
                500,
                tokenProvider,
                policyProjectionCache
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                client, "fallbackGetProjection", "policy-1", new RuntimeException("boom")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private MockRestServiceServer bind(PolicyProjectionClient client) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://policy-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        return server;
    }
}
