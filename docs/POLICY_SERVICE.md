# Policy Service - Detailed Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Functionality](#core-functionality)
4. [Data Model](#data-model)
5. [Components](#components)
6. [Event Publishing](#event-publishing)
7. [API Endpoints](#api-endpoints)
8. [Code Logic Explanation](#code-logic-explanation)

---

## Overview

The **Policy Service** is the **core business service** for managing insurance policies throughout their lifecycle. It handles:

- **Policy Issuance**: Create new insurance policies with terms and premium
- **Policy Renewal**: Extend existing policies with new terms
- **Policy Lapsing**: Mark policies as inactive when not renewed
- **Policy Queries**: List and retrieve policies with role-based filtering
- **Event Publishing**: Emit domain events to Kafka for other services

**Port**: `8082`  
**Database**: PostgreSQL  
**Technology Stack**: Spring Boot, JPA/Hibernate, PostgreSQL, Kafka, Transactional Outbox Pattern

---

## Architecture

### Service Interaction Flow

```
Client Request (Authenticated with JWT)
    ↓
API Gateway validates JWT & forwards with X-User-Id header
    ↓
PolicyController receives request
    ↓
PolicyService.issuePolicy() or renewPolicy()
    ├─ Extract authenticated actor from headers
    ├─ Validate idempotency key (prevent duplicate operations)
    ├─ Check authorization (role-based access)
    ├─ Create/update Policy entity
    ├─ Persist OutboxEvent (transactional outbox pattern)
    ├─ Save idempotency record
    └─ Return PolicyResponse
    ↓
PolicyOutboxPublisher (background job)
    ├─ Scan OutboxEvent table (status=PENDING)
    ├─ Publish to Kafka
    ├─ Mark as PUBLISHED
    └─ Delete old events (cleanup)
    ↓
Claims Service listens to POLICY_ISSUED events
    └─ Synchronizes policy data locally (cache)

Event-Driven Architecture Benefits:
  ✓ Loose coupling: Policy Service doesn't know about Claims Service
  ✓ Scalability: Services process events at their own pace
  ✓ Reliability: Outbox ensures event delivery even if network fails
  ✓ Auditability: All events stored in database
```

---

## Core Functionality

### 1. **Issue Policy**

**Endpoint**: `POST /api/policies`  
**Required Role**: ADMIN, AGENT  
**Idempotent**: Yes

**Flow**:
```
1. Extract authenticated actor (userId, role)
2. Hash request body to check idempotency
3. Query idempotency table:
   ├─ If found with SAME request hash:
   │  └─ Return cached response (same request, re-executed)
   ├─ If found with DIFFERENT request hash:
   │  └─ Throw 409 CONFLICT (key reused with different data)
   └─ If not found: Continue
4. Validate authorization:
   ├─ POLICYHOLDER ✗ (not allowed)
   ├─ AGENT: Can only issue for themselves
   └─ ADMIN: Can issue for any agent
5. Create Policy entity:
   ├─ Generate unique policy number ("POL-" + UUID)
   ├─ Set status to ISSUED
   ├─ Set premium, dates, assigned agent
   └─ Save to database
6. Publish POLICY_ISSUED event (transactional outbox)
7. Save idempotency record
8. Return PolicyResponse
```

**Why Idempotency**:
```
Scenario 1: Normal request
  Request 1: "Issue policy for John"
    → Database saves policy
    → Response sent to client
    Idempotency key saved

Scenario 2: Network timeout, client retries with SAME key
  Request 2: "Issue policy for John" (same key)
    → Idempotency table returns cached response
    → No duplicate policy created
    → Client thinks it's a new response, but same data
    → Success ✓

Scenario 3: Attacker replays request with same key BUT different data
  Request 3: "Issue policy for Alice" (same key)
    → Idempotency table: Key exists
    → Check request hash: Different!
    → Throw 409 CONFLICT
    → Prevent protocol violation
```

### 2. **Renew Policy**

**Endpoint**: `PATCH /api/policies/{policyId}/renew`  
**Required Role**: ADMIN, AGENT (if agent is assigned)

**Flow**:
```
1. Load policy from database
2. Check access:
   ├─ ADMIN: Can renew any policy
   ├─ AGENT: Can only renew own assigned policies
   └─ POLICYHOLDER: Cannot renew
3. Check policy status:
   ├─ If LAPSED: Cannot renew (allow new policy instead)
   ├─ If ISSUED/RENEWED: Allowed
   └─ Else: Throw BAD_REQUEST
4. Update policy:
   ├─ Set status to RENEWED
   ├─ Update premium
   ├─ Update end date
   └─ Save
5. Publish POLICY_RENEWED event
6. Return updated PolicyResponse
```

**Why Can't Renew Lapsed Policies**:
```
Lapsed = didn't renew before expiry
  ├─ User abandoned the policy
  ├─ Might have unresolved claims
  ├─ Might have moved to competitor

Better UX:
  └─ Issue new policy (clean slate)
  └─ Don't renew expired one
```

### 3. **Lapse Policy**

**Endpoint**: `PATCH /api/policies/{policyId}/lapse`  
**Required Role**: ADMIN, AGENT (if agent is assigned)

**Flow**:
```
1. Load policy from database
2. Check access (same as renewal)
3. Check if already lapsed:
   ├─ If yes: Return current state (idempotent)
   └─ If no: Continue
4. Mark policy as LAPSED
5. Publish POLICY_LAPSED event
6. Return PolicyResponse
```

**Why Idempotent Response**:
```
If already lapsed:
  └─ Client retries: GET policy
  └─ Returns LAPSED status
  └─ Client is happy (goal achieved)
  └─ Avoid error on retry

Without idempotency:
  ├─ First lapse: Success
  ├─ Client retries (timeout)
  ├─ Second lapse: 400 BAD_REQUEST "Already lapsed"
  ├─ Client code must handle this error
  ├─ More complex error handling
  └─ Increases bug surface
```

### 4. **List Policies (With Filtering)**

**Endpoint**: `GET /api/policies?page=0&size=10&sort=createdAt,desc`

**Role-Based Filtering**:
```java
Page<Policy> results = switch (actor.role()) {
    case ADMIN -> policyRepository.findAll(pageable);
    case AGENT -> policyRepository.findAllByAssignedAgentId(actor.userId(), pageable);
    case POLICYHOLDER -> policyRepository.findAllByPolicyholderId(actor.userId(), pageable);
};
```

**Authorization at Database Level**:
- ADMIN: Can list all policies
- AGENT: Can only list own assigned policies
- POLICYHOLDER: Can only list own policies

**Pagination Details**:
```
page=0&size=10&sort=createdAt,desc

Validation:
  ├─ page: Max 0 (no negatives)
  ├─ size: Between 1-100 (prevent resource exhaustion)
  └─ sort: Validated against allowed fields

Query:
  ├─ Offset: page * size = 0 * 10 = 0
  ├─ Limit: 10
  ├─ Order by: createdAt DESC
  └─ Count total (for pagination info)

Response: PagedResponse<PolicyResponse>
  ├─ content: List of 10 policies
  ├─ page: 0
  ├─ size: 10
  ├─ totalElements: 250 (if exists)
  └─ totalPages: 25
```

---

## Data Model

### 1. **Policy Entity**

```java
@Entity
@Table(name = "policies")
public class Policy {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(unique = true, nullable = false)
    private String policyNumber;  // "POL-ABC12345"
    
    @Column(nullable = false)
    private String policyholderId;  // User ID
    
    @Column(nullable = false)
    private String assignedAgentId; // Agent user ID
    
    @Enumerated(EnumType.STRING)
    private PolicyStatus status;    // ISSUED, RENEWED, LAPSED
    
    @Column(nullable = false)
    private BigDecimal premium;     // 500.00
    
    @Column(nullable = false)
    private LocalDate startDate;
    
    @Column(nullable = false)
    private LocalDate endDate;
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
}
```

**Indexes for Performance**:
```sql
CREATE INDEX idx_policyholder_id ON policies(policyholderId);
CREATE INDEX idx_assigned_agent_id ON policies(assignedAgentId);
CREATE INDEX idx_status ON policies(status);
CREATE INDEX idx_created_at ON policies(createdAt);
```

### 2. **PolicyStatus Enum**

```java
public enum PolicyStatus {
    ISSUED,    // Policy just created
    RENEWED,   // Policy has been renewed
    LAPSED     // Policy expired and not renewed
}
```

**Lifecycle**:
```
ISSUED
  ├─ Can be renewed → RENEWED
  └─ Can be lapsed → LAPSED

RENEWED
  ├─ Can be renewed again → RENEWED
  └─ Can be lapsed → LAPSED

LAPSED
  └─ Terminal state (no transitions)
```

### 3. **OutboxEvent Entity (Event Sourcing)**

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(nullable = false)
    private String aggregateId;  // Policy ID
    
    @Enumerated(EnumType.STRING)
    private AggregateType aggregateType;  // POLICY
    
    @Column(nullable = false)
    private String eventType;  // POLICY_ISSUED, POLICY_RENEWED, ...
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;  // JSON: {"policyId": "...", "status": "..."}
    
    @Enumerated(EnumType.STRING)
    private OutboxStatus status;  // PENDING, PUBLISHED
    
    @CreationTimestamp
    private Instant createdAt;
    
    @Column(name = "published_at")
    private Instant publishedAt;
    
    @Version
    private Long version;  // Optimistic locking
}
```

**Why Separate Outbox Table**:
```
Without Outbox:
  1. Save policy to policies table
  2. Publish event to Kafka
  
  Problem: If Kafka fails, event lost
           Policy exists but no event published
           Other services don't know

With Outbox:
  1. Save policy to policies table
  2. Save event to outbox_events table (same transaction)
  3. Background job publishes from outbox to Kafka
  
  Problem solved: Event guaranteed to be published
                  Even if process crashes during publish
                  Retry logic picks it up
```

### 4. **IdempotencyRecord Entity**

```java
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(nullable = false)
    private String operation;  // "ISSUE_POLICY", "RENEW_POLICY"
    
    @Column(nullable = false)
    private String actorId;  // User ID making request
    
    @Column(nullable = false)
    private String idempotencyKey;  // From request header
    
    @Column(nullable = false)
    private String requestHash;  // SHA-256 of request body
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String responseBody;  // Cached JSON response
    
    @Column(nullable = false)
    private String responseId;  // ID of created entity
    
    @CreationTimestamp
    private Instant createdAt;
    
    @Version
    private Long version;
}
```

**Database Constraints**:
```sql
UNIQUE(operation, actorId, idempotencyKey)
```

**Why This Design**:
```
Idempotency By Caching:
  1st Call: "Issue policy" (idempotencyKey: uuid-1)
    → Save to database
    → Return response
    → Cache response in idempotency_records
  
  2nd Call: Same "Issue policy" (same uuid-1)
    → Query idempotency_records
    → Find cached response
    → Return same response (no database operation)

Protection Against Misuse:
  Request 1: idempotencyKey: uuid-1, data: "John"
    → Cached
  
  Request 2: idempotencyKey: uuid-1, data: "Alice"
    → Different hash!
    → 409 CONFLICT
    → Force client to use different key or re-use same data
```

---

## Components

### 1. **PolicyController**

**File**: `PolicyController.java`

**Public Methods**:
```java
@PostMapping
public PolicyResponse issuePolicy(
    Authentication auth,
    @RequestBody IssuePolicyRequest request,
    @RequestHeader("Idempotency-Key") String key)

@PatchMapping("/{policyId}/renew")
public PolicyResponse renewPolicy(
    Authentication auth,
    @PathVariable String policyId,
    @RequestBody RenewPolicyRequest request)

@PatchMapping("/{policyId}/lapse")
public PolicyResponse lapsePolicy(
    Authentication auth,
    @PathVariable String policyId)

@GetMapping("/{policyId}")
public PolicyResponse getPolicy(
    Authentication auth,
    @PathVariable String policyId)

@GetMapping
public PagedResponse<PolicyResponse> listPolicies(
    Authentication auth,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size,
    @RequestParam(defaultValue = "createdAt,desc") String sort)
```

### 2. **PolicyService (Business Logic)**

**File**: `PolicyService.java`

#### Key Methods

##### `issuePolicy(Authentication, IssuePolicyRequest, String idempotencyKey)`

```java
@Transactional
public PolicyResponse issuePolicy(Authentication auth,
                                  IssuePolicyRequest request, 
                                  String idempotencyKey) {
    AuthenticatedActor actor = securityUtils.currentActor(auth);
    String requestHash = hashRequest(request);
    
    // Check idempotency
    IdempotencyRecord existing = idempotencyRecordRepository
        .findByOperationAndActorIdAndIdempotencyKey(
            "ISSUE_POLICY", 
            actor.userId(), 
            idempotencyKey)
        .orElse(null);
    
    if (existing != null) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Idempotency key already used with different request");
        }
        return deserialize(existing.getResponseBody(), PolicyResponse.class);
    }
    
    // Authorization
    if (actor.role() == UserRole.POLICYHOLDER) {
        throw new ApiException(HttpStatus.FORBIDDEN, 
            "Policyholders cannot issue policies");
    }
    if (actor.role() == UserRole.AGENT && 
        !actor.userId().equals(request.assignedAgentId())) {
        throw new ApiException(HttpStatus.FORBIDDEN, 
            "Agents can only issue for themselves");
    }
    
    // Create policy
    Policy policy = new Policy();
    policy.setPolicyNumber("POL-" + UUID.randomUUID()
        .toString().substring(0, 8).toUpperCase());
    policy.setPolicyholderId(request.policyholderId());
    policy.setAssignedAgentId(actor.role() == UserRole.AGENT 
        ? actor.userId() 
        : request.assignedAgentId());
    policy.setStatus(PolicyStatus.ISSUED);
    policy.setPremium(request.premium());
    policy.setStartDate(request.startDate());
    policy.setEndDate(request.endDate());
    
    Policy saved = policyRepository.save(policy);
    
    // Publish event
    persistOutboxEvent(saved, "POLICY_ISSUED", actor);
    
    // Cache result
    PolicyResponse response = map(saved);
    persistIdempotencyRecord("ISSUE_POLICY", actor.userId(), 
        idempotencyKey, requestHash, response, response.id());
    
    return response;
}
```

**Key Points**:
1. **Hash Request**: Convert JSON to deterministic hash
   ```java
   MessageDigest.getInstance("SHA-256")
       .digest(objectMapper.writeValueAsBytes(request))
   ```
2. **Check Idempotency**: Query database first
3. **Authorization**: Role + ownership checks
4. **Atomic Save**: Policy + OutboxEvent in one transaction
5. **Cache Response**: Store for future retries

##### `renewPolicy(Authentication, String policyId, RenewPolicyRequest)`

```java
@Transactional
public PolicyResponse renewPolicy(Authentication auth,
                                  String policyId,
                                  RenewPolicyRequest request) {
    AuthenticatedActor actor = securityUtils.currentActor(auth);
    
    Policy policy = requireAccessibleManagedPolicy(actor, policyId);
    
    if (policy.getStatus() == PolicyStatus.LAPSED) {
        throw new ApiException(HttpStatus.BAD_REQUEST, 
            "Lapsed policies cannot be renewed");
    }
    
    policy.setStatus(PolicyStatus.RENEWED);
    policy.setPremium(request.premium());
    policy.setEndDate(request.newEndDate());
    
    Policy saved = policyRepository.save(policy);
    persistOutboxEvent(saved, "POLICY_RENEWED", actor);
    
    return map(saved);
}
```

##### `lapsePolicy(Authentication, String policyId)`

```java
@Transactional
public PolicyResponse lapsePolicy(Authentication auth, String policyId) {
    AuthenticatedActor actor = securityUtils.currentActor(auth);
    Policy policy = requireAccessibleManagedPolicy(actor, policyId);
    
    if (policy.getStatus() == PolicyStatus.LAPSED) {
        return map(policy);  // Already lapsed, return as-is
    }
    
    policy.setStatus(PolicyStatus.LAPSED);
    Policy saved = policyRepository.save(policy);
    persistOutboxEvent(saved, "POLICY_LAPSED", actor);
    
    return map(saved);
}
```

##### Helper Methods

```java
private void persistOutboxEvent(Policy policy, String eventType,
                                AuthenticatedActor actor) {
    PolicyEventPayload payload = new PolicyEventPayload(
        policy.getId(),
        policy.getPolicyNumber(),
        policy.getStatus().name(),
        actor.userId(),
        actor.role().name()
    );
    
    OutboxEvent event = new OutboxEvent();
    event.setAggregateId(policy.getId());
    event.setAggregateType(AggregateType.POLICY);
    event.setEventType(eventType);
    event.setPayload(objectMapper.writeValueAsString(payload));
    event.setStatus(OutboxStatus.PENDING);
    
    outboxEventRepository.save(event);
}

private Policy requireAccessibleManagedPolicy(AuthenticatedActor actor, 
                                              String policyId) {
    Policy policy = policyRepository.findById(policyId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
            "Policy not found"));
    
    // Authorization check
    return switch (actor.role()) {
        case ADMIN -> policy;
        case AGENT -> {
            if (!policy.getAssignedAgentId().equals(actor.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                    "Cannot access other agents' policies");
            }
            yield policy;
        }
        case POLICYHOLDER -> throw new ApiException(HttpStatus.FORBIDDEN,
            "Policyholders cannot manage policies");
    };
}
```

### 3. **InternalPolicyController**

**File**: `InternalPolicyController.java`

**Purpose**: Read-only endpoints for other microservices (Claims, API Gateway)

```java
@RestController
@RequestMapping("/internal/policies")
public class InternalPolicyController {
    
