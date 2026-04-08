package com.rohit8020.claimsservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.rohit8020.platformcommon.event.DomainEvent;
import com.rohit8020.platformcommon.event.PolicyEventPayload;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class ClaimsServiceTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private PolicyProjectionRepository policyProjectionRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private PolicyProjectionClient policyProjectionClient;

    @Mock
    private PolicyProjectionCache policyProjectionCache;

    @Mock
    private Authentication authentication;

    @Mock
    private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper;
    private ClaimsService claimsService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        claimsService = new ClaimsService(
                claimRepository,
                policyProjectionRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                policyProjectionClient,
                policyProjectionCache,
                objectMapper
        );
    }

    @Test
    void submitCreatesClaimOutboxAndIdempotencyRecord() {
        AuthenticatedActor actor = new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER);
        PolicyProjection projection = projection("policy-1", 7L, 99L, true, "ISSUED");
        SubmitClaimRequest request = submitRequest();
        when(securityUtils.currentActor(authentication)).thenReturn(actor);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.empty());
        when(policyProjectionRepository.findById("policy-1")).thenReturn(Optional.of(projection));
        when(claimRepository.save(any(ClaimRecord.class))).thenAnswer(invocation -> {
            ClaimRecord claim = invocation.getArgument(0);
            claim.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            claim.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return claim;
        });

        ClaimResponse response = claimsService.submit(authentication, request, "idem");

        assertThat(response.policyId()).isEqualTo("policy-1");
        assertThat(response.policyholderId()).isEqualTo(7L);
        assertThat(response.assignedAgentId()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo("SUBMITTED");
        verify(policyProjectionCache).put(projection);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("CLAIM_SUBMITTED");
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"status\":\"SUBMITTED\"");

        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getOperation()).isEqualTo("SUBMIT_CLAIM");
        assertThat(recordCaptor.getValue().getResourceId()).isEqualTo(response.id());
    }

    @Test
    void submitFetchesProjectionFromClientWhenRepositoryMisses() {
        AuthenticatedActor actor = new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER);
        SubmitClaimRequest request = submitRequest();
        when(securityUtils.currentActor(authentication)).thenReturn(actor);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.empty());
        when(policyProjectionRepository.findById("policy-1")).thenReturn(Optional.empty());
        when(policyProjectionClient.getProjection("policy-1"))
                .thenReturn(new PolicyProjectionResponse("policy-1", 7L, 99L, "ISSUED", true));
        when(policyProjectionRepository.save(any(PolicyProjection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(claimRepository.save(any(ClaimRecord.class))).thenAnswer(invocation -> {
            ClaimRecord claim = invocation.getArgument(0);
            claim.setCreatedAt(Instant.now());
            claim.setUpdatedAt(Instant.now());
            return claim;
        });

        ClaimResponse response = claimsService.submit(authentication, request, "idem");

        assertThat(response.policyId()).isEqualTo("policy-1");
        verify(policyProjectionClient).getProjection("policy-1");
        verify(policyProjectionCache).put(any(PolicyProjection.class));
    }

    @Test
    void submitReturnsExistingResponseForMatchingIdempotencyKey() throws Exception {
        AuthenticatedActor actor = new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER);
        SubmitClaimRequest request = submitRequest();
        ClaimResponse cached = claimResponse("claim-1", ClaimStatus.SUBMITTED, "notes");
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestHash(hash(objectMapper.writeValueAsBytes(request)));
        record.setResponseBody(objectMapper.writeValueAsString(cached));
        when(securityUtils.currentActor(authentication)).thenReturn(actor);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.of(record));

        ClaimResponse response = claimsService.submit(authentication, request, "idem");

        assertThat(response).isEqualTo(cached);
        verify(claimRepository, never()).save(any(ClaimRecord.class));
    }

    @Test
    void submitRejectsReusedIdempotencyKeyForDifferentPayload() throws Exception {
        AuthenticatedActor actor = new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER);
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestHash(hash("different".getBytes()));
        when(securityUtils.currentActor(authentication)).thenReturn(actor);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> claimsService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void submitRejectsNonPolicyholder() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(99L, "agent", UserRole.AGENT));

        assertThatThrownBy(() -> claimsService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void submitRejectsUnclaimablePolicy() {
        AuthenticatedActor actor = new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER);
        when(securityUtils.currentActor(authentication)).thenReturn(actor);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.empty());
        when(policyProjectionRepository.findById("policy-1"))
                .thenReturn(Optional.of(projection("policy-1", 7L, 99L, false, "LAPSED")));

        assertThatThrownBy(() -> claimsService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void submitRejectsDifferentPolicyholder() {
        AuthenticatedActor actor = new AuthenticatedActor(8L, "holder", UserRole.POLICYHOLDER);
        when(securityUtils.currentActor(authentication)).thenReturn(actor);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 8L, "idem"))
                .thenReturn(Optional.empty());
        when(policyProjectionRepository.findById("policy-1"))
                .thenReturn(Optional.of(projection("policy-1", 7L, 99L, true, "ISSUED")));

        assertThatThrownBy(() -> claimsService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void submitPropagatesHashFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        ClaimsService failingService = new ClaimsService(
                claimRepository,
                policyProjectionRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                policyProjectionClient,
                policyProjectionCache,
                failingMapper
        );
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER));
        when(failingMapper.writeValueAsBytes(any())).thenThrow(jsonProcessingException("hash"));

        assertThatThrownBy(() -> failingService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash request");
    }

    @Test
    void submitPropagatesSerializationFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        ClaimsService failingService = new ClaimsService(
                claimRepository,
                policyProjectionRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                policyProjectionClient,
                policyProjectionCache,
                failingMapper
        );
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER));
        when(failingMapper.writeValueAsBytes(any())).thenReturn("request".getBytes());
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.empty());
        when(policyProjectionRepository.findById("policy-1"))
                .thenReturn(Optional.of(projection("policy-1", 7L, 99L, true, "ISSUED")));
        when(claimRepository.save(any(ClaimRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(failingMapper.writeValueAsString(any())).thenThrow(jsonProcessingException("json"));

        assertThatThrownBy(() -> failingService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize payload");
    }

    @Test
    void submitPropagatesDeserializationFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        ClaimsService failingService = new ClaimsService(
                claimRepository,
                policyProjectionRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                policyProjectionClient,
                policyProjectionCache,
                failingMapper
        );
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestHash(hash("request".getBytes()));
        record.setResponseBody("{}");
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER));
        when(failingMapper.writeValueAsBytes(any())).thenReturn("request".getBytes());
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("SUBMIT_CLAIM", 7L, "idem"))
                .thenReturn(Optional.of(record));
        when(failingMapper.readValue(any(String.class), org.mockito.ArgumentMatchers.<Class<ClaimResponse>>any()))
                .thenThrow(jsonProcessingException("read"));

        assertThatThrownBy(() -> failingService.submit(authentication, submitRequest(), "idem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialize");
    }

    @Test
    void verifyTransitionsSubmittedClaimForAssignedAgent() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(99L, "agent", UserRole.AGENT));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));
        when(claimRepository.save(claim)).thenReturn(claim);

        ClaimResponse response = claimsService.verify(authentication, "claim-1", new ClaimDecisionRequest("checked"));

        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(response.decisionNotes()).isEqualTo("checked");
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void verifyRejectsPolicyholder() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimsService.verify(authentication, "claim-1", null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void verifyReturnsExistingVerifiedClaim() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.VERIFIED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThat(claimsService.verify(authentication, "claim-1", null).status()).isEqualTo("VERIFIED");
        verify(claimRepository, never()).save(claim);
    }

    @Test
    void verifyRejectsWrongStatus() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.APPROVED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimsService.verify(authentication, "claim-1", null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void approveRejectsNonAdmin() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(99L, "agent", UserRole.AGENT));

        assertThatThrownBy(() -> claimsService.approve(authentication, "claim-1", null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approveRejectsClaimThatIsNotVerified() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimsService.approve(authentication, "claim-1", null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void approveReturnsExistingApprovedClaim() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.APPROVED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThat(claimsService.approve(authentication, "claim-1", null).status()).isEqualTo("APPROVED");
        verify(claimRepository, never()).save(claim);
    }

    @Test
    void approveTransitionsVerifiedClaim() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.VERIFIED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));
        when(claimRepository.save(claim)).thenReturn(claim);

        ClaimResponse response = claimsService.approve(authentication, "claim-1", new ClaimDecisionRequest("approved"));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.decisionNotes()).isEqualTo("approved");
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void rejectTransitionsVerifiedClaim() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.VERIFIED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));
        when(claimRepository.save(claim)).thenReturn(claim);

        ClaimResponse response = claimsService.reject(authentication, "claim-1", null);

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.decisionNotes()).isNull();
    }

    @Test
    void getReturnsAccessibleClaim() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThat(claimsService.get(authentication, "claim-1").id()).isEqualTo("claim-1");
    }

    @Test
    void getRejectsInaccessibleClaim() {
        ClaimRecord claim = claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(8L, "holder", UserRole.POLICYHOLDER));
        when(claimRepository.findById("claim-1")).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimsService.get(authentication, "claim-1"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getRejectsMissingClaim() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimsService.get(authentication, "missing"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listUsesAdminRepositoryAndBoundsPageable() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(claimRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            return new PageImpl<>(List.of(claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L)), pageable, 1);
        });

        PagedResponse<ClaimResponse> response = claimsService.list(authentication, -1, 500, "createdAt,asc");

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(100);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(claimRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listUsesAgentRepositoryAndDefaultsInvalidSortDirection() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(99L, "agent", UserRole.AGENT));
        when(claimRepository.findAllByAssignedAgentId(any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L)), pageable, 1);
        });

        claimsService.list(authentication, 0, 20, "createdAt,sideways");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(claimRepository).findAllByAssignedAgentId(org.mockito.Mockito.eq(99L), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listUsesPolicyholderRepository() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(7L, "holder", UserRole.POLICYHOLDER));
        when(claimRepository.findAllByPolicyholderId(any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(claim("claim-1", ClaimStatus.SUBMITTED, 7L, 99L)), pageable, 1);
        });

        claimsService.list(authentication, 0, 20, "createdAt,desc");

        verify(claimRepository).findAllByPolicyholderId(org.mockito.Mockito.eq(7L), any(Pageable.class));
    }

    @Test
    void handlePolicyEventCreatesProjectionAndAcknowledges() {
        PolicyEventPayload payload = new PolicyEventPayload(
                "POL-12345678", 7L, 99L, "ISSUED", 1L, "ADMIN", null, null);
        DomainEvent event = new DomainEvent(
                "event-1",
                com.rohit8020.platformcommon.event.AggregateType.POLICY,
                "policy-1",
                "POLICY_ISSUED",
                1,
                Instant.now(),
                objectMapper.valueToTree(payload)
        );
        when(policyProjectionRepository.findById("policy-1")).thenReturn(Optional.empty());
        when(policyProjectionRepository.save(any(PolicyProjection.class))).thenAnswer(invocation -> invocation.getArgument(0));

        claimsService.handlePolicyEvent(event, acknowledgment);

        ArgumentCaptor<PolicyProjection> projectionCaptor = ArgumentCaptor.forClass(PolicyProjection.class);
        verify(policyProjectionRepository).save(projectionCaptor.capture());
        assertThat(projectionCaptor.getValue().isClaimable()).isTrue();
        verify(policyProjectionCache).put(any(PolicyProjection.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void handlePolicyEventUpdatesExistingProjectionAndMarksLapsedUnclaimable() {
        PolicyProjection existing = projection("policy-1", 7L, 99L, true, "ISSUED");
        PolicyEventPayload payload = new PolicyEventPayload(
                "POL-12345678", 7L, 99L, "LAPSED", 1L, "ADMIN", null, null);
        DomainEvent event = new DomainEvent(
                "event-1",
                com.rohit8020.platformcommon.event.AggregateType.POLICY,
                "policy-1",
                "POLICY_LAPSED",
                1,
                Instant.now(),
                objectMapper.valueToTree(payload)
        );
        when(policyProjectionRepository.findById("policy-1")).thenReturn(Optional.of(existing));
        when(policyProjectionRepository.save(any(PolicyProjection.class))).thenAnswer(invocation -> invocation.getArgument(0));

        claimsService.handlePolicyEvent(event, acknowledgment);

        assertThat(existing.getStatus()).isEqualTo("LAPSED");
        assertThat(existing.isClaimable()).isFalse();
    }

    @Test
    void handlePolicyEventWrapsProcessingFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        ClaimsService failingService = new ClaimsService(
                claimRepository,
                policyProjectionRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                policyProjectionClient,
                policyProjectionCache,
                failingMapper
        );
        DomainEvent event = new DomainEvent(
                "event-1",
                com.rohit8020.platformcommon.event.AggregateType.POLICY,
                "policy-1",
                "POLICY_ISSUED",
                1,
                Instant.now(),
                objectMapper.createObjectNode()
        );
        when(failingMapper.treeToValue(any(), org.mockito.ArgumentMatchers.<Class<PolicyEventPayload>>any()))
                .thenThrow(jsonProcessingException("boom"));

        assertThatThrownBy(() -> failingService.handlePolicyEvent(event, acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to process policy event");
    }

    private SubmitClaimRequest submitRequest() {
        return new SubmitClaimRequest("policy-1", "Broken windshield", new BigDecimal("450.00"));
    }

    private PolicyProjection projection(String id, Long policyholderId, Long assignedAgentId, boolean claimable, String status) {
        PolicyProjection projection = new PolicyProjection();
        projection.setId(id);
        projection.setPolicyholderId(policyholderId);
        projection.setAssignedAgentId(assignedAgentId);
        projection.setClaimable(claimable);
        projection.setStatus(status);
        projection.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return projection;
    }

    private ClaimRecord claim(String id, ClaimStatus status, Long policyholderId, Long assignedAgentId) {
        ClaimRecord claim = new ClaimRecord();
        claim.setId(id);
        claim.setPolicyId("policy-1");
        claim.setPolicyholderId(policyholderId);
        claim.setAssignedAgentId(assignedAgentId);
        claim.setDescription("Broken windshield");
        claim.setClaimAmount(new BigDecimal("450.00"));
        claim.setStatus(status);
        claim.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        claim.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return claim;
    }

    private ClaimResponse claimResponse(String id, ClaimStatus status, String notes) {
        return new ClaimResponse(
                id,
                "policy-1",
                7L,
                99L,
                status.name(),
                new BigDecimal("450.00"),
                "Broken windshield",
                notes,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private String hash(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private JsonProcessingException jsonProcessingException(String message) {
        return new JsonProcessingException(message) { };
    }
}
