package com.rohit8020.policyservice.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.rohit8020.policyservice.entity.Policy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    List<Policy> findAllByPolicyholderIdOrderByCreatedAtDesc(Long policyholderId);
    List<Policy> findAllByAssignedAgentIdOrderByCreatedAtDesc(Long assignedAgentId);
    Page<Policy> findAllByPolicyholderId(Long policyholderId, Pageable pageable);
    Page<Policy> findAllByAssignedAgentId(Long assignedAgentId, Pageable pageable);
    Optional<Policy> findByPolicyNumber(String policyNumber);
}
