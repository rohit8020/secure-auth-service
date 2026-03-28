package com.rohit8020.claimsservice.service;

import com.rohit8020.claimsservice.dto.ClaimDecisionRequest;
import com.rohit8020.claimsservice.dto.ClaimResponse;
import com.rohit8020.claimsservice.dto.PolicyProjectionResponse;
import com.rohit8020.claimsservice.dto.SubmitClaimRequest;
import com.rohit8020.claimsservice.entity.ClaimRecord;
import com.rohit8020.claimsservice.entity.ClaimStatus;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import com.rohit8020.claimsservice.entity.UserRole;
import com.rohit8020.claimsservice.event.ClaimEvent;
import com.rohit8020.claimsservice.event.PolicyEvent;
import com.rohit8020.claimsservice.exception.ApiException;
import com.rohit8020.claimsservice.repository.ClaimRepository;
import com.rohit8020.claimsservice.repository.PolicyProjectionRepository;
import com.rohit8020.claimsservice.security.AuthenticatedActor;
import com.rohit8020.claimsservice.security.SecurityUtils;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ClaimsService {

    private final ClaimRepository claimRepository;
    private final PolicyProjectionRepository policyProjectionRepository;
    private final SecurityUtils securityUtils;
    private final PolicyProjectionClient policyProjectionClient;
    private final KafkaTemplate<String, ClaimEvent> kafkaTemplate;
    private final String claimTopic;

    public ClaimsService(ClaimRepository claimRepository,
                         PolicyProjectionRepository policyProjectionRepository,
                         SecurityUtils securityUtils,
                         PolicyProjectionClient policyProjectionClient,
                         KafkaTemplate<String, ClaimEvent> kafkaTemplate,
                         @Value("${app.kafka.claim-topic}") String claimTopic) {
        this.claimRepository = claimRepository;
        this.policyProjectionRepository = policyProjectionRepository;
        this.securityUtils = securityUtils;
        this.policyProjectionClient = policyProjectionClient;
        this.kafkaTemplate = kafkaTemplate;
        this.claimTopic = claimTopic;
    }

    public ClaimResponse submit(Authentication authentication, SubmitClaimRequest request) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        if (actor.role() != UserRole.POLICYHOLDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only policyholders can submit claims");
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
        claim.setCreatedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claimRepository.save(claim);

        publish(claim, "CLAIM_SUBMITTED", actor);
        return map(claim);
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
        claim.setUpdatedAt(Instant.now());
        claimRepository.save(claim);

        publish(claim, "CLAIM_VERIFIED", actor);
        return map(claim);
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

    public List<ClaimResponse> list(Authentication authentication) {
        AuthenticatedActor actor = securityUtils.currentActor(authentication);
        return switch (actor.role()) {
            case ADMIN -> streamAll();
            case AGENT -> claimRepository.findAllByAssignedAgentId(actor.userId()).stream().map(this::map).toList();
            case POLICYHOLDER -> claimRepository.findAllByPolicyholderId(actor.userId()).stream().map(this::map).toList();
        };
    }

    @KafkaListener(topics = "${app.kafka.policy-topic}", groupId = "${spring.kafka.consumer.group-id:claims-service}")
    public void handlePolicyEvent(PolicyEvent event) {
        PolicyProjection projection = policyProjectionRepository.findById(event.aggregateId())
                .orElseGet(PolicyProjection::new);
        projection.setId(event.aggregateId());
        projection.setPolicyholderId(event.policyholderId());
        projection.setAssignedAgentId(event.assignedAgentId());
        projection.setStatus(event.status());
        projection.setClaimable(!"LAPSED".equals(event.status()));
        projection.setUpdatedAt(Instant.now());
        policyProjectionRepository.save(projection);
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
        claim.setUpdatedAt(Instant.now());
        claimRepository.save(claim);

        publish(claim, eventType, actor);
        return map(claim);
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
            return existing.get();
        }

        PolicyProjectionResponse response = policyProjectionClient.getProjection(policyId);
        PolicyProjection projection = new PolicyProjection();
        projection.setId(response.policyId());
        projection.setPolicyholderId(response.policyholderId());
        projection.setAssignedAgentId(response.assignedAgentId());
        projection.setStatus(response.status());
        projection.setClaimable(response.claimable());
        projection.setUpdatedAt(Instant.now());
        return policyProjectionRepository.save(projection);
    }

    private void publish(ClaimRecord claim, String eventType, AuthenticatedActor actor) {
        kafkaTemplate.send(claimTopic, claim.getId(), new ClaimEvent(
                claim.getId(),
                claim.getPolicyId(),
                claim.getPolicyholderId(),
                claim.getAssignedAgentId(),
                eventType,
                claim.getStatus().name(),
                actor.userId(),
                actor.role().name(),
                Instant.now(),
                claim.getClaimAmount()
        ));
    }

    private List<ClaimResponse> streamAll() {
        java.util.ArrayList<ClaimResponse> responses = new java.util.ArrayList<>();
        claimRepository.findAll().forEach(claim -> responses.add(map(claim)));
        return responses;
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
}
