# Complete Microservices Documentation Index

## 📚 Documentation Overview

This directory contains complete, detailed documentation for every service in the microservices architecture. Each document covers functionality, logic, code details, and design reasoning.

---

## 📖 Service Documentation

### 1. **[Architecture Overview](ARCHITECTURE_OVERVIEW.md)** 📋
**Start here for system-wide understanding**
- High-level service topology
- Service interactions & communication patterns
- Complete user journey examples
- Data flow scenarios
- Deployment architecture
- High availability setup

**Best for**: Understanding how all services work together

---

### 2. **[API Gateway Service](API_GATEWAY_SERVICE.md)** 🚪
**Request entry point and routing**
- Request routing mechanism
- Redis-based rate limiting algorithm
- JWT validation flow
- Identity header propagation
- Filter chain execution order
- Security configuration

**Key Components**:
- `RedisRateLimitFilter`: Per-IP and per-user rate limiting
- `IdentityHeaderFilter`: JWT claim extraction
- `SecurityConfig`: OAuth2 resource server configuration

**Key Endpoints**:
- `POST /api/auth/**` → Auth Service
- `GET/PATCH /api/policies/**` → Policy Service
- `GET/POST /api/claims/**` → Claims Service

**Best for**: Understanding request flow, rate limiting, authentication

---

### 3. **[Auth Service](AUTH_SERVICE.md)** 🔐
**User authentication & security**
- User registration and login flow
- JWT token generation (RS256)
- Refresh token lifecycle
- Password hashing (BCrypt)
- Machine-to-machine authentication (OAuth2)
- Constant-time comparison (preventing timing attacks)

**Key Components**:
- `AuthService`: Business logic (login, register, refresh)
- `JwtUtil`: JWT creation and validation
- `AuthController`: User endpoints
- `OAuthController`: M2M token endpoint

**Key Entities**:
- `User`: Username, password, role (ADMIN/AGENT/POLICYHOLDER)
- `RefreshToken`: Hashed tokens with revocation tracking

**Key Endpoints**:
- `POST /api/auth/register` - User registration
- `POST /api/auth/login` - User login
- `POST /api/auth/refresh` - Token refresh
- `POST /api/auth/oauth2/token` - M2M authentication

**Best for**: Understanding authentication, security, cryptography

---

### 4. **[Policy Service](POLICY_SERVICE.md)** 📋
**Insurance policy lifecycle management**
- Policy issuance with idempotency
- Policy renewal and lapsing
- Event publishing via transactional outbox pattern
- Role-based authorization
- Pagination and filtering

**Key Components**:
- `PolicyService`: Business logic
- `PolicyController`: Public endpoints
- `InternalPolicyController`: Internal service endpoints
- `PolicyOutboxPublisher`: Kafka event publishing

**Key Entities**:
- `Policy`: Policy data, status (ISSUED/RENEWED/LAPSED)
- `OutboxEvent`: Reliable event storage for publishing
- `IdempotencyRecord`: Caching for safe retries

**Key Workflow**:
1. Issue/renew/lapse policy
2. Save policy + OutboxEvent in transaction
3. Background job publishes OutboxEvent to Kafka
4. Other services consume events

**Key Endpoints**:
- `POST /api/policies` - Issue policy
- `PATCH /api/policies/{id}/renew` - Renew policy
- `PATCH /api/policies/{id}/lapse` - Lapse policy
- `GET /api/policies` - List policies

**Best for**: Event-driven architecture, idempotency, transactional patterns

---

### 5. **[Claims Service](CLAIMS_SERVICE.md)** 📝
**Insurance claim submission & workflow**
- Claim submission with idempotency
- Claim verification, approval, rejection
- Policy data caching (Redis) with circuit breaker
- Kafka listener for policy events
- Service-to-service resilience patterns

**Key Components**:
- `ClaimsService`: Business logic
- `ClaimController`: Public endpoints
- `PolicyProjectionClient`: REST call to Policy Service (circuit breaker)
- `PolicyProjectionCache`: Redis caching
- `PolicyEventConsumer`: Kafka listener

