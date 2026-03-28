package com.rohit8020.claimsservice.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ClaimResponse(
        String id,
        String policyId,
        Long policyholderId,
        Long assignedAgentId,
        String status,
        BigDecimal claimAmount,
        String description,
        String decisionNotes,
        Instant createdAt,
        Instant updatedAt
) {
}
