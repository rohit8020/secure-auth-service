package com.rohit8020.claimsservice.service;

import com.rohit8020.claimsservice.dto.PolicyProjectionResponse;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import com.rohit8020.claimsservice.exception.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class PolicyProjectionClient {

    private final RestClient restClient;
    private final ClientCredentialsTokenProvider tokenProvider;
    private final PolicyProjectionCache policyProjectionCache;

    public PolicyProjectionClient(@Value("${app.policy-service.base-url}") String baseUrl,
                                  @Value("${app.policy-service.connect-timeout-ms:2000}") long connectTimeout,
                                  @Value("${app.policy-service.read-timeout-ms:3000}") long readTimeout,
                                  ClientCredentialsTokenProvider tokenProvider,
                                  PolicyProjectionCache policyProjectionCache) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeout))
                .withReadTimeout(Duration.ofMillis(readTimeout));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
        this.tokenProvider = tokenProvider;
        this.policyProjectionCache = policyProjectionCache;
    }

    @Retry(name = "policy-service")
    @CircuitBreaker(name = "policy-service", fallbackMethod = "fallbackGetProjection")
    public PolicyProjectionResponse getProjection(String policyId) {
        return restClient.get()
                .uri("/internal/policies/{policyId}/projection", policyId)
                .headers(headers -> headers.setBearerAuth(tokenProvider.getAccessToken()))
                .retrieve()
                .body(PolicyProjectionResponse.class);
    }

    @SuppressWarnings("unused")
    private PolicyProjectionResponse fallbackGetProjection(String policyId, Throwable throwable) {
        return policyProjectionCache.get(policyId)
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Policy projection unavailable"));
    }

    private PolicyProjectionResponse toResponse(PolicyProjection projection) {
        return new PolicyProjectionResponse(
                projection.getId(),
                projection.getPolicyholderId(),
                projection.getAssignedAgentId(),
                projection.getStatus(),
                projection.isClaimable()
        );
    }
}