**Key Entities**:
- `ClaimRecord`: Claim data, status (SUBMITTED/VERIFIED/APPROVED/REJECTED)
- `PolicyProjection`: Local cache of policy data
- `OutboxEvent`: Event storage for publishing
- `IdempotencyRecord`: Safe retries

**Resilience Features**:
- Redis cache (15-min TTL)
- Circuit breaker (fail after 50% errors)
- Fallback to old cache if service down
- Graceful degradation

**Key Endpoints**:
- `POST /api/claims` - Submit claim
- `PATCH /api/claims/{id}/verify` - Verify claim
- `PATCH /api/claims/{id}/approve` - Approve claim
- `PATCH /api/claims/{id}/reject` - Reject claim
- `GET /api/claims` - List claims

**Best for**: Resilience patterns, caching strategies, circuit breakers

---

### 6. **[Platform Common](PLATFORM_COMMON.md)** 🎯
**Shared domain models & utilities (Maven library)**
- Domain event models
- Event payloads (Policy, Claim)
- Generic pagination response
- Event versioning strategy

**Key Models**:
- `DomainEvent`: Event envelope (aggregateId, type, payload, timestamp)
- `AggregateType`: Enum (POLICY, CLAIM)
- `PolicyEventPayload`: Event data for policy events
- `ClaimEventPayload`: Event data for claim events
- `PagedResponse<T>`: Generic pagination

**Why Shared**:
- Single source of truth
- Type-safe domain models
- Consistent serialization
- Prevents duplicate code

**Usage**:
```java
// Publishing
DomainEvent event = new DomainEvent(
    policyId,
    AggregateType.POLICY,
    "POLICY_ISSUED",
    objectMapper.valueToTree(payload),
    Instant.now()
);

// Consuming
@KafkaListener(topics = "policy-events.v1")
public void handleEvent(DomainEvent event) {
    PolicyEventPayload payload = objectMapper.convertValue(
        event.payload(), PolicyEventPayload.class);
}
```

**Best for**: Understanding shared models, domain-driven design

---

## 🔗 Service Interactions

```
User Request
    ↓
API Gateway (8080)
    ├─ Rate limit check
    ├─ JWT validation
    └─ Identity propagation (X-User-Id header)
    ↓
Service Handling
├─ Auth Service (8081) → MySQL
├─ Policy Service (8082) → PostgreSQL → OutboxEvent → Kafka
├─ Claims Service (8083) → PostgreSQL + Redis cache
│                          ↓ Kafka listener
│                          Updates PolicyProjection
└─ All via Platform Common (Shared Models)
```

---

## 🎯 Quick Navigation by Topic

### Authentication & Security
- See: [Auth Service](AUTH_SERVICE.md)
- Topics: JWT, BCrypt, RS256, refresh tokens, OAuth2, timing attacks

