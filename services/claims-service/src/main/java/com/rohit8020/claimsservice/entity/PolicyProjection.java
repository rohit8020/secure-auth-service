package com.rohit8020.claimsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "policy_projections")
@Getter
@Setter
@NoArgsConstructor
public class PolicyProjection {

    @Id
    private String id;

    @Column(nullable = false)
    private Long policyholderId;

    @Column(nullable = false)
    private Long assignedAgentId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private boolean claimable;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
