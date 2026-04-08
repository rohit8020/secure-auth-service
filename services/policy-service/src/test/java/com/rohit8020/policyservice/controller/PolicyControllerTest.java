package com.rohit8020.policyservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rohit8020.platformcommon.api.PagedResponse;
import com.rohit8020.policyservice.dto.IssuePolicyRequest;
import com.rohit8020.policyservice.dto.PolicyResponse;
import com.rohit8020.policyservice.dto.RenewPolicyRequest;
import com.rohit8020.policyservice.service.PolicyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class PolicyControllerTest {

    @Mock
    private PolicyService policyService;

    @Mock
    private Authentication authentication;

    @Test
    void issuePolicyDelegatesToService() {
        PolicyController controller = new PolicyController(policyService);
        IssuePolicyRequest request = new IssuePolicyRequest(
                22L, 44L, new BigDecimal("199.99"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        PolicyResponse response = response();
        when(policyService.issuePolicy(authentication, request, "idem")).thenReturn(response);

        assertThat(controller.issuePolicy(request, "idem", authentication).getBody()).isEqualTo(response);
        verify(policyService).issuePolicy(authentication, request, "idem");
    }

    @Test
    void renewPolicyDelegatesToService() {
        PolicyController controller = new PolicyController(policyService);
        RenewPolicyRequest request = new RenewPolicyRequest(LocalDate.of(2026, 3, 1), new BigDecimal("299.99"));
        PolicyResponse response = response();
        when(policyService.renewPolicy(authentication, "policy-1", request)).thenReturn(response);

        assertThat(controller.renewPolicy("policy-1", request, authentication).getBody()).isEqualTo(response);
        verify(policyService).renewPolicy(authentication, "policy-1", request);
    }

    @Test
    void lapsePolicyDelegatesToService() {
        PolicyController controller = new PolicyController(policyService);
        PolicyResponse response = response();
        when(policyService.lapsePolicy(authentication, "policy-1")).thenReturn(response);

        assertThat(controller.lapsePolicy("policy-1", authentication).getBody()).isEqualTo(response);
        verify(policyService).lapsePolicy(authentication, "policy-1");
    }

    @Test
    void getPolicyDelegatesToService() {
        PolicyController controller = new PolicyController(policyService);
        PolicyResponse response = response();
        when(policyService.getPolicy(authentication, "policy-1")).thenReturn(response);

        assertThat(controller.getPolicy("policy-1", authentication).getBody()).isEqualTo(response);
        verify(policyService).getPolicy(authentication, "policy-1");
    }

    @Test
    void listPoliciesDelegatesToService() {
        PolicyController controller = new PolicyController(policyService);
        PagedResponse<PolicyResponse> response = new PagedResponse<>(List.of(response()), 0, 20, 1, 1, "createdAt,desc");
        when(policyService.listPolicies(authentication, 0, 20, "createdAt,desc")).thenReturn(response);

        controller.listPolicies(authentication, 0, 20, "createdAt,desc");
        verify(policyService).listPolicies(authentication, 0, 20, "createdAt,desc");
    }

    private PolicyResponse response() {
        return new PolicyResponse(
                "policy-1",
                "POL-12345678",
                22L,
                44L,
                "ISSUED",
                new BigDecimal("199.99"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