### Request Flow & Rate Limiting
- See: [Architecture Overview](ARCHITECTURE_OVERVIEW.md#communication-patterns), [API Gateway](API_GATEWAY_SERVICE.md)
- Topics: Rate limiting algorithm, circuit breaker, request routing

### Event-Driven Architecture
- See: [Policy Service](POLICY_SERVICE.md#event-publishing), [Claims Service](CLAIMS_SERVICE.md#event-processing)
- Topics: Kafka, transactional outbox, event publishing, event consumption

### Resilience & Failure Handling
- See: [Claims Service - Resilience Patterns](CLAIMS_SERVICE.md#resilience-patterns), [Architecture Overview - Graceful Degradation](ARCHITECTURE_OVERVIEW.md#example-2-graceful-handling-of-policy-service-failure)
- Topics: Circuit breaker, retries, caching, fallbacks

### Database & Data Models
- See: Individual service docs (Data Model sections)
- Topics: Entity design, relationships, indexes, transactional boundaries

### API Endpoint Reference
- See: Each service doc's "API Endpoints" section
- All endpoints include: request format, response format, error codes

### Authorization & Role-Based Access
- See: [Auth Service](AUTH_SERVICE.md), [Policy Service](POLICY_SERVICE.md#authorization), [Claims Service](CLAIMS_SERVICE.md#code-logic-explanation)
- Topics: JWT claims, role hierarchy, authorization checks

### Performance & Optimization
- See: [Claims Service - Caching](CLAIMS_SERVICE.md#2-caching-strategy), [Architecture Overview - Technology Stack](ARCHITECTURE_OVERVIEW.md#technology-stack)
- Topics: Redis caching, database indexes, async processing

---

## 📊 Database Schema Overview

### MySQL (Auth Service)
```
users
├─ id (UUID)
├─ username (unique)
├─ password (BCrypt hash)
└─ role (ADMIN/AGENT/POLICYHOLDER)

refresh_tokens
├─ id (UUID)
├─ token_hash (SHA-256)
├─ user_id (FK)
├─ revoked (boolean)
└─ expires_at
```

### PostgreSQL - Policy Service
```
policies
├─ id (UUID)
├─ policy_number (unique)
├─ policyholder_id
├─ assigned_agent_id
├─ status (ISSUED/RENEWED/LAPSED)
├─ premium
├─ start_date
└─ end_date

outbox_events
├─ id (UUID)
├─ aggregate_id
├─ event_type
├─ payload (JSON)
├─ status (PENDING/PUBLISHED)
└─ created_at

idempotency_records
├─ id (UUID)
├─ operation
├─ actor_id
├─ idempotency_key
├─ request_hash
└─ response_body
```

### PostgreSQL - Claims Service
```
claim_records
├─ id (UUID)
├─ policy_id
├─ policyholder_id
├─ status (SUBMITTED/VERIFIED/APPROVED/REJECTED)
├─ claim_amount
└─ description

policy_projections
├─ id (UUID)
├─ policy_id (from Kafka events)
├─ policyholder_id
├─ assigned_agent_id
├─ status
└─ claimable (boolean)

outbox_events & idempotency_records
(same as Policy Service)
```

### Redis (Cache)
```
policy:{policyId} → PolicyProjectionResponse (15-min TTL)
token:claims-service → JWT token (23-hour TTL)
rate:login:{IP} → counter (1-min TTL)
rate:claims:{userId} → counter (1-min TTL)
```

---

## 🛠️ Key Architectural Patterns

| Pattern | Used In | Purpose |
|---------|---------|---------|
| **JWT (RS256)** | All services | Stateless auth, asymmetric signing |
| **BCrypt** | Auth Service | Slow hashing, password security |
| **Transactional Outbox** | Policy, Claims | Reliable event publishing |
| **Saga Pattern** | Policy & Claims | Distributed transactions |
| **Circuit Breaker** | Claims → Policy | Failure detection, graceful degradation |
| **Redis Cache** | Claims Service | Performance, resilience |
| **Idempotency Keys** | Policy, Claims | Safe retries |
| **Role-Based Access Control** | All endpoints | Authorization |
| **Event Sourcing** | Kafka topics | Audit trail, eventual consistency |

---

## 🚀 Getting Started with Documentation

1. **First-time learners**: Start with [Architecture Overview](ARCHITECTURE_OVERVIEW.md)
2. **Security concerns**: Go to [Auth Service](AUTH_SERVICE.md)
3. **Adding features**: See relevant service doc + [Platform Common](PLATFORM_COMMON.md)
4. **Debugging issues**: Check specific service doc and code logic sections
5. **Production deployment**: See [Architecture Overview - Deployment](ARCHITECTURE_OVERVIEW.md#deployment-architecture)

---

## 📝 Documentation Maintenance

This documentation is comprehensive and covers:
- **Functionality**: What each service does
- **Logic**: Why decisions were made
- **Code Details**: How to navigate the codebase
- **Reasoning**: The "why" behind each design choice

**Last Updated**: April 2024  
**Architecture Version**: 1.0  
**Services**: 5 (Auth, Policy, Claims, Gateway, Common)

---

## ✅ What's Documented

- ✓ Complete API endpoints for all services
- ✓ Database schema and entity relationships
- ✓ Authentication & security mechanisms
- ✓ Event-driven architecture patterns
- ✓ Resilience patterns (circuit breaker, caching, fallbacks)
- ✓ Code logic with detailed explanations
- ✓ Service interactions and data flows
- ✓ Deployment architecture
- ✓ Technology stack rationale

---

For detailed information on any specific topic, refer to the corresponding service documentation above.
