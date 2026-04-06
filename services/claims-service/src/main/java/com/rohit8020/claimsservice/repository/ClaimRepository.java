package com.rohit8020.claimsservice.repository;

import com.rohit8020.claimsservice.entity.ClaimRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<ClaimRecord, String> {
    Page<ClaimRecord> findAllByPolicyholderId(Long policyholderId, Pageable pageable);
    Page<ClaimRecord> findAllByAssignedAgentId(Long assignedAgentId, Pageable pageable);
}
