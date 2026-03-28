package com.rohit8020.claimsservice.entity;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@RedisHash("policy-projections")
@Getter
@Setter
@NoArgsConstructor
public class PolicyProjection {

    @Id
    private String id;

    private Long policyholderId;

    private Long assignedAgentId;

    private String status;

    private boolean claimable;

    private Instant updatedAt;
}
