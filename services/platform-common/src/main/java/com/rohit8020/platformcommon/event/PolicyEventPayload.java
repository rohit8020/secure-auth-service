package com.rohit8020.platformcommon.event;

import java.time.LocalDate;

public record PolicyEventPayload(
        String policyNumber,
        Long policyholderId,
        Long assignedAgentId,
        String status,
        Long actorId,
        String actorRole,
        LocalDate startDate,
        LocalDate endDate
) {
}
