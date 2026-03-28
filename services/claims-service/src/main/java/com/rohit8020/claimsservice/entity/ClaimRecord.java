package com.rohit8020.claimsservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

@RedisHash("claims")
@Getter
@Setter
@NoArgsConstructor
public class ClaimRecord {

    @Id
    private String id;

    @Indexed
    private String policyId;

    @Indexed
    private Long policyholderId;

    @Indexed
    private Long assignedAgentId;

    private String description;

    private BigDecimal claimAmount;

    @Indexed
    private ClaimStatus status;

    private String decisionNotes;

    private Instant createdAt;

    private Instant updatedAt;
}
