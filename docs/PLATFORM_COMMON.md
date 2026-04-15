# Platform Common - Detailed Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Models](#core-models)
4. [Event Domain](#event-domain)
5. [API Utilities](#api-utilities)
6. [Usage Guide](#usage-guide)
7. [Extension Points](#extension-points)

---

## Overview

The **Platform Common** library is a **shared Maven dependency** that contains all cross-service domain models, event definitions, and API utilities. It enables type-safe, consistent communication across microservices while promoting code reuse and preventing duplication.

**Type**: Java Maven Library  
**Dependency**: Distributed via Maven (or local repo)  
**Technology**: Spring Framework, Jackson (JSON), Java Records

---

## Why Platform Common?

### Problem Without Shared Library

```
Auth Service defines User:
  public class User {
      String id, username, role;
  }

Policy Service defines User:
  public class User {
      String id, username, role;
  }

Claims Service defines User:
  public class User {
      String id, username, role;
  }

Issues:
  ✗ Duplicate code (DRY violation)
  ✗ Version skew (inconsistent fields)
  ✗ Type-checking breaks across boundaries
  ✗ Maintenance nightmare
```

### Solution: Platform Common

```
Platform Common:
  public record User(
      String id,
      String username,
      UserRole role) {}

Services import from Platform Common:
  ✓ Single source of truth
  ✓ Consistent across all services
  ✓ Type-safe serialization
  ✓ Easy versioning
```

---

## Architecture

### Module Structure

```
platform-common/
├── pom.xml
└── src/main/java/com/rohit8020/platformcommon/
    ├── event/
    │   ├── DomainEvent.java          (Base event model)
    │   ├── AggregateType.java        (POLICY, CLAIM enum)
    │   ├── PolicyEventPayload.java   (Policy event data)
    │   ├── ClaimEventPayload.java    (Claim event data)
    │   └── EventStatus.java          (PENDING, PUBLISHED)
    │
    ├── api/
    │   └── PagedResponse.java        (Generic pagination)
    │
    └── PlatformCommonApplication.java
```

---

## Core Models

### 1. **DomainEvent (Root Event Model)**

**File**: `event/DomainEvent.java`

```java
public record DomainEvent(
    String aggregateId,
    AggregateType aggregateType,
    String eventType,
    JsonNode payload,
    Instant createdAt
) {
    // Getters provided for Jackson serialization
}
```

**Purpose**: Generic event envelope for all domain events

**Usage Example**:
```java
// Creating event
DomainEvent event = new DomainEvent(
    "policy-uuid-123",              // aggregateId
    AggregateType.POLICY,            // aggregateType
    "POLICY_ISSUED",                 // eventType
    objectMapper.valueToTree(payload), // payload as JSON
    Instant.now()                    // createdAt
);

// Publishing to Kafka
kafkaTemplate.send("policy-events.v1", event.aggregateId(), event);

// Consuming from Kafka
@KafkaListener(topics = "policy-events.v1")
public void handleEvent(DomainEvent event) {
    switch(event.eventType()) {
        case "POLICY_ISSUED" -> handlePolicyIssued(event);
        case "POLICY_RENEWED" -> handlePolicyRenewed(event);
    }
}
```

**Why JsonNode for Payload**:
```
Alternative 1 (String):
  payload: "{\n  \"policyId\": ...\n}"
  Problems:
    ✗ Must parse string to use data
    ✗ No type safety
    ✗ Harder to validate

Alternative 2 (Custom class):
  payload: new PolicyEventPayload(...)
  Problems:
    ✗ Must know type at compile time
    ✗ If format changes, breaks deserializer
    ✗ Different services use different payloads

Solution (JsonNode):
  payload: objectMapper.valueToTree({...})
  Benefits:
    ✓ Flexible: Can hold any JSON
    ✓ Type-safe: Can convert with objectMapper
    ✓ Versioning: Can add/remove fields gracefully
    ✓ Inspection: Can dynamically check fields
```

### 2. **AggregateType Enum**

**File**: `event/AggregateType.java`

```java
public enum AggregateType {
    POLICY,   // Insurance policy aggregate
    CLAIM     // Insurance claim aggregate
}
```

**Purpose**: Type-safe identification of aggregate roots

**Why Enum Instead of String**:
```
String approach:
  eventType = "POLICY"  // Easy to typo
  eventType = "Polcy"   // Typo silently accepted
  
Enum approach:
  AggregateType.POLICY   // Compiler ensures correctness
  AggregateType.POLCY    // Compilation error
  
Benefits:
  ✓ Compile-time safety
  ✓ IDE autocomplete
  ✓ Refactoring support
  ✓ No runtime string matching
```

### 3. **PolicyEventPayload**

**File**: `event/PolicyEventPayload.java`

```java
public record PolicyEventPayload(
    String policyId,
    String policyNumber,
    String status,
    String actorId,
    String actorRole
) {
    // Constructor, getters, equals, hashCode, toString auto-generated
}
```

**Java Records Benefits**:

```java
// Instead of:
public class PolicyEventPayload {
    private String policyId;
    private String policyNumber;
    private String status;
    
    public PolicyEventPayload(String policyId, String policyNumber, String status) {
        this.policyId = policyId;
        this.policyNumber = policyNumber;
        this.status = status;
    }
    
    public String getPolicyId() { return policyId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getStatus() { return status; }
    
    @Override
    public int hashCode() { ... }
    
    @Override
    public boolean equals(Object o) { ... }
    
    @Override
    public String toString() { ... }
}
// 30+ lines

// Records provide all this in 1 line:
public record PolicyEventPayload(
    String policyId,
    String policyNumber,
    String status,
    String actorId,
    String actorRole
) {}
// Auto-generates all methods
```

**Record Immutability**:
```
Records are immutable by default:
  ✓ Can't accidentally modify fields
  ✓ Thread-safe (no synchronization needed)
  ✓ Hashable (can use as Map key)
  ✓ Prevents bugs in event handlers

Example:
  PolicyEventPayload event = new PolicyEventPayload(...);
  event.policyId = "x";  // Compilation error!
  
Benefits:
  - Events are immutable facts (happened in past)
  - Can't accidentally modify event history
  - Safe to share across threads
```

**Serialization with Jackson**:
```java
// Serialization (Record → JSON)
ObjectMapper mapper = new ObjectMapper();
PolicyEventPayload payload = new PolicyEventPayload(
    "policy-123", "POL-ABC", "ISSUED", "agent-1", "AGENT");
String json = mapper.writeValueAsString(payload);
// Result: {"policyId":"policy-123","policyNumber":"POL-ABC",...}

// Deserialization (JSON → Record)
String json = "{\"policyId\":\"policy-123\",\"policyNumber\":\"POL-ABC\",...}";
PolicyEventPayload payload = mapper.readValue(json, PolicyEventPayload.class);
// Records have canonical constructor: auto-recognized by Jackson
```

### 4. **ClaimEventPayload**

**File**: `event/ClaimEventPayload.java`

```java
public record ClaimEventPayload(
    String claimId,
    String policyId,
    String policyholderId,
    BigDecimal claimAmount,
    String status,
    String actorId,
    String actorRole
) {}
```

**Similar to PolicyEventPayload** but for claim events.

### 5. **PagedResponse (Generic Pagination)**

**File**: `api/PagedResponse.java`

```java
public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public PagedResponse(List<T> content, int page, int size, 
                         long totalElements, int totalPages) {
        this.content = Objects.requireNonNull(content, "content");
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }
}
```

**Generic Type Parameter**:
```java
// For policies:
PagedResponse<PolicyResponse> page = new PagedResponse<>(
    List.of(policy1, policy2, ...),
    0,      // page
    10,     // size
    150,    // totalElements
    15      // totalPages
);

// For claims:
PagedResponse<ClaimResponse> page = new PagedResponse<>(
    List.of(claim1, claim2, ...),
    0,
    10,
    75,
    8
);

Usage:
  if (page.totalElements() == 0) {
      logger.info("No results");
  }
  
  if (page.totalPages() > 1) {
      logger.info("More pages available");
  }
  
  for (PolicyResponse policy : page.content()) {
      process(policy);
  }
```

**Why Generic Record**:
```
Without Generic:
  public class PolicyPagedResponse {
      List<PolicyResponse> content;
      ...
  }
  
  public class ClaimPagedResponse {
      List<ClaimResponse> content;
      ...
  }
  
  // Duplicate for every type!

With Generic:
  public record PagedResponse<T>(List<T> content, ...) {}
  
  // Single implementation for all types
  PagedResponse<PolicyResponse> policyPage = ...;
  PagedResponse<ClaimResponse> claimPage = ...;
  
Benefits:
  ✓ DRY (Don't Repeat Yourself)
  ✓ Type-safe
  ✓ Reusable for any response type
  ✓ Consistent pagination API
```

---

## Event Domain

### Event Flow Architecture

```
Service 1 (Policy Service)
    ├─ Create Policy
    ├─ Save to database
    ├─ Create OutboxEvent with PolicyEventPayload
    └─ Save to outbox table (same transaction)
    
Background Job (PolicyOutboxPublisher)
    ├─ Scan outbox table
    ├─ Read OutboxEvent
    ├─ Convert to DomainEvent
    └─ Publish to DomainEvent → Kafka topic
    
Kafka Topic (policy-events.v1)
    └─ DomainEvent message (serialized as JSON)
    
Service 2 (Claims Service)
    ├─ Listen to policy-events.v1
    ├─ Receive DomainEvent
    ├─ Extract PayloadEventPayload from payload
    ├─ Update local projection
    └─ Acknowledge message
```

### Event Versioning

**Current Version**: `v1`

**How Versioning Works**:
```
Kafka Topic: policy-events.v1

If we need breaking changes:
  1. Create new enum value or topic: policy-events.v2
  2. Old subscribers stay on v1
  3. New subscribers use v2
  4. Transition period: both run
  5. Deprecate v1 after migration

Example change (supporting both versions):
  @KafkaListener(topics = {"policy-events.v1", "policy-events.v2"})
  public void handlePolicyEvent(DomainEvent event) {
      switch(event.eventType()) {
          // Handle both versions
      }
  }
```

### Available Event Types

| Event Type | Aggregate | Source | Payload |
|------------|-----------|--------|---------|
| POLICY_ISSUED | POLICY | Policy Service | PolicyEventPayload |
| POLICY_RENEWED | POLICY | Policy Service | PolicyEventPayload |
| POLICY_LAPSED | POLICY | Policy Service | PolicyEventPayload |
| CLAIM_SUBMITTED | CLAIM | Claims Service | ClaimEventPayload |
| CLAIM_VERIFIED | CLAIM | Claims Service | ClaimEventPayload |
| CLAIM_APPROVED | CLAIM | Claims Service | ClaimEventPayload |
| CLAIM_REJECTED | CLAIM | Claims Service | ClaimEventPayload |

---

## API Utilities

### OutboxStatus Enum

**File**: `event/OutboxStatus.java`

```java
public enum OutboxStatus {
    PENDING,      // Waiting to be published
    PUBLISHED,    // Successfully sent to Kafka
    FAILED        // (Optional) If publishing failed
}
```

**Lifecycle**:
```
Database Insert
    ↓
OutboxEvent.status = PENDING
    ↓
OutboxPublisher picks up
    ↓
Publishes to Kafka
    ↓
OutboxEvent.status = PUBLISHED
    ↓
Cleanup job deletes (>7 days)
```

### UserRole Enum (Replicated in Services)

While each service has its own `UserRole` enum, Platform Common might include:

```java
public enum UserRole {
    ADMIN,          // Full access
    AGENT,          // Issue, renew, verify
    POLICYHOLDER    // View own, submit claims
}
```

**Why Replicated**:
```
In database each service has UserRole:
  ├─ Auth Service: User.role
  ├─ Policy Service: AuthenticatedActor.role
  ├─ Claims Service: AuthenticatedActor.role
  └─ Same enum, replicated

Why not in Platform Common:
  ├─ Each service manages its own auth
  ├─ If included in Platform Common:
  │  └─ All services must update simultaneously
  │  └─ Prevents independent scaling
  └─ Safer to keep in each service
```

---

## Usage Guide

### Using PagedResponse in Controller

```java
@RestController
@RequestMapping("/api/policies")
public class PolicyController {
    
    @GetMapping
    public PagedResponse<PolicyResponse> listPolicies(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {
        
        Page<Policy> dbResults = policyRepository.findAll(
            PageRequest.of(page, size)
        );
        
        return new PagedResponse<>(
            dbResults.stream()
                .map(this::toPolicyResponse)
                .toList(),
            dbResults.getNumber(),
            dbResults.getSize(),
            dbResults.getTotalElements(),
            dbResults.getTotalPages()
        );
    }
}
```

### Publishing Events

```java
@Service
public class PolicyService {
    
    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public void publishPolicyIssued(Policy policy) {
        // Create payload
        PolicyEventPayload payload = new PolicyEventPayload(
            policy.getId(),
            policy.getPolicyNumber(),
            policy.getStatus().name(),
            getCurrentUserId(),
            getCurrentUserRole()
        );
        
        // Convert to JsonNode
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        
        // Create DomainEvent
        DomainEvent event = new DomainEvent(
            policy.getId(),
            AggregateType.POLICY,
            "POLICY_ISSUED",
            payloadNode,
            Instant.now()
        );
        
        // Send to Kafka
        kafkaTemplate.send(
            "policy-events.v1",
            policy.getId(),  // Key: for partitioning
            event
        );
    }
}
```

### Consuming Events

```java
@Service
public class PolicyEventConsumer {
    
    private final ObjectMapper objectMapper;
    private final PolicyProjectionRepository repository;
    
    @KafkaListener(
        topics = "policy-events.v1",
        groupId = "claims-service"
    )
    public void handlePolicyEvent(DomainEvent event) {
        // Map JSON payload to type-safe record
        PolicyEventPayload payload = objectMapper.convertValue(
            event.payload(),
            PolicyEventPayload.class
        );
        
        switch(event.eventType()) {
            case "POLICY_ISSUED" -> {
                PolicyProjection projection = new PolicyProjection();
                projection.setPolicyId(payload.policyId());
                projection.setStatus(
                    PolicyStatus.valueOf(payload.status())
                );
                repository.save(projection);
                logger.info("Policy projection created: {}",
                    payload.policyId());
            }
            case "POLICY_RENEWED" -> {
                PolicyProjection proj = repository
                    .findByPolicyId(payload.policyId())
                    .orElse(null);
                if (proj != null) {
                    proj.setStatus(
                        PolicyStatus.valueOf(payload.status())
                    );
                    repository.save(proj);
                }
            }
            // ... handle other events
        }
    }
}
```

---

## Extension Points

### Adding New Event Type

**Steps**:

1. **Create Payload Record**:
```java
// platform-common/src/main/java/.../event/NewEntityEventPayload.java
public record NewEntityEventPayload(
    String newEntityId,
    String status,
    String actorId,
    String actorRole
) {}
```

2. **Update AggregateType (if new aggregate)**:
```java
public enum AggregateType {
    POLICY,
    CLAIM,
    NEW_ENTITY  // Add this
}
```

3. **Emit Event in Source Service**:
```java
DomainEvent event = new DomainEvent(
    entity.getId(),
    AggregateType.NEW_ENTITY,
    "NEW_ENTITY_CREATED",
    objectMapper.valueToTree(payload),
    Instant.now()
);
kafkaTemplate.send("new-entity-events.v1", event.aggregateId(), event);
```

4. **Subscribe in Consumer Service**:
```java
@KafkaListener(topics = "new-entity-events.v1")
public void handleNewEntityEvent(DomainEvent event) {
    NewEntityEventPayload payload = objectMapper.convertValue(
        event.payload(),
        NewEntityEventPayload.class
    );
    // Process...
}
```

### Adding New Generic Response Type

**If new paginated response**:
```java
// Use generic PagedResponse<T> instead of creating new class:
PagedResponse<NewEntityResponse> page = new PagedResponse<>(
    entities, page, size, total, totalPages
);
```

**No need to modify Platform Common!**

---

## Build & Distribution

### Maven Dependency

**pom.xml** (in platform-common):
```xml
<groupId>com.rohit8020</groupId>
<artifactId>platform-common</artifactId>
<version>1.0.0</version>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

**Usage in other services**:
```xml
<dependency>
    <groupId>com.rohit8020</groupId>
    <artifactId>platform-common</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Build & Install**:
```bash
cd platform-common
mvn clean install  # Installs to local ~/.m2 repository

# Or to remote Maven repository:
mvn deploy
```

---

## Key Design Principles

| Principle | Implementation | Benefit |
|-----------|----------------|---------|
| **Single Source of Truth** | Shared library for all models | No duplicate code |
| **Type Safety** | Records, enums, generics | Compile-time error detection |
| **Immutability** | Records are final | Thread-safe, no accidental mutations |
| **Extensibility** | Generic types, enums | Easy to add new types |
| **Versioning** | v1 in topic names | Safe breaking changes |
| **Serialization** | Jackson support | Automatic JSON conversion |

---

## Summary

**Platform Common** provides:
1. **Shared Domain Models**: DomainEvent, Payloads, PagedResponse
2. **Type Safety**: Enums, Records ensuring correctness
3. **Consistency**: Single event schema across all services
4. **Extensibility**: Generic types for future growth
5. **Reusability**: No duplication across services

It's the critical foundation enabling loose coupling while maintaining type safety and consistency across the microservices ecosystem.
