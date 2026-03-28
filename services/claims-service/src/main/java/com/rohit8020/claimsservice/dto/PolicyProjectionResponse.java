package com.rohit8020.claimsservice.dto;

public record PolicyProjectionResponse(
        String policyId,
        Long policyholderId,
        Long assignedAgentId,
        String status,
        boolean claimable
) {
}
