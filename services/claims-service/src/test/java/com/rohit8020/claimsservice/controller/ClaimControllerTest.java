package com.rohit8020.claimsservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rohit8020.claimsservice.dto.ClaimDecisionRequest;
import com.rohit8020.claimsservice.dto.ClaimResponse;
import com.rohit8020.claimsservice.dto.SubmitClaimRequest;
import com.rohit8020.claimsservice.service.ClaimsService;
import com.rohit8020.platformcommon.api.PagedResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class ClaimControllerTest {

    @Mock
    private ClaimsService claimsService;

    @Mock
    private Authentication authentication;

    @Test
    void submitDelegatesToService() {
        ClaimController controller = new ClaimController(claimsService);
        SubmitClaimRequest request = new SubmitClaimRequest("policy-1", "Broken windshield", new BigDecimal("450.00"));
        ClaimResponse response = response();
        when(claimsService.submit(authentication, request, "idem")).thenReturn(response);

        assertThat(controller.submit(authentication, "idem", request).getBody()).isEqualTo(response);
        verify(claimsService).submit(authentication, request, "idem");
    }

    @Test
    void verifyDelegatesToService() {
        ClaimController controller = new ClaimController(claimsService);
        ClaimDecisionRequest request = new ClaimDecisionRequest("checked");
        ClaimResponse response = response();
        when(claimsService.verify(authentication, "claim-1", request)).thenReturn(response);

        assertThat(controller.verify(authentication, "claim-1", request).getBody()).isEqualTo(response);
        verify(claimsService).verify(authentication, "claim-1", request);
    }

    @Test
    void approveDelegatesToService() {
        ClaimController controller = new ClaimController(claimsService);
        ClaimDecisionRequest request = new ClaimDecisionRequest("approved");
        ClaimResponse response = response();
        when(claimsService.approve(authentication, "claim-1", request)).thenReturn(response);

        assertThat(controller.approve(authentication, "claim-1", request).getBody()).isEqualTo(response);
        verify(claimsService).approve(authentication, "claim-1", request);
    }

    @Test
    void rejectDelegatesToService() {
        ClaimController controller = new ClaimController(claimsService);
        ClaimResponse response = response();
        when(claimsService.reject(authentication, "claim-1", null)).thenReturn(response);

        assertThat(controller.reject(authentication, "claim-1", null).getBody()).isEqualTo(response);
        verify(claimsService).reject(authentication, "claim-1", null);
    }

    @Test
    void getDelegatesToService() {
        ClaimController controller = new ClaimController(claimsService);
        ClaimResponse response = response();
        when(claimsService.get(authentication, "claim-1")).thenReturn(response);

        assertThat(controller.get(authentication, "claim-1").getBody()).isEqualTo(response);
        verify(claimsService).get(authentication, "claim-1");
    }

    @Test
    void listDelegatesToService() {
        ClaimController controller = new ClaimController(claimsService);
        PagedResponse<ClaimResponse> response = new PagedResponse<>(List.of(response()), 0, 20, 1, 1, "createdAt,desc");
        when(claimsService.list(authentication, 0, 20, "createdAt,desc")).thenReturn(response);

        controller.list(authentication, 0, 20, "createdAt,desc");
        verify(claimsService).list(authentication, 0, 20, "createdAt,desc");
    }

    private ClaimResponse response() {
        return new ClaimResponse(
                "claim-1",
                "policy-1",
                7L,
                99L,
                "SUBMITTED",
                new BigDecimal("450.00"),
                "Broken windshield",
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
