package com.rohit8020.policyservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IssuePolicyRequest(
        @NotNull Long policyholderId,
        @NotNull Long assignedAgentId,
        @NotNull @DecimalMin("0.01") BigDecimal premium,
        @NotNull LocalDate startDate,
        @NotNull @Future LocalDate endDate
) {
}
