package com.rohit8020.claimsservice.controller;

import com.rohit8020.claimsservice.dto.ClaimDecisionRequest;
import com.rohit8020.claimsservice.dto.ClaimResponse;
import com.rohit8020.claimsservice.dto.SubmitClaimRequest;
import com.rohit8020.claimsservice.service.ClaimsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/claims")
public class ClaimController {

    private final ClaimsService claimsService;

    public ClaimController(ClaimsService claimsService) {
        this.claimsService = claimsService;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submit(Authentication authentication,
                                                @Valid @RequestBody SubmitClaimRequest request) {
        return ResponseEntity.ok(claimsService.submit(authentication, request));
    }

    @PostMapping("/{claimId}/verify")
    public ResponseEntity<ClaimResponse> verify(Authentication authentication,
                                                @PathVariable String claimId,
                                                @RequestBody(required = false) ClaimDecisionRequest request) {
        return ResponseEntity.ok(claimsService.verify(authentication, claimId, request));
    }

    @PostMapping("/{claimId}/approve")
    public ResponseEntity<ClaimResponse> approve(Authentication authentication,
                                                 @PathVariable String claimId,
                                                 @RequestBody(required = false) ClaimDecisionRequest request) {
        return ResponseEntity.ok(claimsService.approve(authentication, claimId, request));
    }

    @PostMapping("/{claimId}/reject")
    public ResponseEntity<ClaimResponse> reject(Authentication authentication,
                                                @PathVariable String claimId,
                                                @RequestBody(required = false) ClaimDecisionRequest request) {
        return ResponseEntity.ok(claimsService.reject(authentication, claimId, request));
    }

    @GetMapping("/{claimId}")
    public ResponseEntity<ClaimResponse> get(Authentication authentication, @PathVariable String claimId) {
        return ResponseEntity.ok(claimsService.get(authentication, claimId));
    }

    @GetMapping
    public ResponseEntity<List<ClaimResponse>> list(Authentication authentication) {
        return ResponseEntity.ok(claimsService.list(authentication));
    }
}
