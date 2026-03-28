package com.rohit8020.claimsservice.repository;

import com.rohit8020.claimsservice.entity.ClaimRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface ClaimRepository extends CrudRepository<ClaimRecord, String> {
    List<ClaimRecord> findAllByPolicyholderId(Long policyholderId);
    List<ClaimRecord> findAllByAssignedAgentId(Long assignedAgentId);
}