    @GetMapping("/{policyId}")
    public PolicyProjectionResponse getPolicy(@PathVariable String policyId) {
        return policyService.getPublicProjection(policyId);
    }
}
```

**Why Internal Endpoint**:
- Not exposed through API Gateway
- Only accessible from same Docker network
- Used by Claims Service to verify policies
- No authentication needed (internal only)

### 4. **PolicyOutboxPublisher (Event Publishing)**

**File**: `PolicyOutboxPublisher.java`

**Purpose**: Background job that publishes events from outbox to Kafka

```java
@Component
public class PolicyOutboxPublisher {
    
    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)  // Every 1 second
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository
            .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        
        for (OutboxEvent event : pending) {
            try {
                // Convert to DomainEvent
                DomainEvent domainEvent = new DomainEvent(
                    event.getAggregateId(),
                    event.getAggregateType(),
                    event.getEventType(),
                    objectMapper.readTree(event.getPayload()),
                    event.getCreatedAt()
                );
                
                // Publish to Kafka
                kafkaTemplate.send("policy-events.v1", 
                    event.getAggregateId(), 
                    domainEvent).get();  // Wait for confirmation
                
                // Mark as published
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);
                
                // Log metrics
                metricsService.recordEventPublished(event.getEventType());
            } catch (Exception e) {
                // Log error, retry next cycle
                logger.error("Failed to publish event: " + event.getId(), e);
            }
        }
        
        // Cleanup old published events (older than 7 days)
        outboxRepository.deletePublishedBefore(
            Instant.now().minus(7, ChronoUnit.DAYS));
    }
}
```

**Why Scheduled Job**:
```
Without outbox publisher:
  ├─ Events published synchronously
  ├─ If Kafka slow, policy endpoint takes 500ms
  └─ Poor user experience

