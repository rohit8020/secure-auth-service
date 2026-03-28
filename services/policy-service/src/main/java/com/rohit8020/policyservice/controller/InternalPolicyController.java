package com.rohit8020.policyservice.controller;

import com.rohit8020.policyservice.dto.PolicyProjectionResponse;
import com.rohit8020.policyservice.service.PolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/policies")
public class InternalPolicyController {

    private final PolicyService policyService;
    private final String internalApiKey;

    public InternalPolicyController(PolicyService policyService,
                                    @Value("${app.internal-api-key}") String internalApiKey) {
        this.policyService = policyService;
        this.internalApiKey = internalApiKey;
    }

    @GetMapping("/{policyId}/projection")
    public ResponseEntity<PolicyProjectionResponse> getProjection(@PathVariable String policyId,
                                                                  @RequestHeader("X-Internal-Api-Key") String apiKey) {
        return ResponseEntity.ok(policyService.getProjection(policyId, apiKey, internalApiKey));
    }
}
