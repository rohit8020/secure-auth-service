package com.rohit8020.policyservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.platformcommon.api.PagedResponse;
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
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private Authentication authentication;

    private ObjectMapper objectMapper;
    private PolicyService policyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        policyService = new PolicyService(
                policyRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                objectMapper
        );
    }

    @Test
    void issuePolicyCreatesPolicyOutboxAndIdempotencyRecordForAdmin() {
        AuthenticatedActor admin = new AuthenticatedActor(1L, "admin", UserRole.ADMIN);
        IssuePolicyRequest request = issueRequest(22L, 44L);
        when(securityUtils.currentActor(authentication)).thenReturn(admin);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 1L, "idem"))
                .thenReturn(Optional.empty());
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            policy.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            policy.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            policy.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return policy;
        });

        PolicyResponse response = policyService.issuePolicy(authentication, request, "idem");

        assertThat(response.id()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(response.assignedAgentId()).isEqualTo(44L);
        assertThat(response.status()).isEqualTo("ISSUED");

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getAggregateId()).isEqualTo(response.id());
        assertThat(event.getEventType()).isEqualTo("POLICY_ISSUED");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPayload()).contains("\"policyholderId\":22");

        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(recordCaptor.capture());
        IdempotencyRecord record = recordCaptor.getValue();
        assertThat(record.getOperation()).isEqualTo("ISSUE_POLICY");
        assertThat(record.getActorId()).isEqualTo(1L);
        assertThat(record.getIdempotencyKey()).isEqualTo("idem");
        assertThat(record.getResponseStatus()).isEqualTo(200);
        assertThat(record.getResponseBody()).contains("POL-");
    }

    @Test
    void issuePolicyUsesAuthenticatedAgentAsAssignedAgent() {
        AuthenticatedActor agent = new AuthenticatedActor(44L, "agent", UserRole.AGENT);
        IssuePolicyRequest request = issueRequest(22L, 44L);
        when(securityUtils.currentActor(authentication)).thenReturn(agent);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 44L, "idem"))
                .thenReturn(Optional.empty());
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            policy.setId(UUID.randomUUID());
            policy.setCreatedAt(Instant.now());
            policy.setUpdatedAt(Instant.now());
            return policy;
        });

        PolicyResponse response = policyService.issuePolicy(authentication, request, "idem");

        assertThat(response.assignedAgentId()).isEqualTo(44L);
    }

    @Test
    void issuePolicyReturnsExistingResponseForMatchingIdempotencyKey() throws Exception {
        AuthenticatedActor admin = new AuthenticatedActor(1L, "admin", UserRole.ADMIN);
        IssuePolicyRequest request = issueRequest(22L, 44L);
        PolicyResponse cached = new PolicyResponse(
                "11111111-1111-1111-1111-111111111111",
                "POL-11111111",
                22L,
                44L,
                "ISSUED",
                new BigDecimal("199.99"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestHash(hash(objectMapper.writeValueAsBytes(request)));
        record.setResponseBody(objectMapper.writeValueAsString(cached));
        when(securityUtils.currentActor(authentication)).thenReturn(admin);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 1L, "idem"))
                .thenReturn(Optional.of(record));

        PolicyResponse response = policyService.issuePolicy(authentication, request, "idem");

        assertThat(response).isEqualTo(cached);
        verify(policyRepository, never()).save(any(Policy.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void issuePolicyRejectsMismatchedIdempotentRequest() throws Exception {
        AuthenticatedActor admin = new AuthenticatedActor(1L, "admin", UserRole.ADMIN);
        IssuePolicyRequest request = issueRequest(22L, 44L);
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestHash(hash("different".getBytes()));
        when(securityUtils.currentActor(authentication)).thenReturn(admin);
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 1L, "idem"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> policyService.issuePolicy(authentication, request, "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void issuePolicyRejectsPolicyholder() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(22L, "holder", UserRole.POLICYHOLDER));
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 22L, "idem"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.issuePolicy(authentication, issueRequest(22L, 44L), "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void issuePolicyRejectsAgentIssuingForAnotherAgent() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(55L, "agent", UserRole.AGENT));
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 55L, "idem"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.issuePolicy(authentication, issueRequest(22L, 44L), "idem"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void issuePolicyPropagatesHashingFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        PolicyService failingService = new PolicyService(
                policyRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                failingMapper
        );
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(failingMapper.writeValueAsBytes(any())).thenThrow(jsonProcessingException("hash"));

        assertThatThrownBy(() -> failingService.issuePolicy(authentication, issueRequest(22L, 44L), "idem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash request");
    }

    @Test
    void issuePolicyPropagatesSerializationFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        PolicyService failingService = new PolicyService(
                policyRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                failingMapper
        );
        IssuePolicyRequest request = issueRequest(22L, 44L);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(failingMapper.writeValueAsBytes(any())).thenReturn("request".getBytes());
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 1L, "idem"))
                .thenReturn(Optional.empty());
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            policy.setId(UUID.randomUUID());
            policy.setCreatedAt(Instant.now());
            policy.setUpdatedAt(Instant.now());
            return policy;
        });
        when(failingMapper.writeValueAsString(any())).thenThrow(jsonProcessingException("json"));

        assertThatThrownBy(() -> failingService.issuePolicy(authentication, request, "idem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize payload");
    }

    @Test
    void issuePolicyPropagatesDeserializationFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        PolicyService failingService = new PolicyService(
                policyRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                securityUtils,
                failingMapper
        );
        IssuePolicyRequest request = issueRequest(22L, 44L);
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestHash(hash("request".getBytes()));
        record.setResponseBody("{}");
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(failingMapper.writeValueAsBytes(any())).thenReturn("request".getBytes());
        when(idempotencyRecordRepository.findByOperationAndActorIdAndIdempotencyKey("ISSUE_POLICY", 1L, "idem"))
                .thenReturn(Optional.of(record));
        when(failingMapper.readValue(anyString(), org.mockito.ArgumentMatchers.<Class<PolicyResponse>>any()))
                .thenThrow(jsonProcessingException("read"));

        assertThatThrownBy(() -> failingService.issuePolicy(authentication, request, "idem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialize");
    }

    @Test
    void renewPolicyUpdatesManagedPolicy() {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.ISSUED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));
        when(policyRepository.save(policy)).thenReturn(policy);

        PolicyResponse response = policyService.renewPolicy(authentication, id.toString(),
                new RenewPolicyRequest(LocalDate.of(2026, 3, 1), new BigDecimal("299.99")));

        assertThat(response.status()).isEqualTo("RENEWED");
        assertThat(response.premium()).isEqualTo(new BigDecimal("299.99"));
        assertThat(policy.getEndDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void renewPolicyRejectsLapsedPolicy() {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.LAPSED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> policyService.renewPolicy(authentication, id.toString(),
                new RenewPolicyRequest(LocalDate.of(2026, 3, 1), new BigDecimal("299.99"))))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void renewPolicyRejectsAgentManagingUnassignedPolicy() {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.ISSUED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(55L, "agent", UserRole.AGENT));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> policyService.renewPolicy(authentication, id.toString(),
                new RenewPolicyRequest(LocalDate.of(2026, 3, 1), new BigDecimal("299.99"))))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void renewPolicyRejectsPolicyholderManagingPolicy() {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.ISSUED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(22L, "holder", UserRole.POLICYHOLDER));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> policyService.renewPolicy(authentication, id.toString(),
                new RenewPolicyRequest(LocalDate.of(2026, 3, 1), new BigDecimal("299.99"))))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lapsePolicyReturnsCurrentPolicyWhenAlreadyLapsed() {
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.LAPSED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        PolicyResponse response = policyService.lapsePolicy(authentication, id.toString());

        assertThat(response.status()).isEqualTo("LAPSED");
        verify(policyRepository, never()).save(policy);
    }

    @Test
    void lapsePolicyTransitionsPolicyAndPublishesEvent() {
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.ISSUED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));
        when(policyRepository.save(policy)).thenReturn(policy);

        PolicyResponse response = policyService.lapsePolicy(authentication, id.toString());

        assertThat(response.status()).isEqualTo("LAPSED");
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void getPolicyRejectsUnauthorizedAccess() {
        UUID id = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.ISSUED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(99L, "agent", UserRole.AGENT));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> policyService.getPolicy(authentication, id.toString()))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getPolicyReturnsAccessiblePolicy() {
        UUID id = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.ISSUED);
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(44L, "agent", UserRole.AGENT));
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        assertThat(policyService.getPolicy(authentication, id.toString()).id()).isEqualTo(id.toString());
    }

    @Test
    void listPoliciesUsesActorScopeAndBoundsPageable() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(1L, "admin", UserRole.ADMIN));
        when(policyRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            return new PageImpl<>(List.of(policy(UUID.randomUUID(), 22L, 44L, PolicyStatus.ISSUED)), pageable, 1);
        });

        PagedResponse<PolicyResponse> response = policyService.listPolicies(authentication, -1, 500, "createdAt,asc");

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.totalElements()).isEqualTo(1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(policyRepository).findAll(pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listPoliciesUsesAgentRepositoryAndDefaultsInvalidSortDirection() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(44L, "agent", UserRole.AGENT));
        when(policyRepository.findAllByAssignedAgentId(any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(policy(UUID.randomUUID(), 22L, 44L, PolicyStatus.ISSUED)), pageable, 1);
        });

        policyService.listPolicies(authentication, 1, 10, "createdAt,sideways");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(policyRepository).findAllByAssignedAgentId(org.mockito.Mockito.eq(44L), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listPoliciesUsesPolicyholderRepository() {
        when(securityUtils.currentActor(authentication))
                .thenReturn(new AuthenticatedActor(22L, "holder", UserRole.POLICYHOLDER));
        when(policyRepository.findAllByPolicyholderId(any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(policy(UUID.randomUUID(), 22L, 44L, PolicyStatus.ISSUED)), pageable, 1);
        });

        policyService.listPolicies(authentication, 0, 20, "createdAt,desc");

        verify(policyRepository).findAllByPolicyholderId(org.mockito.Mockito.eq(22L), any(Pageable.class));
    }

    @Test
    void getProjectionMapsExistingPolicy() {
        UUID id = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Policy policy = policy(id, 22L, 44L, PolicyStatus.RENEWED);
        when(policyRepository.findById(id)).thenReturn(Optional.of(policy));

        PolicyProjectionResponse response = policyService.getProjection(id.toString());

        assertThat(response).isEqualTo(new PolicyProjectionResponse(id.toString(), 22L, 44L, "RENEWED", true));
    }

    @Test
    void getProjectionRejectsMissingPolicy() {
        UUID id = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(policyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.getProjection(id.toString()))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private IssuePolicyRequest issueRequest(Long policyholderId, Long assignedAgentId) {
        return new IssuePolicyRequest(
                policyholderId,
                assignedAgentId,
                new BigDecimal("199.99"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1)
        );
    }

    private Policy policy(UUID id, Long policyholderId, Long assignedAgentId, PolicyStatus status) {
        Policy policy = new Policy();
        policy.setId(id);
        policy.setPolicyNumber("POL-" + id.toString().substring(0, 8).toUpperCase());
        policy.setPolicyholderId(policyholderId);
        policy.setAssignedAgentId(assignedAgentId);
        policy.setStatus(status);
        policy.setPremium(new BigDecimal("199.99"));
        policy.setStartDate(LocalDate.of(2026, 1, 1));
        policy.setEndDate(LocalDate.of(2026, 2, 1));
        policy.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        policy.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return policy;
    }

    private String hash(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private JsonProcessingException jsonProcessingException(String message) {
        return new JsonProcessingException(message) { };
    }
}
