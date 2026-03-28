package com.rohit8020.claimsservice.service;

import com.rohit8020.claimsservice.dto.PolicyProjectionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PolicyProjectionClient {

    private final RestClient restClient;

    public PolicyProjectionClient(@Value("${app.policy-service.base-url}") String baseUrl,
                                  @Value("${app.policy-service.internal-api-key}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Api-Key", apiKey)
                .build();
    }

    public PolicyProjectionResponse getProjection(String policyId) {
        return restClient.get()
                .uri("/internal/policies/{policyId}/projection", policyId)
                .retrieve()
                .body(PolicyProjectionResponse.class);
    }
}
