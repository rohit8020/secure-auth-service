# Claims Service - Detailed Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Functionality](#core-functionality)
4. [Data Model](#data-model)
5. [Event Processing](#event-processing)
6. [Components](#components)
7. [Resilience Patterns](#resilience-patterns)
8. [API Endpoints](#api-endpoints)

---

## Overview

The **Claims Service** manages the entire insurance claim lifecycle from submission to approval/rejection. It tracks claim records, validates claims against policies, and executes claim workflows.

**Port**: `8083`  
**Database**: PostgreSQL + Redis (cache)  
**Technology Stack**: Spring Boot, Spring Cloud Circuit Breaker (Resilience4j), Kafka, REST Client, Redis

---

## Architecture

### Service Dependencies

```
Claims Service
    ├─ Reads policies from Policy Service (REST + Circuit Breaker)
    ├─ Caches policy data in Redis (PolicyProjection)
    ├─ Listens to POLICY_ISSUED events from Kafka
    ├─ Publishes CLAIM_* events via outbox to Kafka
    └─ Database: PostgreSQL for claims, idempotency, outbox
```

### Request Flow for Claim Submission

```
Client: Submit Claim
    ↓
API Gateway: Validate JWT, forward with X-User-Id header
    ↓
ClaimController.submit()
    ├─ AuthenticatedActor from SecurityUtils
    ├─ Check idempotency key
    ├─ Verify policyholder owns policy
    ├─ Get policy from cache/service
    ├─ Validate policy is claimable
    ├─ Create ClaimRecord
    ├─ Persist OutboxEvent
    ├─ Save idempotency record
    └─ Return ClaimResponse
    ↓
ClaimsOutboxPublisher (background)
    ├─ Scan PENDING events
    ├─ Publish to Kafka (claim-events.v1)
    └─ Mark PUBLISHED
    ↓
Policy Service (subscribes to claim events)
    ├─ Updates policy projection
    └─ Logs claim metadata
```

---

## Core Functionality

### 1. **Submit Claim**

**Endpoint**: `POST /api/claims`  
**Required Role**: POLICYHOLDER  
**Idempotent**: Yes

**Flow**:
```
1. Extract authenticated actor
2. Verify actor is POLICYHOLDER
3. Hash request for idempotency
4. Check cache: If duplicate request, return cached response
5. Fetch policy via:
   ├─ Try Redis cache first (fast)
   ├─ If miss, REST call to Policy Service (with circuit breaker)
   ├─ Cache result in Redis (15 mins)
   └─ If not found: 404
6. Validate policy:
   ├─ Policyholder matches actor
   ├─ Policy is in ISSUED or RENEWED status
   ├─ Claim amount <= policy premium
   └─ No active claims (business rule)
7. Create ClaimRecord:
   ├─ Status: SUBMITTED
   ├─ Assigned to same agent as policy
   ├─ Store claim amount
   ├─ Store description
   └─ Save to database
8. Publish CLAIM_SUBMITTED event
9. Cache response for idempotency
10. Return ClaimResponse
```

**Why Multiple Data Sources**:
```
Redis cache:
  ├─ Ultra-fast (microseconds)
  ├─ Reduces Policy Service load
  ├─ Stale data acceptable (15 min TTL)
  └─ Handles Policy Service unavailability

Policy Service:
  ├─ Source of truth
  ├─ Fetched on cache miss
  ├─ Circuit breaker protects
  ├─ Fallback to old cache if unavailable
  └─ Automatic retry with backoff

Benefits:
  ✓ Fast path: 95% serve from cache
  ✓ Resilient: Works if Policy Service down
  ✓ Accurate: Periodic refresh from source
```

### 2. **Verify Claim**

**Endpoint**: `PATCH /api/claims/{claimId}/verify`  
**Required Role**: ADMIN or AGENT (if assigned to claim)

**Flow**:
```
1. Load claim from database
2. Check access:
   ├─ ADMIN: Can verify any claim
   ├─ AGENT: Can only verify assigned claims
   └─ POLICYHOLDER: Cannot verify
3. Check claim status:
   ├─ If VERIFIED or APPROVED/REJECTED: Return current
   ├─ If SUBMITTED: Allowed
   └─ Else: BAD_REQUEST
4. Update status to VERIFIED
5. Add optional verification notes
6. Publish CLAIM_VERIFIED event
7. Return ClaimResponse
```

**Why Status VERIFIED**:
```
Claim Lifecycle:
  SUBMITTED → VERIFIED → APPROVED/REJECTED
  
SUBMITTED: Initial state
  └─ Agent reviewed basic information

VERIFIED: Agent/admin confirmed details
  ├─ Checked policy validity
  ├─ Checked claim documents
  ├─ Confirmed eligibility
  └─ Ready for decision

APPROVED/REJECTED: Final decision
  └─ Payment processing begins
```

### 3. **Approve/Reject Claims**

**Endpoints**:
- `PATCH /api/claims/{claimId}/approve`
- `PATCH /api/claims/{claimId}/reject`

**Flow**:
```
1. Load claim
2. Check access (ADMIN/AGENT same as verify)
3. Check status (allow VERIFIED only)
4. Update status to APPROVED/REJECTED
5. Add optional decision notes
6. Publish event (CLAIM_APPROVED/CLAIM_REJECTED)
7. Return ClaimResponse
```

### 4. **List Claims**

**Endpoint**: `GET /api/claims?page=0&size=10&status=SUBMITTED`

**Role-Based Filtering**:
```java
Page<ClaimRecord> claims = switch (actor.role()) {
    case ADMIN -> claimRepository.findAll(spec, pageable);
    case AGENT -> claimRepository.findByAssignedAgentId(actor.userId(), pageable);
    case POLICYHOLDER -> {
        List<String> ownPolicies = policyProjectionRepository
            .findByPolicyholderId(actor.userId())
            .stream().map(PolicyProjection::getPolicyId).toList();
        yield claimRepository.findByPolicyIdIn(ownPolicies, pageable);
    }
};
```

**Optional Filters**:
- `status`: SUBMITTED, VERIFIED, APPROVED, REJECTED
- `policyId`: Filter by specific policy
- `createdAfter`: Filter by submission date

---

## Data Model

### 1. **ClaimRecord Entity**

```java
@Entity
@Table(name = "claim_records")
public class ClaimRecord {
    @Id
    private String id;  // UUID
    
    @Column(nullable = false)
    private String policyId;  // Link to policy
    
    @Column(nullable = false)
    private String policyholderId;  // Who submitted
    
    @Column(nullable = false)
    private String assignedAgentId;  // Policy's agent
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;  // What happened
    
    @Column(nullable = false)
    private BigDecimal claimAmount;  // How much claimed
    
    @Enumerated(EnumType.STRING)
    private ClaimStatus status;  // SUBMITTED, VERIFIED, APPROVED, REJECTED
    
    @Column(columnDefinition = "TEXT")
    private String decisionNotes;  // Why approved/rejected
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
}
```

### 2. **ClaimStatus Enum**

```java
public enum ClaimStatus {
    SUBMITTED,   // Initial submission by policyholder
    VERIFIED,    // Agent/admin verified documents
    APPROVED,    // Claim approved, payment processing
    REJECTED     // Claim rejected, no payment
}
```

### 3. **PolicyProjection Entity (Cache)**

```java
@Entity
@Table(name = "policy_projections")
public class PolicyProjection {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(unique = true, nullable = false)
    private String policyId;  // Policy Service's ID
    
    @Column(nullable = false)
    private String policyNumber;
    
    @Column(nullable = false)
    private String policyholderId;
    
    @Column(nullable = false)
    private String assignedAgentId;
    
    @Enumerated(EnumType.STRING)
    private PolicyStatus status;  // ISSUED, RENEWED, LAPSED
    
    @Column(nullable = false)
    private BigDecimal premium;
    
    @Column(nullable = false)
    private boolean claimable;  // false if LAPSED
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
}
```

**Why PolicyProjection**:
```
Without projection:
  ├─ Submit claim
  ├─ Query Policy Service
  ├─ Validate policy exists
  └─ 200ms latency

With projection:
  ├─ Policy Service publishes POLICY_ISSUED
  ├─ Claims Service receives via Kafka
  ├─ Saves to PolicyProjection table
  ├─ Submit claim: Query local table
  └─ < 5ms latency

Benefits:
  ✓ Fast queries (no network call)
  ✓ Works if Policy Service offline
  ✓ Eventually consistent (acceptable for projections)
  ✓ Enables claim submission even with network issues
```

### 4. **IdempotencyRecord & OutboxEvent**

Same pattern as Policy Service (see [POLICY_SERVICE.md](POLICY_SERVICE.md#data-model))

---

## Event Processing

### Kafka Consumer: Policy Events

**Topic**: `policy-events.v1`

```java
@Service
public class PolicyEventConsumer {
    
    private final PolicyProjectionRepository projectionRepository;
    
    @KafkaListener(
        topics = "policy-events.v1",
        groupId = "claims-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePolicyEvent(DomainEvent event,
                                  Acknowledgment ack) {
        try {
            logger.info("Received policy event: {}", event.getEventType());
            
            switch (event.getEventType()) {
                case "POLICY_ISSUED" -> {
                    PolicyEventPayload payload = objectMapper.convertValue(
                        event.getPayload(), PolicyEventPayload.class);
                    
                    PolicyProjection projection = new PolicyProjection();
                    projection.setPolicyId(payload.getPolicyId());
                    projection.setPolicyNumber(payload.getPolicyNumber());
                    projection.setStatus(PolicyStatus.valueOf(payload.getStatus()));
                    projection.setClaimable(true);
                    
                    projectionRepository.save(projection);
                    logger.info("Created policy projection for {}", 
                        payload.getPolicyId());
                }
                
                case "POLICY_RENEWED" -> {
                    PolicyProjection proj = projectionRepository
                        .findByPolicyId(payload.getPolicyId())
                        .orElse(null);
                    if (proj != null) {
                        proj.setStatus(PolicyStatus.RENEWED);
                        projectionRepository.save(proj);
                    }
                }
                
                case "POLICY_LAPSED" -> {
                    PolicyProjection proj = projectionRepository
                        .findByPolicyId(payload.getPolicyId())
                        .orElse(null);
                    if (proj != null) {
                        proj.setClaimable(false);
                        projectionRepository.save(proj);
                    }
                }
            }
            
            ack.acknowledge();  // Manual acknowledgment
        } catch (Exception e) {
            logger.error("Failed to process policy event", e);
            // Don't acknowledge, retry
        }
    }
}
```

**Why Manual Acknowledgment**:
```
Auto Acknowledge:
  ├─ Consumer reads message
  ├─ Automatically marks as consumed
  ├─ Processing fails
  └─ Message lost forever ✗

Manual Acknowledge:
  ├─ Consumer reads message
  ├─ Processes successfully
  ├─ Explicitly call ack.acknowledge()
  ├─ If error before ack: Message redelivered
  └─ No data loss ✓
```

---

## Components

### 1. **Policy Projection Client (Circuit Breaker)**

**File**: `PolicyProjectionClient.java`

```java
@Service
public class PolicyProjectionClient {
    
    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final PolicyProjectionCache cache;
    
    public PolicyProjectionClient(RestTemplateBuilder builder,
                                  CircuitBreakerFactory factory,
                                  PolicyProjectionCache cache) {
        this.restTemplate = builder.build();
        this.circuitBreaker = factory.create("policy-service");
        this.cache = cache;
    }
    
    public PolicyProjectionResponse getPolicyProjection(String policyId) {
        // Try cache first
        PolicyProjectionResponse cached = cache.get(policyId);
        if (cached != null) {
            return cached;
        }
        
        // Fall back to service with circuit breaker
        return circuitBreaker.execute(
            () -> {
                try {
                    ResponseEntity<PolicyProjectionResponse> response =
                        restTemplate.getForEntity(
                            "http://policy-service:8082/internal/policies/" + policyId,
                            PolicyProjectionResponse.class
                        );
                    
                    if (response.getStatusCode() == HttpStatus.OK) {
                        PolicyProjectionResponse proj = response.getBody();
                        cache.put(policyId, proj);  // Cache for 15 min
                        return proj;
                    }
                    throw new ApiException(HttpStatus.NOT_FOUND, 
                        "Policy not found");
                } catch (HttpClientErrorException.NotFound e) {
                    throw new ApiException(HttpStatus.NOT_FOUND, 
                        "Policy not found");
                }
            },
            throwable -> {
                // Fallback if circuit open
                PolicyProjectionResponse cached2 = cache.get(policyId);
                if (cached2 != null) {
                    logger.warn("Circuit breaker open, using old cache");
                    return cached2;
                }
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Policy service unavailable");
            }
        );
    }
}
```

**Circuit Breaker States**:

```
CLOSED (Normal):
  ├─ Allow requests through
  └─ Count failures
  
OPEN (Too many failures):
  ├─ Reject requests immediately
  ├─ Return cached response (fallback)
  └─ Wait 30s before trying again
  
HALF_OPEN (Testing recovery):
  ├─ Allow 1 test request
  ├─ If succeeds: Return to CLOSED
  ├─ If fails: Return to OPEN
  └─ Prevents cascade failures
```

**Configuration**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      policy-service:
        registerHealthIndicator: true
        slidingWindowSize: 10        # Last 10 requests
        failureRateThreshold: 50     # > 50% fail = open
        waitDurationInOpenState: 30000  # Wait 30s
        permittedNumberOfCallsInHalfOpenState: 1
```

### 2. **PolicyProjectionCache**

**File**: `PolicyProjectionCache.java`

```java
@Service
public class PolicyProjectionCache {
    
    private final RedisTemplate<String, PolicyProjectionResponse> redisTemplate;
    private static final String CACHE_KEY_PREFIX = "policy:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    
    public PolicyProjectionResponse get(String policyId) {
        try {
            return redisTemplate.opsForValue()
                .get(CACHE_KEY_PREFIX + policyId);
        } catch (Exception e) {
            logger.warn("Cache get failed for {}", policyId);
            return null;  // Fail silently, fall back to service
        }
    }
    
    public void put(String policyId, PolicyProjectionResponse response) {
        try {
            redisTemplate.opsForValue().set(
                CACHE_KEY_PREFIX + policyId,
                response,
                CACHE_TTL
            );
        } catch (Exception e) {
            logger.warn("Cache put failed for {}", policyId);
            // Fail silently, service still works without cache
        }
    }
    
    public void invalidate(String policyId) {
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + policyId);
        } catch (Exception e) {
            logger.warn("Cache delete failed for {}", policyId);
        }
    }
}
```

**Why Redis for Policy Cache**:
```
In-Memory Cache (HashMap):
  ├─ Fast but confined to single instance
  ├─ Scales with number of pods (wasteful)
  └─ No cache invalidation across instances

Redis Cache:
  ├─ Shared across all Claims Service instances
  ├─ TTL automatic cleanup (15 mins)
  ├─ Survives pod restarts
  ├─ Invalidation via Kafka events
  └─ Scales efficiently
```

### 3. **ClientCredentialsTokenProvider**

**File**: `ClientCredentialsTokenProvider.java`

**Purpose**: Obtain OAuth2 tokens for requests to Policy Service (M2M auth)

```java
@Service
public class ClientCredentialsTokenProvider {
    
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, String> redis;
    private final ClaimsServiceProperties properties;
    private static final Duration TOKEN_CACHE_DURATION = Duration.ofMinutes(23);
    
    public String getAccessToken() {
        // Try cache first
        String cached = redis.opsForValue().get("token:claims-service");
        if (cached != null) {
            return cached;
        }
        
        // Call Auth Service
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", properties.getClientId());
        body.add("client_secret", properties.getClientSecret());
        body.add("scope", "policy.read");
        body.add("grant_type", "client_credentials");
        
        ResponseEntity<ClientCredentialsTokenResponse> response =
            restTemplate.postForEntity(
                "http://auth-service:8081/api/auth/oauth2/token",
                body,
                ClientCredentialsTokenResponse.class
            );
        
        String token = response.getBody().getAccessToken();
        
        // Cache for 23 hours (token valid 24 hours)
        redis.opsForValue().set(
            "token:claims-service",
            token,
            TOKEN_CACHE_DURATION
        );
        
        return token;
    }
}
```

**Why Service-to-Service Auth**:
```
Without Auth:
  ├─ Policy Service accepts requests from anywhere
  ├─ Any service can call
  └─ Security vulnerability

With OAuth2:
  ├─ Claims Service must have credentials
  ├─ Token issued by Auth Service
  ├─ Token contains scope (what it can do)
  └─ Policy Service verifies token before responding

Flow:
  1. Claims Service requests token from Auth Service
  2. Uses client_id + client_secret (configured securely)
  3. Gets Bearer token (valid 24 hours)
  4. Includes token in calls to Policy Service
  5. Policy Service validates token
  6. Only requests from authenticated services succeed
```

### 4. **ClaimsOutboxPublisher**

Similar to Policy Service (see [POLICY_SERVICE.md](POLICY_SERVICE.md#4-policyoutboxpublisher-event-publishing))

```java
@Component
public class ClaimsOutboxPublisher {
    
    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository
            .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        
        for (OutboxEvent event : pending) {
            try {
                DomainEvent domainEvent = convertToEvent(event);
                kafkaTemplate.send("claim-events.v1",
                    event.getAggregateId(), domainEvent).get();
                
                event.setStatus(OutboxStatus.PUBLISHED);
                outboxRepository.save(event);
                metricsService.recordEventPublished(event.getEventType());
            } catch (Exception e) {
                logger.error("Failed to publish event", e);
            }
        }
    }
}
```

---

## Resilience Patterns

### 1. **Circuit Breaker Pattern**

**Protects Against**:
- Policy Service being slow/down
- Cascading failures
- Resource exhaustion

**Flow**:
```
Normal (CLOSED):
  Request → Policy Service → Success

After 5 failures (OPEN):
  Request → Return cached response immediately
  No network call (fast fail)

After 30s (HALF_OPEN):
  1 test request → Policy Service
  If succeeds: Close circuit
  If fails: Reopen circuit
```

### 2. **Caching Strategy**

**Three-Level Cache**:
```
1. Redis (15 mins):
   ├─ Shared across all instances
   ├─ Survives requests spike
   └─ Can be invalidated via Kafka

2. Local memory (circuit breaker fallback):
   ├─ Oldest cached data
   ├─ Used if Redis/service down
   └─ Stale but available

3. Policy Projection table:
   ├─ Source of truth if service down
   ├─ Updated via Kafka events
   └─ Immediate consistency
```

### 3. **Idempotency**

Prevents duplicate claims even on network retries:
```
Submit claim request
  ↓ (with Idempotency-Key: uuid-1)
  ├─ Check table: Not found
  ├─ Create claim
  ├─ Return response
  ├─ Save idempotency record
  
Retry with same key:
  ├─ Check table: Found
  ├─ Return cached response
  └─ No duplicate created
```

---

## API Endpoints

### 1. Submit Claim

```http
POST /api/claims
Authorization: Bearer {token}
Idempotency-Key: {uuid4}
Content-Type: application/json

{
  "policyId": "uuid-1234",
  "description": "Car damaged in accident",
  "claimAmount": 2500.00
}

Response: 201 CREATED
{
  "id": "claim-uuid-1",
  "policyId": "uuid-1234",
  "policyholderId": "uuid-5678",
  "status": "SUBMITTED",
  "claimAmount": 2500.00,
  "createdAt": "2024-01-15T10:30:00Z"
}

Error: 400 BAD_REQUEST (if policy not claimable)
{
  "error": "Claims are not allowed for this policy"
}
```

### 2. Verify Claim

```http
PATCH /api/claims/claim-uuid-1/verify
Authorization: Bearer {token}
Content-Type: application/json

{
  "notes": "Documents verified and valid"
}

Response: 200 OK
{
  "id": "claim-uuid-1",
  "status": "VERIFIED",
  "decisionNotes": "Documents verified and valid"
}
```

### 3. Approve Claim

```http
PATCH /api/claims/claim-uuid-1/approve
Authorization: Bearer {token}
Content-Type: application/json

{
  "notes": "All criteria met, approved for payment"
}

Response: 200 OK
{
  "id": "claim-uuid-1",
  "status": "APPROVED",
  "decisionNotes": "All criteria met, approved for payment"
}
```

### 4. List Claims

```http
GET /api/claims?page=0&size=10&status=SUBMITTED
Authorization: Bearer {token}

Response: 200 OK
{
  "content": [
    {
      "id": "claim-uuid-1",
      "policyId": "uuid-1234",
      "status": "SUBMITTED",
      "claimAmount": 2500.00
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 50
}
```

---

## Summary

The **Claims Service** provides:
1. **Claim Lifecycle Management**: From submission to approval
2. **Policy Integration**: REST + Circuit breaker + caching
3. **Event-Driven Updates**: Via Kafka listener
4. **Resilience**: 3-level caching, fallbacks, graceful degradation
5. **Authorization**: Role-based access with verification workflow

Design enables fast claims processing even with Policy Service failures, thanks to projections and intelligent caching.
