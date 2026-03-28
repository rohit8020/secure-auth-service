package com.rohit8020.claimsservice.event;

import java.math.BigDecimal;
import java.time.Instant;

public record ClaimEvent(
        String aggregateId,
        String policyId,
        Long policyholderId,
        Long assignedAgentId,
        String eventType,
        String status,
        Long actorId,
        String actorRole,
        Instant timestamp,
        BigDecimal claimAmount
) {
}
