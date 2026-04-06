package com.rohit8020.claimsservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.claimsservice.dto.ClaimDecisionRequest;
import com.rohit8020.claimsservice.dto.ClaimResponse;
import com.rohit8020.claimsservice.dto.PolicyProjectionResponse;
import com.rohit8020.claimsservice.dto.SubmitClaimRequest;
import com.rohit8020.claimsservice.entity.ClaimRecord;
import com.rohit8020.claimsservice.entity.ClaimStatus;
import com.rohit8020.claimsservice.entity.IdempotencyRecord;
import com.rohit8020.claimsservice.entity.OutboxEvent;
import com.rohit8020.claimsservice.entity.OutboxStatus;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import com.rohit8020.claimsservice.entity.UserRole;
import com.rohit8020.claimsservice.exception.ApiException;
import com.rohit8020.claimsservice.repository.ClaimRepository;
import com.rohit8020.claimsservice.repository.IdempotencyRecordRepository;
import com.rohit8020.claimsservice.repository.OutboxEventRepository;
import com.rohit8020.claimsservice.repository.PolicyProjectionRepository;
import com.rohit8020.claimsservice.security.AuthenticatedActor;
import com.rohit8020.claimsservice.security.SecurityUtils;
import com.rohit8020.platformcommon.api.PagedResponse;
import com.rohit8020.platformcommon.event.AggregateType;
import com.rohit8020.platformcommon.event.ClaimEventPayload;
import com.rohit8020.platformcommon.event.DomainEvent;
import com.rohit8020.platformcommon.event.PolicyEventPayload;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ClaimsService {

    private final ClaimRepository claimRepository;
    private final PolicyProjectionRepository policyProjectionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SecurityUtils securityUtils;
    private final PolicyProjectionClient policyProjectionClient;
    private final PolicyProjectionCache policyProjectionCache;
    private final ObjectMapper objectMapper;

    public ClaimsService(ClaimRepository claimRepository,
                         PolicyProjectionRepository policyProjectionRepository,
                         IdempotencyRecordRepository idempotencyRecordRepository,
                         OutboxEventRepository outboxEventRepository,
                         SecurityUtils securityUtils,
                         PolicyProjectionClient policyProjectionClient,
                         PolicyProjectionCache policyProjectionCache,
                         ObjectMapper objectMapper) {
        this.claimRepository = claimRepository;
        this.policyProjectionRepository = policyProjectionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.securityUtils = securityUtils;
        this.policyProjectionClient = policyProjectionClient;
        this.policyProjectionCache = policyProjectionCache;
        this.objectMapper = objectMapper;
    }

    public ClaimResponse submit(Authentication authentication,
                                SubmitClaimRequest request,
                                String idempotencyKey) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        if (actor.role() != UserRole.POLICYHOLDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only policyholders can submit claims");
        }

        String requestHash = hashRequest(request);
        IdempotencyRecord existing = idempotencyRecordRepository
                .findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", actor.userId(), idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Idempotency key has already been used with a different request");
            }
            return deserialize(existing.getResponseBody(), ClaimResponse.class);
        }

        PolicyProjection projection = ensureProjection(request.policyId());
        if (!projection.isClaimable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Claims are not allowed for this policy");
        }
        if (!actor.userId().equals(projection.getPolicyholderId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can submit claims only for your own policies");
        }

        ClaimRecord claim = new ClaimRecord();
        claim.setId(UUID.randomUUID().toString());
        claim.setPolicyId(request.policyId());
        claim.setPolicyholderId(projection.getPolicyholderId());
        claim.setAssignedAgentId(projection.getAssignedAgentId());
        claim.setDescription(request.description());
        claim.setClaimAmount(request.claimAmount());
        claim.setStatus(ClaimStatus.SUBMITTED);
        ClaimRecord saved = claimRepository.save(claim);

        persistOutboxEvent(saved, "CLAIM_SUBMITTED", actor);
        ClaimResponse response = map(saved);
        persistIdempotencyRecord("SUBMIT_CLAIM", actor.userId(), idempotencyKey, requestHash, response, response.id());
        return response;
    }

    public ClaimResponse verify(Authentication authentication, String claimId, ClaimDecisionRequest request) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        ClaimRecord claim = findAccessibleClaim(actor, claimId);

        if (actor.role() == UserRole.POLICYHOLDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Policyholders cannot verify claims");
        }
        if (actor.role() == UserRole.AGENT && !actor.userId().equals(claim.getAssignedAgentId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Agents can verify only assigned claims");
        }
        if (claim.getStatus() == ClaimStatus.VERIFIED) {
            return map(claim);
        }
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only submitted claims can be verified");
        }

        claim.setStatus(ClaimStatus.VERIFIED);
        claim.setDecisionNotes(request == null ? null : request.notes());
        ClaimRecord saved = claimRepository.save(claim);

        persistOutboxEvent(saved, "CLAIM_VERIFIED", actor);
        return map(saved);
    }

    public ClaimResponse approve(Authentication authentication, String claimId, ClaimDecisionRequest request) {
        return decide(authentication, claimId, ClaimStatus.APPROVED, "CLAIM_APPROVED", request);
    }

    public ClaimResponse reject(Authentication authentication, String claimId, ClaimDecisionRequest request) {
        return decide(authentication, claimId, ClaimStatus.REJECTED, "CLAIM_REJECTED", request);
    }

    public ClaimResponse get(Authentication authentication, String claimId) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        return map(findAccessibleClaim(actor, claimId));
    }

    public PagedResponse<ClaimResponse> list(Authentication authentication, int page, int size, String sort) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), parseSort(sort));
        Page<ClaimRecord> results = switch (actor.role()) {
            case ADMIN -> claimRepository.findAll(pageable);
            case AGENT -> claimRepository.findAllByAssignedAgentId(actor.userId(), pageable);
            case POLICYHOLDER -> claimRepository.findAllByPolicyholderId(actor.userId(), pageable);
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

    @KafkaListener(topics = "${app.kafka.policy-topic}",
            groupId = "${spring.kafka.consumer.group-id:claims-service}",
            containerFactory = "domainEventKafkaListenerContainerFactory")
    public void handlePolicyEvent(DomainEvent event, Acknowledgment acknowledgment) {
        try {
            PolicyEventPayload payload = objectMapper.treeToValue(event.payload(), PolicyEventPayload.class);
            PolicyProjection projection = policyProjectionRepository.findById(event.aggregateId())
                    .orElseGet(PolicyProjection::new);
            projection.setId(event.aggregateId());
            projection.setPolicyholderId(payload.policyholderId());
            projection.setAssignedAgentId(payload.assignedAgentId());
            projection.setStatus(payload.status());
            projection.setClaimable(!"LAPSED".equals(payload.status()));
            PolicyProjection saved = policyProjectionRepository.save(projection);
            policyProjectionCache.put(saved);
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process policy event " + event.eventId(), ex);
        }
    }

    private ClaimResponse decide(Authentication authentication,
                                 String claimId,
                                 ClaimStatus targetStatus,
                                 String eventType,
                                 ClaimDecisionRequest request) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        if (actor.role() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only admins can finalize claims");
        }

        ClaimRecord claim = findAccessibleClaim(actor, claimId);
        if (claim.getStatus() == targetStatus) {
            return map(claim);
        }
        if (claim.getStatus() != ClaimStatus.VERIFIED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only verified claims can be finalized");
        }

        claim.setStatus(targetStatus);
        claim.setDecisionNotes(request == null ? null : request.notes());
        ClaimRecord saved = claimRepository.save(claim);

        persistOutboxEvent(saved, eventType, actor);
        return map(saved);
    }

    private ClaimRecord findAccessibleClaim(AuthenticatedActor actor, String claimId) {
        ClaimRecord claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claim not found"));
        if (actor.role() == UserRole.ADMIN) {
            return claim;
        }
        if (actor.role() == UserRole.AGENT && actor.userId().equals(claim.getAssignedAgentId())) {
            return claim;
        }
        if (actor.role() == UserRole.POLICYHOLDER && actor.userId().equals(claim.getPolicyholderId())) {
            return claim;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You are not allowed to access this claim");
    }

    private PolicyProjection ensureProjection(String policyId) {
        Optional<PolicyProjection> existing = policyProjectionRepository.findById(policyId);
        if (existing.isPresent()) {
            policyProjectionCache.put(existing.get());
            return existing.get();
        }

        PolicyProjectionResponse response = policyProjectionClient.getProjection(policyId);
        PolicyProjection projection = new PolicyProjection();
        projection.setId(response.policyId());
        projection.setPolicyholderId(response.policyholderId());
        projection.setAssignedAgentId(response.assignedAgentId());
        projection.setStatus(response.status());
        projection.setClaimable(response.claimable());
        PolicyProjection saved = policyProjectionRepository.save(projection);
        policyProjectionCache.put(saved);
        return saved;
    }

    private void persistOutboxEvent(ClaimRecord claim, String eventType, AuthenticatedActor actor) {
        ClaimEventPayload payload = new ClaimEventPayload(
                claim.getPolicyId(),
                claim.getPolicyholderId(),
                claim.getAssignedAgentId(),
                claim.getStatus().name(),
                actor.userId(),
                actor.role().name(),
                claim.getClaimAmount()
        );

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID().toString());
        event.setAggregateType(AggregateType.CLAIM);
        event.setAggregateId(claim.getId());
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
                                          ClaimResponse response,
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

    private ClaimResponse map(ClaimRecord claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getPolicyId(),
                claim.getPolicyholderId(),
                claim.getAssignedAgentId(),
                claim.getStatus().name(),
                claim.getClaimAmount(),
                claim.getDescription(),
                claim.getDecisionNotes(),
                claim.getCreatedAt(),
                claim.getUpdatedAt()
        );
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
