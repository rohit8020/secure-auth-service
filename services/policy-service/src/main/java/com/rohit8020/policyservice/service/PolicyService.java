package com.rohit8020.policyservice.service;

import com.rohit8020.policyservice.dto.IssuePolicyRequest;
import com.rohit8020.policyservice.dto.PolicyProjectionResponse;
import com.rohit8020.policyservice.dto.PolicyResponse;
import com.rohit8020.policyservice.dto.RenewPolicyRequest;
import com.rohit8020.policyservice.entity.Policy;
import com.rohit8020.policyservice.entity.PolicyStatus;
import com.rohit8020.policyservice.entity.UserRole;
import com.rohit8020.policyservice.event.PolicyEvent;
import com.rohit8020.policyservice.exception.ApiException;
import com.rohit8020.policyservice.repository.PolicyRepository;
import com.rohit8020.policyservice.security.AuthenticatedActor;
import com.rohit8020.policyservice.security.SecurityUtils;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final SecurityUtils securityUtils;
    private final KafkaTemplate<String, PolicyEvent> kafkaTemplate;
    private final String topic;

    public PolicyService(PolicyRepository policyRepository,
                         SecurityUtils securityUtils,
                         KafkaTemplate<String, PolicyEvent> kafkaTemplate,
                         @Value("${app.kafka.policy-topic}") String topic) {
        this.policyRepository = policyRepository;
        this.securityUtils = securityUtils;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Transactional
    public PolicyResponse issuePolicy(Authentication authentication, IssuePolicyRequest request) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        if (actor.role() == UserRole.POLICYHOLDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Policyholders cannot issue policies");
        }
        if (actor.role() == UserRole.AGENT && !actor.userId().equals(request.assignedAgentId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Agents can issue policies only for themselves");
        }

        Policy policy = new Policy();
        policy.setPolicyNumber("POL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        policy.setPolicyholderId(request.policyholderId());
        policy.setAssignedAgentId(actor.role() == UserRole.AGENT ? actor.userId() : request.assignedAgentId());
        policy.setStatus(PolicyStatus.ISSUED);
        policy.setPremium(request.premium());
        policy.setStartDate(request.startDate());
        policy.setEndDate(request.endDate());
        Policy saved = policyRepository.save(policy);

        publish(saved, "POLICY_ISSUED", actor);
        return map(saved);
    }

    @Transactional
    public PolicyResponse renewPolicy(Authentication authentication, String policyId, RenewPolicyRequest request) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        Policy policy = requireAccessibleManagedPolicy(actor, policyId);
        if (policy.getStatus() == PolicyStatus.LAPSED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lapsed policies cannot be renewed");
        }

        policy.setStatus(PolicyStatus.RENEWED);
        policy.setPremium(request.premium());
        policy.setEndDate(request.newEndDate());
        Policy saved = policyRepository.save(policy);

        publish(saved, "POLICY_RENEWED", actor);
        return map(saved);
    }

    @Transactional
    public PolicyResponse lapsePolicy(Authentication authentication, String policyId) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        Policy policy = requireAccessibleManagedPolicy(actor, policyId);
        if (policy.getStatus() == PolicyStatus.LAPSED) {
            return map(policy);
        }

        policy.setStatus(PolicyStatus.LAPSED);
        Policy saved = policyRepository.save(policy);

        publish(saved, "POLICY_LAPSED", actor);
        return map(saved);
    }

    public PolicyResponse getPolicy(Authentication authentication, String policyId) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        return map(requireAccessiblePolicy(actor, policyId));
    }

    public List<PolicyResponse> listPolicies(Authentication authentication) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        return switch (actor.role()) {
            case ADMIN -> policyRepository.findAll().stream().map(this::map).toList();
            case AGENT -> policyRepository.findAllByAssignedAgentIdOrderByCreatedAtDesc(actor.userId())
                    .stream().map(this::map).toList();
            case POLICYHOLDER -> policyRepository.findAllByPolicyholderIdOrderByCreatedAtDesc(actor.userId())
                    .stream().map(this::map).toList();
        };
    }

    public PolicyProjectionResponse getProjection(String policyId, String providedKey, String expectedKey) {
        if (!expectedKey.equals(providedKey)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Invalid internal API key");
        }
        Policy policy = findById(policyId);
        return new PolicyProjectionResponse(
                policy.getId().toString(),
                policy.getPolicyholderId(),
                policy.getAssignedAgentId(),
                policy.getStatus().name(),
                policy.getStatus() != PolicyStatus.LAPSED
        );
    }

    private Policy requireAccessibleManagedPolicy(AuthenticatedActor actor, String policyId) {
        Policy policy = requireAccessiblePolicy(actor, policyId);
        if (actor.role() == UserRole.AGENT && !actor.userId().equals(policy.getAssignedAgentId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Agents can manage only assigned policies");
        }
        if (actor.role() == UserRole.POLICYHOLDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Policyholders cannot manage policies");
        }
        return policy;
    }

    private Policy requireAccessiblePolicy(AuthenticatedActor actor, String policyId) {
        Policy policy = findById(policyId);
        if (actor.role() == UserRole.ADMIN) {
            return policy;
        }
        if (actor.role() == UserRole.AGENT && actor.userId().equals(policy.getAssignedAgentId())) {
            return policy;
        }
        if (actor.role() == UserRole.POLICYHOLDER && actor.userId().equals(policy.getPolicyholderId())) {
            return policy;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You are not allowed to access this policy");
    }

    private Policy findById(String policyId) {
        return policyRepository.findById(UUID.fromString(policyId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found"));
    }

    private void publish(Policy policy, String eventType, AuthenticatedActor actor) {
        kafkaTemplate.send(topic, policy.getId().toString(), new PolicyEvent(
                policy.getId().toString(),
                policy.getPolicyNumber(),
                policy.getPolicyholderId(),
                policy.getAssignedAgentId(),
                eventType,
                policy.getStatus().name(),
                actor.userId(),
                actor.role().name(),
                Instant.now(),
                policy.getStartDate(),
                policy.getEndDate()
        ));
    }

    private PolicyResponse map(Policy policy) {
        return new PolicyResponse(
                policy.getId().toString(),
                policy.getPolicyNumber(),
                policy.getPolicyholderId(),
                policy.getAssignedAgentId(),
                policy.getStatus().name(),
                policy.getPremium(),
                policy.getStartDate(),
                policy.getEndDate(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
