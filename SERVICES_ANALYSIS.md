# Services Architecture Analysis

## Overview
This microservices architecture consists of 5 main services that form an insurance management platform. The services use an event-driven architecture with Kafka for inter-service communication, implement JWT-based OAuth2 security, and employ the transactional outbox pattern for reliable event publishing.

---

## 1. API Gateway Service (`api-gateway`)

### Purpose
Central entry point for all API requests, handling authentication, authorization, rate limiting, and routing to backend services.

### Main Source Files
- **ApiGatewayApplication.java**: Main Spring Boot application entry point
- **SecurityConfig.java**: JWT security configuration for OAuth2 resource server
- **IdentityHeaderFilter.java**: Extracts JWT claims and adds user identity headers
- **RedisRateLimitFilter.java**: Rate limiting with Redis backend

### Key Classes & Components

#### Controllers
None exposed at gateway level - all requests are routed through predicates

#### Filters
1. **IdentityHeaderFilter** (Order: -10)
   - Extracts user information from JWT token
   - Injects headers: `X-User-Id`, `X-Username`, `X-User-Role`
   - Executed after authentication

2. **RedisRateLimitFilter** (Order: -20)
   - Implements token bucket rate limiting using Redis
   - Login endpoint: `${app.rate-limit.login-per-minute}` (configurable)
   - Claim submission: `${app.rate-limit.claim-submit-per-minute}` (configurable)
   - Returns HTTP 429 when limit exceeded

#### Configuration Files
- **application.yaml**: Route definitions, Redis config, JWT JWK Set URI

### Configuration & Routes
```yaml
Gateway Routes:
1. /api/auth/** → http://localhost:8081/auth/**
2. /api/policies/** → http://localhost:8082/policies/**
3. /api/claims/** → http://localhost:8083/claims/**
```

### Database
- No database (stateless gateway)

### Dependencies
- Spring Cloud Gateway Server WebFlux (reactive)
- Spring Security OAuth2 Resource Server
- Micrometer Prometheus (metrics)
- Spring Cloud tracing with Brave/Zipkin

### Security Features
- JWT validation via JWK Set URI
- Role-based access control (ROLE_ADMIN, ROLE_AGENT, ROLE_POLICYHOLDER)
- Scope-based authorization (SCOPE_*)
- Reactive security with ServerHttpSecurity

### Main Functionality
- **Route Management**: Requests routed based on path patterns
- **Rate Limiting**: Per-minute rate limits on login and claim submission
- **Identity Propagation**: User context injected into downstream services
- **Health Monitoring**: Actuator endpoints for Prometheus metrics

---

## 2. Auth Service (`auth-service`)

### Purpose
Handles user authentication, JWT token generation, refresh tokens, and OAuth2 machine-to-machine authentication.

### Main Source Files
- **AuthServiceApplication.java**: Main Spring Boot application
- **JwtUtil.java**: JWT token generation and validation
- **AuthService.java**: Core authentication business logic
- **AuthController.java**: User authentication endpoints
- **OAuthController.java**: OAuth2 token endpoints
- **AdminUserController.java**: Admin user management

### Key Classes & Components

#### Entities
1. **User**
   - Fields: id, username, password, role (enum), createdAt
   - Roles: ADMIN, AGENT, POLICYHOLDER
   - MySQL table: `users`
   - Constraints: username is unique

2. **RefreshToken**
   - Fields: id, tokenHash, userId, expiresAt, revoked, createdAt
   - MySQL table: `refresh_tokens`
   - Foreign key to users(id)
   - Stores hash of token for security

3. **UserRole** (Enum)
   - ADMIN, AGENT, POLICYHOLDER

#### Controllers
1. **AuthController** (/auth)
   - `GET /health`: Service health check
   - `POST /register`: User registration (POLICYHOLDER role)
   - `POST /login`: User login with credentials
   - `POST /refresh`: Refresh access token
   - `GET /me`: Get current user profile

2. **OAuthController** (/oauth2)
   - `POST /oauth2/token`: Client credentials flow
   - `GET /oauth2/jwks`: Public JWKS endpoint
   - `GET /.well-known/jwks.json`: OpenID Connect compatible endpoint
   - Supports Basic Auth and form-encoded credentials

3. **AdminUserController** (/auth/admin)
   - Admin-only endpoints for user management
   - Create/read/update user operations

#### Services
**AuthService**
- `register(RegisterRequest)`: Create new POLICYHOLDER user
- `login(LoginRequest)`: Authenticate user
- `refresh(RefreshTokenRequest)`: Issue new access token
- `issueClientCredentialsToken(clientId, secret, scope)`: Machine-to-machine auth
- User creation with password hashing
- Idempotency key handling for registration

