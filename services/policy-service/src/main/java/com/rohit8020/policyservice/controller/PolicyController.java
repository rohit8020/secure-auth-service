package com.rohit8020.policyservice.controller;

import com.rohit8020.policyservice.dto.IssuePolicyRequest;
import com.rohit8020.policyservice.dto.PolicyResponse;
import com.rohit8020.policyservice.dto.RenewPolicyRequest;
import com.rohit8020.policyservice.service.PolicyService;
import com.rohit8020.platformcommon.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    public ResponseEntity<PolicyResponse> issuePolicy(@Valid @RequestBody IssuePolicyRequest request,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      Authentication authentication) {
        return ResponseEntity.ok(policyService.issuePolicy(authentication, request, idempotencyKey));
    }

    @PostMapping("/{policyId}/renew")
    public ResponseEntity<PolicyResponse> renewPolicy(@PathVariable String policyId,
                                                      @Valid @RequestBody RenewPolicyRequest request,
                                                      Authentication authentication) {
        return ResponseEntity.ok(policyService.renewPolicy(authentication, policyId, request));
    }

    @PostMapping("/{policyId}/lapse")
    public ResponseEntity<PolicyResponse> lapsePolicy(@PathVariable String policyId,
                                                      Authentication authentication) {
        return ResponseEntity.ok(policyService.lapsePolicy(authentication, policyId));
    }

    @GetMapping("/{policyId}")
    public ResponseEntity<PolicyResponse> getPolicy(@PathVariable String policyId,
                                                    Authentication authentication) {
        return ResponseEntity.ok(policyService.getPolicy(authentication, policyId));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<PolicyResponse>> listPolicies(Authentication authentication,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "20") int size,
                                                                      @RequestParam(defaultValue = "createdAt,desc")
                                                                      String sort) {
        return ResponseEntity.ok(policyService.listPolicies(authentication, page, size, sort));
    }
}
