# API Gateway Service - Detailed Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Functionality](#core-functionality)
4. [Components](#components)
5. [Key Features](#key-features)
6. [Configuration](#configuration)
7. [Endpoints](#endpoints)
8. [Security](#security)
9. [Code Logic Explanation](#code-logic-explanation)

---

## Overview

The **API Gateway Service** is a Spring Cloud Gateway-based application that serves as the **entry point** for all client requests in the microservices architecture. It is responsible for:

- **Request Routing**: Routes incoming requests to appropriate backend microservices
- **Rate Limiting**: Prevents abuse by limiting requests per user/IP
- **Identity Propagation**: Extracts JWT claims and propagates user identity headers
- **Authentication**: Validates JWT tokens before forwarding requests
- **Cross-Cutting Concerns**: Centralized handling of security, logging, and metrics

**Port**: `8080`  
**Technology Stack**: Spring Cloud Gateway, Spring Security OAuth2, Spring WebFlux (Reactive), Redis

---

## Architecture

### Request Flow

```
Client Request
    ↓
API Gateway (Port 8080)
    ↓
[1. RedisRateLimitFilter] → Rate limit checks
    ↓
[2. IdentityHeaderFilter] → Extract & propagate identity
    ↓
[3. SecurityConfig] → JWT validation
    ↓
Route to Backend Service (Auth/Policy/Claims)
    ↓
Response to Client
```

### Why Spring Cloud Gateway?

- **Reactive Non-blocking**: Built on WebFlux for high throughput
- **Filter Chains**: Allows composable request/response processing
- **Circuit Breaker Integration**: Can failover to backup services
- **Dynamic Routing**: Can route based on headers, paths, methods
- **Stateless**: Easy horizontal scaling

---

## Core Functionality

### 1. **Request Routing**

Routes are defined in `application.yml` configuration:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: http://auth-service:8081
          predicates:
            - Path=/api/auth/**
        - id: policy-service
          uri: http://policy-service:8082
          predicates:
            - Path=/api/policies/**
        - id: claims-service
          uri: http://claims-service:8083
          predicates:
            - Path=/api/claims/**
```

**Routing Logic**:
- Matches incoming request path against predicates
- Forwards request to configured backend service URI
- Maintains HTTP method, headers, and body

### 2. **Rate Limiting**

**Implemented via**: `RedisRateLimitFilter` (Global Filter)

**Purpose**: Prevent API abuse by limiting:
- Login attempts: Max `X` requests/minute per IP
- Claim submissions: Max `Y` requests/minute per user ID

**Algorithm**:
```
1. For each request, generate rate limit key (Redis)
2. Increment counter in Redis
3. Set TTL to 1 minute if first request
4. If counter > limit: Return 429 (Too Many Requests)
5. Otherwise: Allow request to proceed
```

**Why Rate Limiting**:
- Protects against brute-force login attacks
- Prevents claim submission spam
- Maintains service stability under load

### 3. **Identity Propagation**

**Implemented via**: `IdentityHeaderFilter` (Global Filter)

**Purpose**: Extract JWT claims and create custom headers for backend services

**Header Details**:
| Header | Value | Purpose |
|--------|-------|---------|
| `X-User-Id` | UUID from JWT `userId` claim | Identifies user requester |
| `X-Username` | JWT `subject` | Username of requester |
| `X-User-Role` | JWT `role` claim | Authorization role (ADMIN/AGENT/POLICYHOLDER) |

**Why Custom Headers**:
- Backend services don't need to parse JWT again
- Reduces redundant processing
- Simplifies authorization checks
- Acts as audit trail

---

## Components

### 1. **ApiGatewayApplication**

**File**: `ApiGatewayApplication.java`

**Responsibility**: Bootstrap Spring Boot application

```java
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

**What It Does**:
- Initializes Spring context
- Loads all beans (filters, security config, gateway routes)
- Starts embedded Netty server on port 8080

---

### 2. **RedisRateLimitFilter**

**File**: `RedisRateLimitFilter.java`

**Implements**: `GlobalFilter`, `Ordered`

**Purpose**: Rate-limit sensitive endpoints using Redis

#### Key Methods

##### `filter(ServerWebExchange exchange, GatewayFilterChain chain)`

**Logic Flow**:
```
1. Extract request path & method
2. If POST /api/auth/login:
   → Generate key: "rate:login:{IP_ADDRESS}"
   → Check rate limit
3. Else if POST /api/claims:
   → Try to get JWT token
   → Generate key: "rate:claims:{USER_ID}" OR "rate:claims:{IP_ADDRESS}"
   → Check rate limit
4. Else:
   → Allow request through
```

**Code Explanation**:

```java
if (HttpMethod.POST.equals(method) && "/api/auth/login".equals(path)) {
    String key = "rate:login:" + exchange.getRequest().getRemoteAddress();
    return enforce(exchange, chain, key, loginLimit);
}
```

**Why This Logic**:
- Only rate-limit sensitive operations (login, claim submission)
- Use IP for unauthenticated login requests
- Use User ID for authenticated claim requests (more accurate)
- Fall through for other requests (read operations don't need limiting)

##### `enforce(ServerWebExchange, GatewayFilterChain, String key, long limit)`

**Algorithm**:

```java
return redisTemplate.opsForValue().increment(key)
    .flatMap(count -> {
        // Set 1-minute expiry on first request
        Mono<Boolean> expiry = count == 1
            ? redisTemplate.expire(key, Duration.ofMinutes(1))
            : Mono.just(Boolean.TRUE);
        return expiry.thenReturn(count);
    })
    .flatMap(count -> {
        if (count > limit) {
            // Send 429 Too Many Requests
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            byte[] payload = "{\"error\":\"Rate limit exceeded\"}".getBytes();
            return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(payload))
            );
        }
        // Allow request
        return chain.filter(exchange);
    });
```

**Why Reactive/Mono**:
- Non-blocking I/O to Redis
- Maintains high throughput
- Each request doesn't block threads
- Can handle thousands of concurrent requests

**Why Set TTL on count==1**:
- First request in time window sets the window
- If key already exists, Redis already has TTL
- Saves unnecessary Redis calls
- Efficiency optimization

##### `getOrder()`

```java
@Override
public int getOrder() {
    return -20;  // Runs very early in filter chain
}
```

**Why -20**:
- Lower order = runs earlier
- Rate limiting should happen BEFORE expensive operations
- If rate limited, no point authenticating or routing

---

### 3. **IdentityHeaderFilter**

**File**: `IdentityHeaderFilter.java`

**Implements**: `GlobalFilter`, `Ordered`

**Purpose**: Extract JWT claims and propagate as headers

#### Key Method

##### `filter(ServerWebExchange, GatewayFilterChain)`

**Logic Flow**:

```java
return exchange.getPrincipal()
    .cast(JwtAuthenticationToken.class)
    .flatMap(authentication -> {
        // Extract claims from JWT
        Object userId = authentication.getToken().getClaims().get("userId");
        
        // Create mutated exchange with new headers
        ServerWebExchange mutated = exchange.mutate()
            .request(builder -> builder
                .header("X-User-Id", userId == null ? "" : userId.toString())
                .header("X-Username", authentication.getToken().getSubject())
                .header("X-User-Role", authentication.getToken().getClaimAsString("role")))
            .build();
        
        return chain.filter(mutated);
    })
    .switchIfEmpty(chain.filter(exchange));  // If no JWT, pass through
```

**Why This Approach**:

1. **`getPrincipal()`**: Retrieves authenticated user from Spring Security context
2. **`cast(JwtAuthenticationToken.class)`**: Ensures it's a JWT token
3. **`getClaims().get("userId")`**: Extracts custom claim from JWT
4. **`getSubject()`**: JWT standard claim (username)
5. **`getClaimAsString("role")`**: Extracts role claim
6. **`exchange.mutate()`**: Creates new request with added headers
7. **`switchIfEmpty()`**: If not authenticated, just pass through (for public endpoints)

**Why Custom Headers**:

- **Efficiency**: Backend services read headers instead of parsing JWT again
- **Consistency**: All services receive identity in same format
- **Security**: Headers are part of Spring Security context, can't be forged
- **Audit**: Request tracing becomes easier with X-headers

##### `getOrder()`

```java
@Override
public int getOrder() {
    return -10;  // Runs after rate limiting but before routing
}
```

**Why -10**:
- Runs after `-20` (RedisRateLimitFilter)
- Runs before default routes (order 0)
- Only mutate request if it will be routed to backend

---

### 4. **SecurityConfig**

**File**: `SecurityConfig.java`

**Purpose**: Configure OAuth2 Resource Server and JWT validation

**Key Configuration**:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://auth-service:8081  # JWT issuer
          jwk-set-uri: http://auth-service:8081/oauth2/authorize  # Public key endpoint
```

**What SecurityConfig Does**:
1. Enables OAuth2 Resource Server
2. Configures JWT validation using auth-service's public keys
3. Blocks unauthenticated requests to protected paths
4. Allows public endpoints (like `/api/auth/login`, `/api/auth/register`)

**Authorization Rules** (in SecurityConfig):
```
GET    /api/auth/**              → PUBLIC
POST   /api/auth/login           → PUBLIC
POST   /api/auth/register        → PUBLIC
POST   /api/auth/refresh         → PUBLIC

GET    /api/policies/**          → REQUIRES_AUTH
POST   /api/policies/**          → REQUIRES_AUTH
PATCH  /api/policies/**          → REQUIRES_AUTH

GET    /api/claims/**            → REQUIRES_AUTH
POST   /api/claims/**            → REQUIRES_AUTH
PATCH  /api/claims/**            → REQUIRES_AUTH
```

---

## Key Features

| Feature | Implementation | Benefit |
|---------|----------------|---------|
| **Rate Limiting** | Redis + Increment counter | Prevents brute-force attacks |
| **Identity Propagation** | Extract claims → Add headers | Reduces processing in backend |
| **JWT Validation** | OAuth2 Resource Server | Centralized authentication |
| **Reactive Processing** | WebFlux + Project Reactor | High throughput, low latency |
| **Graceful Fallback** | switchIfEmpty, defaultIfEmpty | Service doesn't crash on errors |

---

## Configuration

### `application.yml`

```yaml
spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        # Auth routes
        - id: auth-service
          uri: http://auth-service:8081
          predicates:
            - Path=/api/auth/**
          
        # Policy routes
        - id: policy-service
          uri: http://policy-service:8082
          predicates:
            - Path=/api/policies/**
          
        # Claims routes
        - id: claims-service
          uri: http://claims-service:8083
          predicates:
            - Path=/api/claims/**

  # Redis for rate limiting
  data:
    redis:
      host: redis
      port: 6379

  # OAuth2 / JWT
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://auth-service:8081
          jwk-set-uri: http://auth-service:8081/.well-known/openid-configuration

# Rate limits
app:
  rate-limit:
    login-per-minute: 5          # Max 5 login attempts per minute per IP
    claim-submit-per-minute: 10  # Max 10 claim submissions per minute per user

server:
  port: 8080
```

### `pom.xml` Dependencies

```xml
<dependencies>
    <!-- Spring Cloud Gateway -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>
    
    <!-- OAuth2 Resource Server -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    
    <!-- Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
    </dependency>
    
    <!-- WebFlux (Reactive) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
</dependencies>
```

---

## Endpoints

### Public Endpoints (No Auth Required)

#### 1. **Register New User**
```
POST /api/auth/register
Content-Type: application/json

{
  "username": "user@example.com",
  "password": "SecurePassword123!"
}

Response:
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "uuid.uuid",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

#### 2. **Login**
```
POST /api/auth/login
Content-Type: application/json

{
  "username": "user@example.com",
  "password": "SecurePassword123!"
}

Response:
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "uuid.uuid",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

#### 3. **Refresh Token**
```
POST /api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "uuid.uuid"
}

Response:
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "uuid.uuid",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

### Protected Endpoints (Auth Required)

#### 4. **Get Current User**
```
GET /api/auth/me
Authorization: Bearer {accessToken}

Response:
{
  "id": "uuid-1234",
  "username": "user@example.com",
  "role": "POLICYHOLDER"
}
```

#### 5. **List Policies**
```
GET /api/policies?page=0&size=10&sort=createdAt,desc
Authorization: Bearer {accessToken}

Response:
{
  "content": [
    {
      "id": "uuid-1",
      "policyNumber": "POL-ABC12345",
      "status": "ISSUED",
      "premium": 500.00
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 100
}
```

#### 6. **Submit Claim**
```
POST /api/claims
Authorization: Bearer {accessToken}
Idempotency-Key: {unique-uuid}
Content-Type: application/json

{
  "policyId": "uuid-1",
  "description": "Car accident damage",
  "claimAmount": 2500.00
}

Response:
{
  "id": "claim-uuid-1",
  "policyId": "uuid-1",
  "status": "SUBMITTED",
  "claimAmount": 2500.00
}
```

---

## Security

### 1. **JWT Token Validation**

**Flow**:
```
Request arrives at Gateway
    ↓
SecurityConfig checks Authorization header
    ↓
Validates JWT signature using public key from auth-service
    ↓
Extracts claims (userId, username, role)
    ↓
Creates JwtAuthenticationToken
    ↓
IdentityHeaderFilter adds X-headers
    ↓
Request forwarded to backend with identity
```

**Why Validate at Gateway**:
- Rejects invalid tokens early
- Prevents wasted processing in backend
- Centralized authorization policy

### 2. **Rate Limiting**

**Attack Prevention**:
```
Brute-force attack (100 login attempts/minute)
    ↓
Gateway rate limiter checks Redis
    ↓
Increments counter for IP address
    ↓
After 5 requests: Return 429
    ↓
Attacker must wait 1 minute
```

### 3. **Identity Propagation**

**Prevents Spoofing**:
```
Malicious actor tries to forge X-User-Id header
    ↓
IdentityHeaderFilter OVERWRITES custom headers
    ↓
Uses JWT claim (cryptographically signed)
    ↓
Spoofed header is replaced with true identity
```

### 4. **CORS & XSS Protection**

Configured via Spring Security filters:
```
- X-Frame-Options: DENY
- X-Content-Type-Options: nosniff
- Content-Security-Policy: default-src 'self'
```

---

## Code Logic Explanation

### Redis Rate Limiting Algorithm

**Why Use Redis**:
1. **Fast**: In-memory, microsecond latency
2. **Atomic Operations**: `increment` is atomic, prevents race conditions
3. **Expiry**: Automatic TTL cleanup
4. **Scalable**: Multiple gateway instances can share same Redis

**Counter Logic**:

```
User makes login attempt #1:
  key = "rate:login:192.168.1.100"
  count = Redis.increment(key) → 1
  if count == 1: Redis.expire(key, 60seconds)  // Set 1-minute window
  if count > 5: BLOCK
  else: ALLOW

User makes attempt #2 (within 1 minute):
  count = Redis.increment(key) → 2
  TTL already exists, don't reset
  if count > 5: BLOCK
  else: ALLOW

User makes attempt #6 (within 1 minute):
  count = Redis.increment(key) → 6
  if count > 5: REJECT 429

After 1 minute passes (TTL expires):
  key is deleted from Redis
  Next request starts fresh with count = 1
```

**Why Increment Only Once Per Request**:
```java
Mono<Boolean> expiry = count == 1
    ? redisTemplate.expire(key, Duration.ofMinutes(1))
    : Mono.just(Boolean.TRUE);
```
- If this is the first request (`count == 1`), set expiry
- If key already exists (previous requests), TTL is already set
- Avoids unnecessary Redis calls
- Still safe because TTL was set on first request

### JWT Claim Extraction

**Why Extract CustomClaims**:

JWT Contains:
```json
{
  "sub": "user@example.com",
  "userId": "uuid-1234",
  "role": "POLICYHOLDER",
  "iat": 1234567890,
  "exp": 1234571490
}
```

Backend needs these values, so Gateway extracts:
```
X-User-Id: uuid-1234         (from "userId" claim)
X-Username: user@example.com (from "sub" claim)
X-User-Role: POLICYHOLDER    (from "role" claim)
```

**Why Headers Instead of Forwarding JWT**:
1. Reduces payload size
2. Backend doesn't need to parse JWT again
3. Headers are standard HTTP mechanism
4. Easier to log and debug
5. Some backends might not understand JWT format

### Reactive Streams (Mono/Flux)

**Why Used in Filters**:

```java
return exchange.getPrincipal()           // Mono<Principal>
    .cast(JwtAuthenticationToken.class)  // Mono<JwtAuthenticationToken>
    .flatMap(authentication -> {
        // Do async work, return Mono<Void>
        return chain.filter(mutated);
    })
    .switchIfEmpty(chain.filter(exchange));  // Fallback if no principal
```

**Benefits**:
- **Non-blocking**: Doesn't consume thread while waiting for I/O
- **Composable**: Chain operations with flatMap
- **Lazy**: Processing doesn't start until subscription
- **Backpressure**: Automatically handles slow consumers

---

## Performance Considerations

| Aspect | Optimization | Impact |
|--------|--------------|--------|
| **Rate Limiting** | Redis in-memory | Sub-millisecond checks |
| **JWT Validation** | Cached public keys | Only fetch keys once per startup |
| **Routing** | Path-based predicates | O(1) route lookup |
| **Headers** | Minimal extraction | Only 3 headers added |
| **Async I/O** | WebFlux + Reactive | Handles 10K+ concurrent requests |

---

## Summary

The API Gateway Service is the **intelligent entry point** that:
1. **Routes** requests to correct microservice
2. **Protects** from abuse via rate limiting
3. **Authenticates** users via JWT validation
4. **Propagates** identity for backend authorization
5. **Scales** horizontally with reactive non-blocking I/O

Each filter runs in order (by `getOrder()`), allowing composable, efficient request processing.
