package com.rohit8020.policyservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PolicyResponse(
        String id,
        String policyNumber,
        Long policyholderId,
        Long assignedAgentId,
        String status,
        BigDecimal premium,
        LocalDate startDate,
        LocalDate endDate,
        Instant createdAt,
        Instant updatedAt
) {
}
