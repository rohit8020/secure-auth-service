package com.rohit8020.claimsservice.repository;

import com.rohit8020.claimsservice.entity.PolicyProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyProjectionRepository extends JpaRepository<PolicyProjection, String> {
}