#### Repository
**UserRepository** (JpaRepository<User, Long>)
- `findByUsername(String)`: Lookup user by username
- `existsByUsername(String)`: Check username availability
- `countByRole(UserRole)`: Get role statistics

**RefreshTokenRepository**
- `findByTokenHash(String)`: Lookup refresh token

#### Security Utils
**JwtUtil**
- `generateToken(User)`: Create access token with claims
  - Claims: userId, role, token_use="user", iss, kid, iat, exp
  - Expiration: 1 hour (configurable)
- `generateClientToken(clientId, scope)`: Machine token
  - Claims: client_id, scope, token_use="client"
  - Expiration: 5 minutes (configurable)
- RSA-256 signature with configurable key pairs
- Token refresh extends expiration (24 hours by default)

### Configuration
- **Database**: MySQL
- **Port**: 8081
- **JWT Keys**: RSA key pair (configurable via environment)
- **Database Migrations**: Flyway
- **Bootstrap Admin**: Automatic admin user creation on startup

### Database Schema
```sql
users:
  - id (bigint, PK, auto-increment)
  - username (varchar, unique)
  - password (varchar)
  - role (varchar)
  - created_at (timestamp)

refresh_tokens:
  - id (bigint, PK)
  - token_hash (varchar, unique)
  - user_id (bigint, FK)
  - expires_at (timestamp)
  - revoked (boolean)
  - created_at (timestamp)
```

### Machine Client Configuration
Configured via `MachineClientProperties`:
- Claims Service: `claims-service` / `claims-service-secret`
  - Scopes: `policy.projection.read`

### Dependencies
- Spring Boot Data JPA & Hibernate
- Spring Security
- MySQL Connector
- JJWT (JWT library)
- Flyway (database migrations)
- Micrometer Prometheus & Brave/Zipkin tracing

### Main Functionality
- **User Registration**: New user onboarding
- **Authentication**: Login with username/password
- **Token Management**: Access and refresh token lifecycle
- **OAuth2 Authorization Server**: Issue machine-to-machine tokens
- **JWT Validation**: Sign and verify JWTs
- **Role-Based Access**: Three user roles with different permissions
- **Message Format Support**: Form-encoded and JSON for OAuth2 endpoints

---

## 3. Policy Service (`policy-service`)

### Purpose
Manages insurance policies lifecycle - creation, renewal, lapsing. Provides public API for policy management and internal projection endpoint for claims service.

### Main Source Files
- **PolicyServiceApplication.java**: Main application with scheduling enabled
- **PolicyService.java**: Core business logic
- **PolicyController.java**: Public API endpoints
- **InternalPolicyController.java**: Internal service-to-service API
- **Policy.java**: Domain entity

### Key Classes & Components

#### Entities
1. **Policy**
   - Fields: id (UUID), policyNumber, policyholderId, assignedAgentId, status, premium, startDate, endDate, createdAt, updatedAt
   - Status enum: ISSUED, ACTIVE, RENEWED, LAPSED
   - Precision fields: premium (12, 2)
   - PostgreSQL table: `policies` with 4 indexes
   - Pre-persist hooks set timestamps

2. **IdempotencyRecord**
   - Ensures idempotent operations
   - Fields: operation, actorId, idempotencyKey, requestHash, responseBody, resourceId, expiresAt
   - Unique constraint on (operation, actor_id, idempotency_key)
   - Automatic cleanup via expiration

3. **OutboxEvent**
   - Implementation of transactional outbox pattern
   - Fields: id, aggregateType, aggregateId, eventType, eventVersion, payload, status, retryCount, availableAt, createdAt, sentAt, lastError
   - Status enum: PENDING, IN_FLIGHT, SENT, FAILED
   - Retry mechanism with exponential backoff

4. **OutboxStatus** (Enum)
   - PENDING, IN_FLIGHT, SENT, FAILED

5. **PolicyStatus** (Enum)
   - ISSUED, ACTIVE, RENEWED, LAPSED

#### Controllers
1. **PolicyController** (/policies)
   - `POST /`: Issue new policy
   - `POST /{policyId}/renew`: Renew existing policy
   - `POST /{policyId}/lapse`: Lapse policy
   - `GET /{policyId}`: Get policy details
   - `GET /`: List policies (paginated, sortable)
   - All require Idempotency-Key header for mutation operations

