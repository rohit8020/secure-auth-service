package com.rohit8020.claimsservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rohit8020.claimsservice.dto.ClaimResponse;
import com.rohit8020.claimsservice.dto.SubmitClaimRequest;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import com.rohit8020.claimsservice.entity.UserRole;
import com.rohit8020.claimsservice.exception.ApiException;
import com.rohit8020.claimsservice.repository.ClaimRepository;
import com.rohit8020.claimsservice.repository.PolicyProjectionRepository;
import com.rohit8020.claimsservice.service.ClaimsService;
import com.rohit8020.claimsservice.service.PolicyProjectionCache;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@SpringBootTest
class ClaimsServiceIntegrationTest {

    @Autowired
    private ClaimsService claimsService;

    @Autowired
    private PolicyProjectionRepository policyProjectionRepository;

    @Autowired
    private ClaimRepository claimRepository;

    @MockBean
    private PolicyProjectionCache policyProjectionCache;

    @BeforeEach
    void setUpProjection() {
        if (policyProjectionRepository.findById("policy-123").isEmpty()) {
            PolicyProjection projection = new PolicyProjection();
            projection.setId("policy-123");
            projection.setPolicyholderId(7L);
            projection.setAssignedAgentId(99L);
            projection.setStatus("ISSUED");
            projection.setClaimable(true);
            projection.setUpdatedAt(Instant.now());
            policyProjectionRepository.save(projection);
        }
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalClaim() {
        JwtAuthenticationToken policyholder = authentication(7L, "policyholder", UserRole.POLICYHOLDER);
        SubmitClaimRequest request = new SubmitClaimRequest("policy-123", "Broken windshield",
                new BigDecimal("450.00"));

        ClaimResponse first = claimsService.submit(policyholder, request, "submit-claim-key");
        ClaimResponse replayed = claimsService.submit(policyholder, request, "submit-claim-key");

        assertThat(first.id()).isEqualTo(replayed.id());
        assertThat(claimRepository.count()).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        JwtAuthenticationToken policyholder = authentication(7L, "policyholder", UserRole.POLICYHOLDER);

        claimsService.submit(policyholder, new SubmitClaimRequest("policy-123", "Broken windshield",
                new BigDecimal("450.00")), "submit-claim-key-2");

        assertThatThrownBy(() -> claimsService.submit(policyholder,
                new SubmitClaimRequest("policy-123", "Different incident", new BigDecimal("451.00")),
                "submit-claim-key-2"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    private JwtAuthenticationToken authentication(Long userId, String username, UserRole role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(username)
                .claim("userId", userId)
                .claim("role", role.name())
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
