package com.rohit8020.policyservice.repository;

import com.rohit8020.policyservice.entity.Policy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    List<Policy> findAllByPolicyholderIdOrderByCreatedAtDesc(Long policyholderId);
    List<Policy> findAllByAssignedAgentIdOrderByCreatedAtDesc(Long assignedAgentId);
    Optional<Policy> findByPolicyNumber(String policyNumber);
}