2. **InternalPolicyController** (/internal/policies)
   - `GET /{policyId}/projection`: Get policy projection
   - Protected by OAuth2 scope: `policy.projection.read`
   - Used by claims service with client credentials

#### Services
**PolicyService**
- `issuePolicy()`: Create new policy with ISSUED status
- `renewPolicy()`: Extend policy dates
- `lapsePolicy()`: Deactivate policy
- `getPolicy()`: Retrieve policy by ID
- `listPolicies()`: Paginated policy listing
- `getProjection()`: Cached view for claims service
- Implements idempotency via hashing and deduplication
- Event publishing via outbox table

#### Repositories
1. **PolicyRepository** (JpaRepository<Policy, UUID>)
   - `findByPolicyNumber(String)`
   - `findAllByPolicyholderId(Long)`
   - `findAllByAssignedAgentId(Long)` (with pagination)

2. **OutboxEventRepository**
   - `claimNextBatch(int)`: Fetch pending events for publishing
   - Custom query for status and availability

3. **IdempotencyRecordRepository**
   - `findByOperationAndActorIdAndIdempotencyKey()`

#### Security
- OAuth2 resource server with JWT validation
- Role check for operations (AGENT/ADMIN can issue, not POLICYHOLDER)
- Scope validation for internal endpoints

### Configuration
- **Database**: PostgreSQL
- **Port**: 8082
- **Kafka**: Used for publishing events
- **Topics**: policy-events.v1, policy-events.DLQ
- **Outbox Publisher**: Scheduled task every 5 seconds
- **Batch Processing**: 25 events per batch

### Database Schema
```sql
policies:
  - id (uuid, PK)
  - policy_number (varchar, unique)
  - policyholder_id (bigint)
  - assigned_agent_id (bigint)
  - status (varchar)
  - premium (numeric 12,2)
  - start_date (date)
  - end_date (date)
  - created_at (timestamp)
  - updated_at (timestamp)
  - Indexes on: policyholder_id, assigned_agent_id, status, created_at

outbox_events:
  - id (varchar 64, PK)
  - aggregate_type (varchar)
  - aggregate_id (varchar 64)
  - event_type (varchar 64)
  - event_version (int)
  - payload (text)
  - status (varchar)
  - retry_count (int)
  - available_at (timestamp)
  - created_at (timestamp)
  - sent_at (timestamp)
  - last_error (text)
  - Indexes on: (status, available_at), aggregate_id

idempotency_keys:
  - Unique constraint on (operation, actor_id, idempotency_key)
  - index on expires_at for cleanup
```

### Dependencies
- Spring Boot Data JPA
- Spring Security OAuth2
- Spring Kafka
- PostgreSQL JDBC
- Micrometer Prometheus & Brave/Zipkin
- Flyway (migrations)
- Lombok

### Main Functionality
- **Policy Lifecycle**: ISSUED → ACTIVE → RENEWED/LAPSED
- **Event Publishing**: Uses transactional outbox pattern with Kafka
- **Idempotent Operations**: Ensure safe retries
- **Policy Projections**: Cached views for other services
- **Role-Based Operations**: Different capabilities for AGENT vs ADMIN vs POLICYHOLDER
- **Pagination & Sorting**: Support paginated listings
- **Retry Logic**: Failed events are retried with exponential backoff

---

## 4. Claims Service (`claims-service`)

### Purpose
Manages insurance claim submissions, verification, approval/rejection workflow. Integrates with policy service for claim eligibility validation. Consumes policy events via Kafka.

### Main Source Files
- **ClaimsServiceApplication.java**: Main application with scheduling
- **ClaimsService.java**: Core claim business logic
- **ClaimController.java**: REST API endpoints
- **ClaimRecord.java**: Claim entity
- **PolicyProjectionClient.java**: REST client to policy service
- **PolicyProjectionCache.java**: Redis cache for policy data
- **ClaimsOutboxPublisher.java**: Event publisher

### Key Classes & Components

#### Entities
1. **ClaimRecord**
   - Fields: id, policyId, policyholderId, assignedAgentId, description, claimAmount, status, decisionNotes, createdAt, updatedAt
   - Status enum: SUBMITTED, VERIFIED, APPROVED, REJECTED
   - PostgreSQL table: `claims` with 5 indexes
   - Pre-persist hooks manage timestamps

2. **PolicyProjection**
   - Cached copy of policy data for fast lookup
   - Fields: id, policyholderId, assignedAgentId, status, claimable, updatedAt
   - Synced from policy-events via Kafka listener

3. **OutboxEvent**
   - Same pattern as policy service
   - Publishes claim events

