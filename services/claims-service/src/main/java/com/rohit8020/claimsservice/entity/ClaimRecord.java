package com.rohit8020.claimsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "claims", indexes = {
        @Index(name = "idx_claims_policy_id", columnList = "policyId"),
        @Index(name = "idx_claims_policyholder_id", columnList = "policyholderId"),
        @Index(name = "idx_claims_assigned_agent_id", columnList = "assignedAgentId"),
        @Index(name = "idx_claims_status", columnList = "status"),
        @Index(name = "idx_claims_created_at", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class ClaimRecord {

    @Id
    private String id;

    @Column(nullable = false)
    private String policyId;

    @Column(nullable = false)
    private Long policyholderId;

    @Column(nullable = false)
    private Long assignedAgentId;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal claimAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    private String decisionNotes;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
