package com.rohit8020.claimsservice.event;

import java.time.Instant;
import java.time.LocalDate;

public record PolicyEvent(
        String aggregateId,
        String policyNumber,
        Long policyholderId,
        Long assignedAgentId,
        String eventType,
        String status,
        Long actorId,
        String actorRole,
        Instant timestamp,
        LocalDate startDate,
        LocalDate endDate
) {
}