4. **IdempotencyRecord**
   - Prevents duplicate claim submissions
   - Unique on (operation, actor_id, idempotency_key)

5. **ClaimStatus** (Enum)
   - SUBMITTED, VERIFIED, APPROVED, REJECTED

#### Controllers
**ClaimController** (/claims)
- `POST /`: Submit claim (requires Idempotency-Key)
- `POST /{claimId}/verify`: Verify claim (agent/admin only)
- `POST /{claimId}/approve`: Approve claim (admin only)
- `POST /{claimId}/reject`: Reject claim (admin only)
- `GET /{claimId}`: Get claim details
- `GET /`: List claims (paginated, filtered by actor)

#### Services
**ClaimsService**
- `submit(auth, request, idempotencyKey)`: Submit new claim
  - Validates policyholder owns policy
  - Ensures policy is claimable
  - Publishes CLAIM_SUBMITTED event
- `verify(auth, claimId, notes)`: Verify claim details
- `approve(auth, claimId, notes)`: Approve claim
- `reject(auth, claimId, notes)`: Reject claim
- `list(auth, page, size, sort)`: List claims with pagination
- Event consumption from policy-events topic
- Policy projection caching with fallback

**PolicyProjectionClient**
- REST client to policy service with resilience
- Uses client credentials OAuth2 for authentication
- Circuit breaker pattern for fault tolerance
- Retry logic for transient failures
- Fallback to Redis cache if policy service unavailable

**PolicyProjectionCache**
- Redis-backed cache for policy data
- TTL-based expiration
- Fallback for circuit breaker

**ClaimsOutboxPublisher**
- Scheduled task (every 5 seconds)
- Processes up to 25 pending events per batch
- Publishes to claim-events.v1 topic
- Handles failures with retry and DLQ routing

#### Repositories
1. **ClaimRepository**
   - `findAllByPolicyholderId()`
   - `findAllByAssignedAgentId()` (pagination)

2. **PolicyProjectionRepository**
   - Lookup projections by ID

3. **OutboxEventRepository**
   - Standard outbox queries

4. **IdempotencyRecordRepository**
   - Lookup by operation/actor/key

#### Security
**SecurityUtils**
- Extracts JWT claims into AuthenticatedActor
- Provides userId, username, and role
- Required for all endpoints except health

**AuthenticatedActor** (Record)
- userId: Long
- username: String
- role: UserRole (enum)

### Configuration
- **Database**: PostgreSQL with claims_service schema
- **Port**: 8083
- **Cache**: Redis (localhost:6379)
- **Kafka Topics Consumed**: policy-events.v1
- **Kafka Topics Published**: claim-events.v1, claim-events.DLQ
- **Policy Service**: Client credentials auth, timeouts: 2s connect, 3s read
- **Resilience4j**: Circuit breaker and retry for policy-service calls

### Database Schema
```sql
claims:
  - id (varchar 64, PK)
  - policy_id (varchar 64)
  - policyholder_id (bigint)
  - assigned_agent_id (bigint)
  - description (text)
  - claim_amount (numeric 12,2)
  - status (varchar)
  - decision_notes (text)
  - created_at (timestamp)
  - updated_at (timestamp)
  - Indexes on: policy_id, policyholder_id, assigned_agent_id, status, created_at

policy_projections:
  - id (varchar 64, PK)
  - policyholder_id (bigint)
  - assigned_agent_id (bigint)
  - status (varchar)
  - claimable (boolean)
  - updated_at (timestamp)

outbox_events:
  - Same structure as policy service

idempotency_keys:
  - Same structure as policy service
```

### Dependencies
- Spring Boot Data JPA
- Spring Security OAuth2
- Spring Kafka with manual ACK
- Spring Data Redis
- PostgreSQL JDBC
- Resilience4j (circuit breaker, retry)
- Micrometer Prometheus & Brave/Zipkin
- Lombok

### Main Functionality
- **Claim Submission**: SUBMITTED status with idempotency
- **Claim Workflow**: SUBMITTED → VERIFIED → APPROVED/REJECTED
- **Policy Validation**: Cross-service integration with circuit breaker
- **Kafka Event Consumption**: Sync policy data from policy service
- **Outbox Publishing**: Reliable claim event publishing
- **Authorization**: Based on user role and resource ownership
- **Caching**: Redis cache with fallback behavior

### Event Handling
**Incoming Events**: Consumes policy-events.v1 topic
- POLICY_ISSUED, POLICY_RENEWED, POLICY_LAPSED
- Updates local PolicyProjection table
- Sets claimable flag based on policy status

