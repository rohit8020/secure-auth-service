package com.rohit8020.claimsservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SubmitClaimRequest(
        @NotBlank String policyId,
        @NotBlank String description,
        @NotNull @DecimalMin("0.01") BigDecimal claimAmount
) {
}
