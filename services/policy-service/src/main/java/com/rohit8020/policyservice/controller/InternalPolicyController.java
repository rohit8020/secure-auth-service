package com.rohit8020.policyservice.controller;

import com.rohit8020.policyservice.dto.PolicyProjectionResponse;
import com.rohit8020.policyservice.service.PolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/policies")
public class InternalPolicyController {

    private final PolicyService policyService;

    public InternalPolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/{policyId}/projection")
    public ResponseEntity<PolicyProjectionResponse> getProjection(@PathVariable String policyId) {
        return ResponseEntity.ok(policyService.getProjection(policyId));
    }
}
