package com.rohit8020.policyservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rohit8020.policyservice.dto.PolicyProjectionResponse;
import com.rohit8020.policyservice.service.PolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalPolicyControllerTest {

    @Mock
    private PolicyService policyService;

    @Test
    void getProjectionDelegatesToService() {
        InternalPolicyController controller = new InternalPolicyController(policyService);
        PolicyProjectionResponse response = new PolicyProjectionResponse("policy-1", 22L, 44L, "ISSUED", true);
        when(policyService.getProjection("policy-1")).thenReturn(response);

        assertThat(controller.getProjection("policy-1").getBody()).isEqualTo(response);
        verify(policyService).getProjection("policy-1");
    }
}