**Outgoing Events**: Publishes claim-events.v1 topic
- CLAIM_SUBMITTED, CLAIM_VERIFIED, CLAIM_APPROVED, CLAIM_REJECTED
- Transactional outbox pattern for reliability

---

## 5. Platform Common (`platform-common`)

### Purpose
Shared library containing common data models, events, and utilities used by other services.

### Main Source Files
- **DomainEvent.java**: Base event record
- **AggregateType.java**: Event entity types
- **ClaimEventPayload.java**: Claim event details
- **PolicyEventPayload.java**: Policy event details
- **PagedResponse.java**: Pagination response wrapper

### Key Classes & Components

#### Events

**DomainEvent** (Record)
```java
public record DomainEvent(
    String eventId,
    AggregateType aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    JsonNode payload
)
```
- Generic event envelope for Kafka messages
- aggregateType: CLAIM or POLICY
- eventVersion: Supports event schema versioning

**AggregateType** (Enum)
- CLAIM, POLICY

**ClaimEventPayload** (Record)
```java
public record ClaimEventPayload(
    String policyId,
    Long policyholderId,
    Long assignedAgentId,
    String status,
    Long actorId,
    String actorRole,
    BigDecimal claimAmount
)
```

**PolicyEventPayload** (Record)
```java
public record PolicyEventPayload(
    String policyNumber,
    Long policyholderId,
    Long assignedAgentId,
    String status,
    Long actorId,
    String actorRole,
    LocalDate startDate,
    LocalDate endDate
)
```

#### API Response Types

**PagedResponse<T>** (Record)
```java
public record PagedResponse<T>(
    List<T> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    String sort
)
```

### Typical Event Flow

1. **Policy Service** publishes to `policy-events.v1`:
   - POLICY_ISSUED, POLICY_RENEWED, POLICY_LAPSED

2. **Claims Service** consumes and updates PolicyProjection

3. **Claims Service** publishes to `claim-events.v1`:
   - CLAIM_SUBMITTED, CLAIM_VERIFIED, CLAIM_APPROVED, CLAIM_REJECTED

### Dependencies
- Jackson Databind (JSON serialization)

---

## Cross-Service Integration Patterns

### 1. Authentication Flow
```
User → API Gateway (rate limit) → Auth Service (JWT) → Gateway (identity headers)
           ↓
      Downstream Services (verify JWT)
```

### 2. Policy-to-Claims Communication
```
Claims Service → REST Client → Policy Service (/internal/policies/{id}/projection)
                               ↓
                         Circuit Breaker (timeout: 2s connect, 3s read)
                               ↓
                         Redis Cache (fallback)
```

### 3. Event-Driven Synchronization
```
Policy Service → OutboxEvent → Kafka (policy-events.v1)
                                   ↓
                        Claims Service (listener)
                                   ↓
                        PolicyProjection (cache)
```

### 4. Claim Event Publishing
```
Claims Service → OutboxEvent → Publishing Scheduler → Kafka (claim-events.v1)
                                                            ↓
                                                       DLQ (failed)
```

---

## Summary Table

| Aspect | API Gateway | Auth Service | Policy Service | Claims Service | Platform Common |
|--------|-------|------|---------|---------|---------|
| **Port** | 8080 | 8081 | 8082 | 8083 | N/A (library) |
| **Database** | None | MySQL | PostgreSQL | PostgreSQL + Redis | None |
| **Primary Role** | Route & Rate Limit | Authentication | Policy CRUD | Claim Workflow | Shared Models |
| **Key Pattern** | Reactive Gateway | OAuth2 Server | Outbox Event | Event Consumer | Event Definitions |
| **Kafka** | No | No | Producer | Consumer & Producer | Models only |
| **External Calls** | None | None | None | REST to Policy Service | None |
| **Security** | JWT Validation | JWT Generation | JWT Validation | JWT Validation | N/A |
| **Is Synchronous** | Mostly | Fully | Fully | Mixed (REST + Events) | N/A |

---

## Deployment Considerations

1. **Database Setup**: Required for Auth (MySQL), Policy (PG), Claims (PG)
2. **Redis Instance**: Required for API Gateway rate limiting and Claims caching
3. **Kafka Broker**: Required for event streaming
4. **JWT Key Rotation**: Managed via Auth Service configuration
5. **Rate Limit Configuration**: Via API Gateway environment variables
6. **Service Discovery**: Requires service URLs in configuration
7. **Monitoring**: All services report to Prometheus and Zipkin