With outbox publisher:
  ├─ Policy saved immediately
  ├─ Event published asynchronously
  ├─ Policy endpoint returns in 50ms
  ├─ User happy immediately
  └─ Event publishes reliably in background
```

**Why `fixedDelay=1000`**:
```
Balances:
  ├─ Kafka latency: Most events published within 1 second
  ├─ Database load: Doesn't hammer database
  ├─ CPU: Lightweight loop
  └─ Configuration: Tunable for different load profiles
```

---

## Event Publishing

### Kafka Topic: `policy-events.v1`

**Event Schema**:
```json
{
  "aggregateId": "uuid-1234",
  "aggregateType": "POLICY",
  "eventType": "POLICY_ISSUED",
  "payload": {
    "policyId": "uuid-1234",
    "policyNumber": "POL-ABC12345",
    "status": "ISSUED",
    "actorId": "uuid-5678",
    "actorRole": "AGENT"
  },
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### Event Types Emitted

| Event | When | Reason |
|-------|------|--------|
| POLICY_ISSUED | New policy created | Claims Service creates projection |
| POLICY_RENEWED | Policy renewed | Update cached policy data |
| POLICY_LAPSED | Policy expired | Mark policies as inactive |

### Consumer: Claims Service

**Kafka Listener**:
```java
@KafkaListener(topics = "policy-events.v1", ...)
public void handlePolicyEvent(DomainEvent event, 
                              Acknowledgment ack) {
    switch (event.getEventType()) {
        case "POLICY_ISSUED" -> {
            PolicyProjection proj = createFromPayload(event.getPayload());
            projectionRepo.save(proj);
        }
        case "POLICY_RENEWED" -> {
            PolicyProjection proj = projectionRepo.findById(...).orElse(null);
            if (proj != null) updateFromPayload(proj, event.getPayload());
        }
        case "POLICY_LAPSED" -> {
            PolicyProjection proj = projectionRepo.findById(...).orElse(null);
            if (proj != null) proj.setClaimable(false);
        }
    }
    ack.acknowledge();  // Manual acknowledgment
}
```

---

## API Endpoints

### 1. Issue Policy

```http
POST /api/policies
Authorization: Bearer {token}
Idempotency-Key: {uuid4}
Content-Type: application/json

{
  "policyholderId": "uuid-1234",
  "assignedAgentId": "uuid-5678",
  "premium": 500.00,
  "startDate": "2024-01-01",
  "endDate": "2024-12-31"
}

Response: 201 CREATED
{
  "id": "uuid-9012",
  "policyNumber": "POL-ABC12345",
  "policyholderId": "uuid-1234",
  "assignedAgentId": "uuid-5678",
  "status": "ISSUED",
  "premium": 500.00,
  "startDate": "2024-01-01",
  "endDate": "2024-12-31"
}
```

### 2. Renew Policy

```http
PATCH /api/policies/uuid-9012/renew
Authorization: Bearer {token}
Content-Type: application/json

{
  "premium": 550.00,
  "newEndDate": "2025-12-31"
}

Response: 200 OK
{
  "id": "uuid-9012",
  "policyNumber": "POL-ABC12345",
  "status": "RENEWED",
  "premium": 550.00,
  "endDate": "2025-12-31"
}
```

### 3. Lapse Policy

```http
PATCH /api/policies/uuid-9012/lapse
Authorization: Bearer {token}

Response: 200 OK
{
  "id": "uuid-9012",
  "status": "LAPSED"
}
```

### 4. Get Policy

```http
GET /api/policies/uuid-9012
Authorization: Bearer {token}

Response: 200 OK
{ same as above }
```

### 5. List Policies

```http
GET /api/policies?page=0&size=10&sort=createdAt,desc
Authorization: Bearer {token}

Response: 200 OK
{
  "content": [ ... ],
  "page": 0,
  "size": 10,
  "totalElements": 100,
  "totalPages": 10
}
```

---

## Code Logic Explanation

### Idempotency Hashing

```java
private String hashRequest(IssuePolicyRequest request) {
    try {
        byte[] jsonBytes = objectMapper.writeValueAsBytes(request);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(jsonBytes);
        return HexFormat.of().formatHex(hash);
    } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
    }
}
```

**Why SHA-256**:
```
Request 1:
  {policyId: "123", amount: 500}
  → SHA-256 → "a3f4d8c..."

Request 2 (identical):
  {policyId: "123", amount: 500}
  → SHA-256 → "a3f4d8c..." (SAME HASH)

Request 3 (different):
  {policyId: "123", amount: 501}
  → SHA-256 → "b2e1c9d..." (DIFFERENT HASH)

Properties:
  ✓ Deterministic: Same input → same hash
  ✓ Avalanche: Tiny change → completely different hash
  ✓ Fast: Microsecond computation
  ✓ Collision-resistant: 2^256 possibilities
```

### Authorization Pattern

```java
private Policy requireAccessibleManagedPolicy(AuthenticatedActor actor, 
                                              String policyId) {
    Policy policy = policyRepository.findById(policyId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ...));
    
    return switch (actor.role()) {
        case ADMIN -> policy;  // Full access
        case AGENT -> {
            if (!policy.getAssignedAgentId().equals(actor.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, ...);
            }
            yield policy;  // Switch expression syntax
        }
        case POLICYHOLDER -> throw new ApiException(HttpStatus.FORBIDDEN, ...);
    };
}
```

**Why Switch Expression Pattern**:
```
Traditional if-else:
  if (actor.role() == UserRole.ADMIN) {
      return policy;
  } else if (actor.role() == UserRole.AGENT) {
      ...
      return policy;
  } else {
      throw ...
  }
  
Cleaner with switch:
  ✓ Exhaustive (compiler ensures all cases covered)
  ✓ Returns value (no temp variables)
  ✓ More readable (pattern matching)
  ✓ Less error-prone (can't forget return)
```

### Transactional Outbox Pattern

```java
@Transactional
public PolicyResponse issuePolicy(...) {
    // All database operations in one transaction
    
    Policy saved = policyRepository.save(policy);
    // At this point, in transaction (not yet committed)
    
    persistOutboxEvent(saved, "POLICY_ISSUED", actor);
    // OutboxEvent also saved in same transaction
    
    return map(saved);
    // Transaction commit AFTER method returns
}
```

**Why This Works**:

```
Normal approach (fails):
  1. Save Policy → commit
  2. Send to Kafka
  3. Database crash!
  → Policy exists but event never published

Outbox approach (reliable):
  1. Save Policy + OutboxEvent in 1 transaction
  2. Commit (both or neither)
  3. Scheduled job publishes OutboxEvent
  4. Database crash during Kafka send?
     → Next job cycle retries
  5. Eventually publishes (guaranteed)
```

---

## Summary

The **Policy Service** manages insurance policies with:
1. **Complete Lifecycle**: Issue, renew, lapse policies
2. **Idempotency**: Safe retries without duplicates
3. **Authorization**: Role-based access control
4. **Event Publishing**: Reliable async event propagation via outbox pattern
5. **Scalability**: Pagination, role-based filtering, indexed queries

Event-driven architecture enables tight coupling, real-time updates, and audit trails.
