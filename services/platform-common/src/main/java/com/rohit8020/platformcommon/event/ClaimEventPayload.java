package com.rohit8020.platformcommon.event;

import java.math.BigDecimal;

public record ClaimEventPayload(
        String policyId,
        Long policyholderId,
        Long assignedAgentId,
        String status,
        Long actorId,
        String actorRole,
        BigDecimal claimAmount
) {
}
