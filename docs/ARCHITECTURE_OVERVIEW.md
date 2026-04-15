# Microservices Architecture Overview - Complete Guide

## Table of Contents
1. [System Architecture](#system-architecture)
2. [Service Interactions](#service-interactions)
3. [Data Flow Examples](#data-flow-examples)
4. [Technology Stack](#technology-stack)
5. [Communication Patterns](#communication-patterns)
6. [Deployment Architecture](#deployment-architecture)

---

## System Architecture

### High-Level Service Topology

```
                        ┌─────────────────────────────────┐
                        │      Client Applications        │
                        │  (Web, Mobile, Third-party)     │
                        └────────────────┬─────────────────┘
                                         │
                        ┌────────────────▼──────────────────┐
                        │      API GATEWAY (Port 8080)      │
                        │  ● Request Routing                │
                        │  ● Rate Limiting (Redis)          │
                        │  ● JWT Validation                 │
                        │  ● Identity Propagation           │
                        └────┬─────────────┬────────────────┘
                             │             │
           ┌─────────────────┼─────┬───────┼──────────────────┐
           │                 │     │       │                  │
    ┌──────▼─────┐  ┌────────▼──┐  │  ┌────▼──────┐  ┌───────▼──────┐
    │   AUTH     │  │  POLICY   │  │  │  CLAIMS   │  │   PLATFORM   │
    │  SERVICE   │  │  SERVICE  │  │  │  SERVICE  │  │   COMMON     │
    │ (Port 8081)│  │(Port 8082)│  │  │(Port 8083)│  │  (Library)   │
    │            │  │           │  │  │           │  │              │
    │ MySQL      │  │PostgreSQL │  │  │PostgreSQL │  │ Shared Models│
    │            │  │           │  │  │  + Redis  │  │              │
    └────────────┘  └───────────┘  │  └───────────┘  └──────────────┘
                                    │
                        ┌───────────┴──────────────┐
                        │  Kafka Message Broker    │
                        └───────────┬──────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
        ┌───────────▼─────────────┐   ┌────────────▼──────────┐
        │  policy-events.v1 Topic │   │ claim-events.v1 Topic │
        │                         │   │                       │
        │  Messages:              │   │  Messages:            │
        │  • POLICY_ISSUED        │   │  • CLAIM_SUBMITTED    │
        │  • POLICY_RENEWED       │   │  • CLAIM_VERIFIED     │
        │  • POLICY_LAPSED        │   │  • CLAIM_APPROVED     │
        │                         │   │  • CLAIM_REJECTED     │
        └─────────────────────────┘   └───────────────────────┘
```

### Service Responsibilities

| Service | Port | Database | Role |
|---------|------|----------|------|
| **Auth** | 8081 | MySQL | User authentication, JWT tokens, OAuth2 |
| **Policy** | 8082 | PostgreSQL | Policy lifecycle, event publishing |
| **Claims** | 8083 | PostgreSQL + Redis | Claim management, policy tracking via events |
| **API Gateway** | 8080 | None | Routing, rate limit, auth validation |
| **Platform Common** | - | - | Shared models, events (Maven library) |

---

## Service Interactions

### 1. Authentication Flow

```
User Login Request
    ↓
API Gateway (public endpoint, no JWT required)
    ↓
Auth Service
    ├─ Find user by username
    ├─ Validate password (BCrypt)
    ├─ Generate JWT (RS256 signed)
    ├─ Generate refresh token (hashed)
    ├─ Save refresh token
    └─ Return tokens
    ↓
Client receives: {accessToken, refreshToken}
    ↓
Client stores tokens locally
    ├─ Access token in memory or localStorage
    └─ Refresh token in httpOnly cookie (secure)
```

**Why Two Tokens**:
```
Access Token (1 hour):
  ├─ Sent with every request
  ├─ Short-lived (limits damage if stolen)
  └─ JWT (stateless)

Refresh Token (7 days):
  ├─ Stored server-side (stateful)
  ├─ Never sent to backend except for refresh
  ├─ Hashed in database
  └─ Can be revoked on logout
```

### 2. Policy Management with Event Publishing

```
Admin Issues New Policy
    ↓
API Gateway: validates JWT, forwards to Policy Service
    ↓
Policy Service (/api/policies POST)
    ├─ Extract authenticated actor from X-User-Id header
    ├─ Check idempotency key (prevent duplicates)
    ├─ Validate authorization (ADMIN only)
    ├─ Create Policy entity
    ├─ Create OutboxEvent with event data
    └─ Save both in single transaction
    ↓
PolicyOutboxPublisher (scheduled job, runs every 1 second)
    ├─ Query OutboxEvent table for PENDING events
    ├─ Convert to DomainEvent
    ├─ Publish to Kafka (policy-events.v1 topic)
    ├─ Mark OutboxEvent as PUBLISHED
    └─ Clean up old events
    ↓
Claims Service (Kafka consumer, topic: policy-events.v1)
    ├─ Receive POLICY_ISSUED event
    ├─ Extract PolicyEventPayload
    ├─ Create PolicyProjection in local table
    ├─ Cache in Redis (15 mins)
    └─ Acknowledge message
    ↓
Now Claims Service can immediately validate claims without calling Policy Service
```

**Why Transactional Outbox Pattern**:
```
Without outbox (naive approach):
  1. Save policy to DB
  2. Publish to Kafka
  
  Problem: If Kafka fails or process crashes:
    ├─ Policy saved but event not published
    ├─ Claims Service never learns about policy
    └─ Data consistency broken

With outbox (reliable):
  1. Save policy + OutboxEvent in ONE transaction
  2. Scheduled publisher handles async publishing
  
  Benefits:
    ✓ Atomicity: Policy and event always created together
    ✓ Reliability: Even if publisher crashes, event still in DB
    ✓ Retry: Publisher can retry indefinitely
    ✓ Guaranteed delivery (exactly-once semantics possible)
```

### 3. Claim Submission with Multi-Service Validation

```
Policyholder Submits Claim
    ↓
API Gateway
    ├─ Validate JWT token
    ├─ Extract X-User-Id (policyholder's ID)
    └─ Forward to Claims Service
    ↓
Claims Service (/api/claims POST)
    ├─ Extract actor from headers
    ├─ Verify actor is POLICYHOLDER
    ├─ Hash request (for idempotency)
    ├─ Check cache: If duplicate, return cached response
    ├─ Fetch policy: Try multiple sources
    │  ├─ Redis cache first (fast, 15-min TTL)
    │  ├─ If miss: REST call to Policy Service
    │  │  └─ With circuit breaker (fail-safe)
    │  └─ If circuit open: Use old cache (graceful degradation)
    ├─ Validate claim:
    │  ├─ Policyholder owns policy
    │  ├─ Policy status is ISSUED or RENEWED
    │  └─ Claim amount reasonable
    ├─ Create ClaimRecord
    ├─ Create OutboxEvent
    ├─ Save idempotency record
    └─ Return ClaimResponse
    ↓
ClaimsOutboxPublisher
    ├─ Publish CLAIM_SUBMITTED to Kafka
    └─ Other services can listen (audit logging, etc)
```

**Resilience in Action**:
```
Scenario 1: Policy Service normal
  1s: Claim submitted
  2s: Claims Service queries Policy Service
  3s: Validation complete
  5s: ClaimResponse sent to client

Scenario 2: Policy Service slow (5 second response time)
  1s: Claim submitted
  10s: Finally get response from Policy Service
  11s: Validation complete
  But wait - client might timeout!

Solution: Redis Cache + Circuit Breaker
  1s: Claim submitted
  2s: Redis HIT (15-min cache)
  3s: Validation complete (never called Policy Service!)
  5s: ClaimResponse sent to client

Scenario 3: Policy Service down (completely unavailable)
  Without cache:
    1s: Claim submitted
    5s: Timeout trying to reach Policy Service
    6s: 503 Service Unavailable error to client
  
  With cache + circuit breaker:
    1s: Claim submitted
    2s: Redis miss
    3s: Circuit breaker detects: OPEN
    4s: Fallback: Use old cache (5 mins old, fine for this use case)
    5s: Validation complete
    6s: ClaimResponse sent to client ✓ (Still works!)
```

---

## Data Flow Examples

### Example 1: Complete User Journey - Issue & Claim

```
Step 1: Admin Creates Agent User
  POST /api/admin/users
  Auth Service creates user with AGENT role
  
Step 2: Admin Logs In
  POST /api/auth/login
  Gets JWT + refresh token
  Sets Authorization: Bearer {JWT}

Step 3: Admin Issues Policy to Policyholder
  POST /api/policies
  ├─ Policy Service creates policy
  ├─ OutboxEvent created: POLICY_ISSUED
  └─ Response: {id: "pol-123", status: "ISSUED"}

Step 4: BackgroundJob Publishes Event
  PolicyOutboxPublisher
  ├─ Finds POLICY_ISSUED in outbox
  ├─ Publishes to Kafka
  └─ Marks PUBLISHED

Step 5: Claims Service Receives Event
  Claims Service Kafka listener
  ├─ Creates PolicyProjection
  ├─ Caches in Redis
  └─ Ready to accept claims

Step 6: Policyholder Logs In
  POST /api/auth/login
  Gets JWT with role: POLICYHOLDER

Step 7: Policyholder Submits Claim
  POST /api/claims
  Idempotency-Key: uuid-4
  ├─ Claims Service gets policy from Redis cache
  ├─ Validates: Policyholder owns policy ✓
  ├─ Validates: Policy not lapsed ✓
  ├─ Creates ClaimRecord (SUBMITTED)
  ├─ Creates OutboxEvent
  └─ Response: {id: "claim-123", status: "SUBMITTED"}

Step 8: Agent Verifies Claim
  PATCH /api/claims/claim-123/verify
  ├─ Claims Service loads claim record
  ├─ Check: Agent assigned to claim ✓
  ├─ Updates status to VERIFIED
  ├─ Creates OutboxEvent
  └─ Response: {id: "claim-123", status: "VERIFIED"}

Step 9: Agent Approves Claim
  PATCH /api/claims/claim-123/approve
  ├─ Updates status to APPROVED
  ├─ Creates OutboxEvent
  └─ Payment processing begins (external system)
```

### Example 2: Graceful Handling of Policy Service Failure

```
Normal Scenario (Policy Service UP):
  Claims Service needs to validate policy
    ├─ Tries Redis cache
    ├─ Cache miss (new policy)
    ├─ REST call to Policy Service: 5ms response
    ├─ Cache result in Redis
    └─ Continue processing

Policy Service Slow (>2 seconds):
  Circuit Breaker Configuration:
    ├─ Failure rate threshold: >50% failures
    ├─ Wait duration: 30 seconds
    └─ Permitted calls in half-open: 1
  
  Requests 1-10: Succeed (< 2s each)
  Requests 11-15: Timeout (> 2s) → Marked as failed
  Failure rate: 5 failures / 10 total = 50%
    ├─ Circuit breaker opens
    ├─ No more requests sent to Policy Service
    └─ Use fallback (cached response)
  
  Client: All requests still succeed (using old cache)

Policy Service Recovers:
  After 30 seconds:
    ├─ Circuit breaker enters HALF_OPEN
    ├─ Allow 1 test request
    ├─ If succeeds: Return to CLOSED
    ├─ If fails: Return to OPEN, wait another 30s

Benefit:
  ✓ Policy Service can recover without being hammered
  ✓ Claims Service keeps serving (with slightly stale data)
  ✓ No cascading failure
```

---

## Technology Stack

### Backend Framework
| Component | Technology | Why |
|-----------|-----------|-----|
| Web Framework | Spring Boot | Industry standard, excellent ecosystem |
| Reactive Processing | Spring WebFlux | Non-blocking I/O, high throughput |
| Gateway | Spring Cloud Gateway | Out-of-box routing, filters, load balancing |
| Security | Spring Security OAuth2 | JWT validation, role-based access |
| ORM | Hibernate JPA | Type-safe queries, automatic schema |
| Event Publishing | Kafka | Distributed, scalable, durable messaging |

### Database & Caching
| Component | Technology | Why |
|-----------|-----------|-----|
| Primary DB (Auth) | MySQL | Transactional, ACID, reliable |
| Primary DB (Policy) | PostgreSQL | Advanced features, reliability |
| Primary DB (Claims) | PostgreSQL | Consistency guarantees |
| Cache | Redis | Fast in-memory, TTL support, pub/sub |

### Security
| Component | Technology | Why |
|-----------|-----------|-----|
| Password Hashing | BCrypt | Slow (resistant to brute-force), salted |
| JWT Signing | RS256 (RSA) | Asymmetric: Auth signs, others verify |
| Secret Management | Environment Variables | Simple, secure, standard |
| Rate Limiting | Redis + Algorithm | Atomic operations, distributed |

### Messaging & Integration
| Component | Technology | Why |
|-----------|-----------|-----|
| Async Messaging | Kafka | Guaranteed delivery, scalable, durable |
| Service-to-Service | REST + HTTP | Synchronous for critical operations |
| Circuit Breaker | Resilience4j | Detects failures, prevents cascades |
| Observability | Prometheus + Grafana | Metrics collection and visualization |

---

## Communication Patterns

### 1. Synchronous (Request-Response)

**Used For**: User-facing operations that need immediate response

**Examples**:
- User login → immediate token
- Submit claim → claim ID returned
- Get policy details → policy returned

**Implementation**:
```
Client → API Gateway → Service
         └─ Waits for response
         
Service processes → Returns JSON response
         
Client receives response
```

**Timeout Strategy**:
```
Default timeout: 30 seconds
Too long? User might close app
Too short? Policy Service might be legitimately slow

Solution: Use circuit breaker
  ├─ Timeout: 2 seconds
  ├─ If too slow: Open circuit
  └─ Return cached response
```

### 2. Asynchronous (Event-Driven)

**Used For**: Non-critical updates, background processing

**Examples**:
- Policy issued → Claims Service learns about policy
- Claim created → External audit system subscribes
- Policy lapsed → Multiple services need to know

**Implementation**:
```
Service A: Create entity + OutboxEvent (transactional)
         │
         └─→ Background Job publishes to Kafka
                    │
                    └─→ Kafka Topic
                           │
                           ├─→ Service B consumes
                           ├─→ Service C consumes
                           └─→ Service D consumes (all async)

Each service processes at own pace
No blocking, high throughput
```

**Guarantee Levels**:
```
At-Least-Once Guarantee:
  ├─ OutboxEvent saved in DB
  ├─ Always publishes (even if retry)
  └─ Subscriber must handle duplicates (idempotency key)

Exactly-Once is harder:
  ├─ Requires distributed transactions
  ├─ Performance hit
  ├─ Usually not worth it
  └─ At-least-once + idempotency = same result
```

### 3. Cross-Service Calls

**API Calls Between Services**:
```
Claims Service → Policy Service (internal, same network)
  ├─ Not authenticated with JWT (internal network)
  ├─ Uses circuit breaker (resilience)
  ├─ Has timeout (2 seconds)
  └─ Falls back to cache

If Policy Service down:
  ├─ Circuit breaker prevents thundering herd
  ├─ Claims Service uses old cache
  └─ Continues serving (degraded but functional)
```

**Why OAuth2 for P2P**:
```
Without Auth:
  Any service can call any other
  No audit trail
  No permission control

With OAuth2 (client credentials):
  Claims Service requests token from Auth Service
  Token has scope: "policy.read"
  Policy Service validates scope
  Only reads allowed, not writes
  Audit log of which service called when
```

---

## Deployment Architecture

### Container Orchestration

```
Kubernetes Cluster
│
├─── Namespace: default
│    │
│    ├─ Pod: auth-service-deployment
│    │  ├─ Container: auth-service
│    │  └─ Service: auth-service (port 8081)
│    │
│    ├─ Pod: policy-service-deployment
│    │  ├─ Container: policy-service
│    │  └─ Service: policy-service (port 8082)
│    │
│    ├─ Pod: claims-service-deployment
│    │  ├─ Container: claims-service
│    │  └─ Service: claims-service (port 8083)
│    │
│    ├─ Pod: api-gateway-deployment
│    │  ├─ Container: api-gateway
│    │  └─ Service: api-gateway (port 8080, external)
│    │
│    ├─ StatefulSet: kafka
│    │  └─ broker-0, broker-1, broker-2
│    │
│    ├─ StatefulSet: redis
│    │  └─ master-0
│    │
│    ├─ Deployment: mysql
│    │  └─ PersistentVolume: mysql-data
│    │
│    ├─ Deployment: postgres
│    │  └─ PersistentVolume: postgres-data
│    │
│    └─ Ingress: platform-ingress
│       └─ Routes: *.example.com → api-gateway:8080
│
└─ ConfigMap & Secrets
   ├─ ConfigMap: db-config
   ├─ Secret: db-credentials
   ├─ Secret: jwt-keys
   └─ Secret: oauth-clients
```

### Database Deployment

**MySQL (Auth Service)**:
```yaml
StatefulSet: mysql
  Replicas: 1
  PersistentVolume: 10Gi
  Data:
    ├─ users
    └─ refresh_tokens
```

**PostgreSQL (Policy & Claims)**:
```yaml
StatefulSet: postgres
  Replicas: 1
  PersistentVolume: 50Gi
  Data:
    ├─ Policy Service:
    │  ├─ policies
    │  ├─ outbox_events
    │  └─ idempotency_records
    │
    └─ Claims Service:
       ├─ claim_records
       ├─ policy_projections
       ├─ outbox_events
       └─ idempotency_records
```

### Networking

```
Internet
    │
    └─→ Ingress Controller
           │
           └─→ Load Balancer (HTTPS)
                  │
                  └─→ API Gateway Pod (multiple replicas)
                       │
                       ├─→ Auth Service (internal)
                       ├─→ Policy Service (internal)
                       └─→ Claims Service (internal)
                            │
                            ├─→ Redis (in-cluster)
                            ├─→ PostgreSQL (in-cluster)
                            └─→ Kafka (in-cluster)

Internal network: pod-to-pod communication (no internet exposure)
External network: Only API Gateway exposed
```

### High Availability

```
API Gateway:
  ├─ Replicas: 3 (load balanced)
  ├─ Reason: Entry point, high traffic
  └─ Rolling update: Always 2 available

Auth Service:
  ├─ Replicas: 2
  ├─ Reason: Critical, stateless
  └─ Rolling update: Always 1 available

Policy Service:
  ├─ Replicas: 2
  ├─ Reason: Stateless, events can retry
  └─ Rolling update: Always 1 available

Claims Service:
  ├─ Replicas: 2
  ├─ Reason: Stateless, caching helps
  └─ Rolling update: Always 1 available

Kafka:
  ├─ Replicas: 3 (Zookeeper ensemble)
  ├─ Reason: Distributed, needs quorum
  └─ Data durability: Replicated across brokers

Redis:
  ├─ Replicas: 1 (single master)
  ├─ Reason: Cache only, if lost not critical
  └─ Optional: Sentinel for auto-failover

Databases:
  ├─ MySQL: Replicas 1 (could add Galera for HA)
  ├─ PostgreSQL: Replicas 1 (could add streaming replication)
  └─ PersistentVolume with auto-backup
```

### Health Checks

```
Kubernetes Liveness Probe (is pod alive?):
  GET /actuator/health
  → 200 OK: Pod is healthy
  → 500 Error: Kill pod, restart

Readiness Probe (is pod ready for traffic?):
  GET /actuator/health/readiness
  → 200 OK: Ready, send traffic
  → 503 Service Unavailable: Not ready yet, queue traffic

Startup Probe (has pod started?):
  GET /actuator/health/liveness
  → 200 OK: Pod started, check liveness
  → Timeout: Give pod more time to start

Rolling Update:
  1. New pod starts
  2. Startup probe: Is it ready? (Wait if not)
  3. Readiness probe: OK, route traffic
  4. Old pod: No new requests
  5. Drain existing connections (graceful shutdown)
  6. Kill old pod
  7. Repeat for next replica
  
Result: Zero downtime deployment
```

---

## Summary

This microservices architecture provides:

1. **Scalability**: Services scale independently, async events prevent bottlenecks
2. **Resilience**: Circuit breakers, caching, fallbacks prevent cascade failures
3. **Security**: JWT tokens, OAuth2 M2M, rate limiting, encrypted passwords
4. **Consistency**: Transactional outbox ensures reliable event publishing
5. **Observability**: Prometheus metrics, Grafana dashboards, structured logging
6. **Deployability**: Containerized, Kubernetes-native, stateless services

Each service is a single responsibility unit that can be:
- Deployed independently
- Scaled horizontally
- Updated with zero downtime
- Monitored for health and performance
- Tested in isolation

The event-driven architecture allows services to evolve independently while maintaining system coherence through asynchronous messaging.
