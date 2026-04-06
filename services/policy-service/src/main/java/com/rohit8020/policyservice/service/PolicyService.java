package com.rohit8020.policyservice.service;

import com.rohit8020.policyservice.dto.IssuePolicyRequest;
import com.rohit8020.policyservice.dto.PolicyProjectionResponse;
import com.rohit8020.policyservice.dto.PolicyResponse;
import com.rohit8020.policyservice.dto.RenewPolicyRequest;
import com.rohit8020.policyservice.entity.IdempotencyRecord;
import com.rohit8020.policyservice.entity.OutboxEvent;
import com.rohit8020.policyservice.entity.OutboxStatus;
import com.rohit8020.policyservice.entity.Policy;
import com.rohit8020.policyservice.entity.PolicyStatus;
import com.rohit8020.policyservice.entity.UserRole;
import com.rohit8020.policyservice.exception.ApiException;
import com.rohit8020.policyservice.repository.IdempotencyRecordRepository;
import com.rohit8020.policyservice.repository.OutboxEventRepository;
import com.rohit8020.policyservice.repository.PolicyRepository;
import com.rohit8020.policyservice.security.AuthenticatedActor;
import com.rohit8020.policyservice.security.SecurityUtils;
import com.rohit8020.platformcommon.api.PagedResponse;
import com.rohit8020.platformcommon.event.AggregateType;
import com.rohit8020.platformcommon.event.PolicyEventPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SecurityUtils securityUtils;
    private final ObjectMapper objectMapper;

    public PolicyService(PolicyRepository policyRepository,
                         IdempotencyRecordRepository idempotencyRecordRepository,
                         OutboxEventRepository outboxEventRepository,
                         SecurityUtils securityUtils,
                         ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.securityUtils = securityUtils;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PolicyResponse issuePolicy(Authentication authentication,
                                      IssuePolicyRequest request,
                                      String idempotencyKey) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        String requestHash = hashRequest(request);
        IdempotencyRecord existing = idempotencyRecordRepository
                .findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", actor.userId(), idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Idempotency key has already been used with a different request");
            }
            return deserialize(existing.getResponseBody(), PolicyResponse.class);
        }

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

        persistOutboxEvent(saved, "POLICY_ISSUED", actor);
        PolicyResponse response = map(saved);
        persistIdempotencyRecord("ISSUE_POLICY", actor.userId(), idempotencyKey, requestHash, response, response.id());
        return response;
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

        persistOutboxEvent(saved, "POLICY_RENEWED", actor);
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

        persistOutboxEvent(saved, "POLICY_LAPSED", actor);
        return map(saved);
    }

    public PolicyResponse getPolicy(Authentication authentication, String policyId) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        return map(requireAccessiblePolicy(actor, policyId));
    }

    public PagedResponse<PolicyResponse> listPolicies(Authentication authentication,
                                                      int page,
                                                      int size,
                                                      String sort) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), parseSort(sort));
        Page<Policy> results = switch (actor.role()) {
            case ADMIN -> policyRepository.findAll(pageable);
            case AGENT -> policyRepository.findAllByAssignedAgentId(actor.userId(), pageable);
            case POLICYHOLDER -> policyRepository.findAllByPolicyholderId(actor.userId(), pageable);
        };
        return new PagedResponse<>(
                results.stream().map(this::map).toList(),
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages(),
                sort
        );
    }

    public PolicyProjectionResponse getProjection(String policyId) {
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

    private void persistOutboxEvent(Policy policy, String eventType, AuthenticatedActor actor) {
        PolicyEventPayload payload = new PolicyEventPayload(
                policy.getPolicyNumber(),
                policy.getPolicyholderId(),
                policy.getAssignedAgentId(),
                policy.getStatus().name(),
                actor.userId(),
                actor.role().name(),
                policy.getStartDate(),
                policy.getEndDate()
        );

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID().toString());
        event.setAggregateType(AggregateType.POLICY);
        event.setAggregateId(policy.getId().toString());
        event.setEventType(eventType);
        event.setEventVersion(1);
        event.setPayload(writeJson(payload));
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setAvailableAt(Instant.now());
        outboxEventRepository.save(event);
    }

    private void persistIdempotencyRecord(String operation,
                                          Long actorId,
                                          String idempotencyKey,
                                          String requestHash,
                                          PolicyResponse response,
                                          String resourceId) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setOperation(operation);
        record.setActorId(actorId);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setResponseStatus(HttpStatus.OK.value());
        record.setResponseBody(writeJson(response));
        record.setResourceId(resourceId);
        record.setExpiresAt(Instant.now().plusSeconds(24 * 60 * 60));
        idempotencyRecordRepository.save(record);
    }

    private String hashRequest(Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(request)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash request", ex);
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize payload", ex);
        }
    }

    private <T> T deserialize(String payload, Class<T> targetType) {
        try {
            return objectMapper.readValue(payload, targetType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize idempotent response", ex);
        }
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", 2);
        Sort.Direction direction = parts.length > 1
                ? Sort.Direction.fromOptionalString(parts[1]).orElse(Sort.Direction.DESC)
                : Sort.Direction.DESC;
        return Sort.by(direction, parts[0]);
    }
}
