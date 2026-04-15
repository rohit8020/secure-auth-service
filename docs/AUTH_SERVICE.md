# Auth Service - Detailed Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Functionality](#core-functionality)
4. [Data Model](#data-model)
5. [Components](#components)
6. [API Endpoints](#api-endpoints)
7. [Security Implementation](#security-implementation)
8. [Code Logic Explanation](#code-logic-explanation)

---

## Overview

The **Auth Service** is the **central authentication and authorization service** for the entire microservices ecosystem. It handles:

- **User Registration**: Create new user accounts
- **User Authentication**: Validate credentials and issue tokens
- **Token Management**: Issue, refresh, and revoke access tokens
- **Machine-to-Machine Auth**: Service-to-service authentication via OAuth2 client credentials
- **User Management**: Admin operations to manage users and roles

**Port**: `8081`  
**Database**: MySQL  
**Technology Stack**: Spring Boot, Spring Security, JWT, RSA-256 Encryption, JPA/Hibernate

---

## Architecture

### Request Flow

```
Client Login Request
    ↓
AuthController.login()
    ↓
AuthService.login()
    ├─ Fetch user from database
    ├─ Validate password (BCrypt)
    ├─ Generate JWT token (RS256)
    ├─ Create refresh token (hashed)
    └─ Save refresh token
    ↓
AuthResponse with tokens
```

### JWT Token Lifecycle

```
1. User registers/logs in
   ↓
2. Auth Service generates JWT (valid 1 hour)
   ├─ Signed with private key
   ├─ Contains: userId, username, role, issuedAt, expiresAt
   └─ Returned to client
   ↓
3. Client stores JWT + Refresh Token
   ↓
4. Client sends JWT in Authorization header for API requests
   ↓
5. API Gateway validates JWT signature with public key
   ↓
6. When JWT expires:
   └─ Client sends refresh token to Auth Service
   ↓
7. Auth Service issues new JWT (old refresh token revoked)
```

---

## Core Functionality

### 1. **User Registration**

**Endpoint**: `POST /api/auth/register`

**Flow**:
```
1. Receive RegisterRequest (username, password)
2. Check if username already exists
3. Hash password using BCrypt
4. Create new User entity with role=POLICYHOLDER
5. Save to database
6. Issue tokens (access + refresh)
7. Return AuthResponse
```

**Why POLICYHOLDER Role**:
- Only admins can create ADMIN/AGENT users
- Self-registration creates regular policyholders
- Prevents privilege escalation

### 2. **User Login**

**Endpoint**: `POST /api/auth/login`

**Flow**:
```
1. Receive LoginRequest (username, password)
2. Query database for user by username
3. If not found: throw 401 UNAUTHORIZED
4. Compare provided password with hashed password
   ├─ BCrypt does constant-time comparison
   ├─ Prevents timing attacks
   └─ If mismatch: throw 401
5. Revoke all existing refresh tokens for user
6. Issue new tokens
7. Return AuthResponse
```

**Why Revoke Old Tokens**:
- Only one active session per user
- If account compromised, old tokens invalidated
- Security best practice

### 3. **Token Refresh**

**Endpoint**: `POST /api/auth/refresh`

**Flow**:
```
1. Receive RefreshTokenRequest (refreshToken)
2. Hash the raw refresh token
3. Query database for refresh token
4. If not found: throw 401 UNAUTHORIZED
5. If revoked or expired: throw 401
6. Mark refresh token as revoked
7. Issue new access + refresh token pair
8. Return AuthResponse with new tokens
```

**Why Hash Refresh Tokens**:
```
Refresh Token Stored in Database (Hashed):
  Raw Token: "550e8400-e29b-41d4-a716-446655440000.550e8400-e29b-41d4-a716-446655440001"
  Hash:      "a4c3f8d2b1e9c7a5f6d8e9c0b1a2f3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"
  
  If attacker steals database:
  ├─ Cannot use hash to create valid token (one-way)
  └─ If they steal raw token from network, hash doesn't match

If attacker steals raw token from network:
  ├─ Can use it immediately
  └─ But each refresh revokes old token, limits lifetime
```

### 4. **Machine-to-Machine Authentication**

**Endpoint**: `POST /api/auth/oauth2/token` (Client Credentials Flow)

**Flow**:
```
1. Service calls with clientId, clientSecret, scope
2. Query configured machines (from properties)
3. If clientId not found: throw 401
4. Compare clientSecret (constant-time comparison)
5. If mismatch: throw 401
6. Validate requested scope against allowed scopes
7. Generate JWT with scope + client_id (not userId)
8. Return ClientCredentialsTokenResponse
```

**Use Cases**:
- Claims Service calling Policy Service
- Background jobs calling other services
- Internal service-to-service communication

**Why Different Token**:
- Client token has no `userId`
- Different `token_use` claim
- Different expiration (longer, for server processes)

### 5. **User Management (Admin)**

**Endpoint**: `POST /api/admin/users` (Create User)

**Flow**:
```
1. Verify requester is ADMIN role
2. Receive CreateUserRequest (username, password, role)
3. Create user with specified role
4. Only ADMIN can create ADMIN/AGENT/POLICYHOLDER
```

**Bootstrap Admin**:
```
On first startup:
  ├─ Check if any ADMIN user exists
  ├─ If not: Create bootstrap admin from environment variables
  ├─ Username: from JWT_BOOTSTRAP_USER
  └─ Password: from JWT_BOOTSTRAP_PASSWORD
```

---

## Data Model

### 1. **User Entity**

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(unique = true, nullable = false)
    private String username;
    
    @Column(nullable = false)
    private String password;  // BCrypt hashed
    
    @Enumerated(EnumType.STRING)
    private UserRole role;    // ADMIN, AGENT, POLICYHOLDER
    
    @CreationTimestamp
    private Instant createdAt;
}
```

**Why UUID ID**:
- Globally unique across all databases
- Can't predict user IDs
- Better than sequential (integer) IDs for security

**Why BCrypt for Password**:
```
Plain password: "SecurePassword123!"
BCrypt hash:    "$2a$10$yF8jXq/T8YVi4Q3K9L2o7eL8Y9jK0m1n2o3p4q5r6s7t8u9v0w1x2y3z"

Properties:
- Salted: Each password has unique salt
- Iterative: Takes ~100ms to compute (brute-force resistant)
- Constant-time: Prevents timing attacks on comparison
- Upgradeable: Can increase cost factor
```

### 2. **RefreshToken Entity**

```java
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(nullable = false)
    private String tokenHash;  // SHA-256 hash of raw token
    
    @ManyToOne(fetch = FetchType.LAZY)
    private User user;
    
    @Column(nullable = false)
    private boolean revoked;
    
    @Column(nullable = false)
    private Instant expiresAt;  // 7 days
    
    @CreationTimestamp
    private Instant createdAt;
}
```

**Why Separate Table**:
- Track which tokens are revoked
- Audit trail of token issuance
- Can delete expired tokens periodically
- Can implement "logout" by revoking token

### 3. **UserRole Enum**

```java
public enum UserRole {
    ADMIN,          // Full system access, user management
    AGENT,          // Issue policies, verify claims, manage assigned policies
    POLICYHOLDER    // View own policies, submit claims
}
```

**Role Hierarchy**:
```
ADMIN
  ├─ Create users of any role
  ├─ View all policies
  ├─ View all claims
  └─ System administration

AGENT
  ├─ Issue policies (assigned to self)
  ├─ Renew/lapse own policies
  ├─ Verify assigned claims
  ├─ Cannot see other agents' data
  └─ Cannot see admin panel

POLICYHOLDER
  ├─ View own policies
  ├─ Cannot issue/renew policies
  ├─ Submit claims for own policies
  └─ View own claims
```

---

## Components

### 1. **AuthServiceApplication**

**File**: `AuthServiceApplication.java`

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

**`@ConfigurationPropertiesScan`**:
- Enables `@ConfigurationProperties` beans
- Auto-binds YAML properties to Java objects
- Example: `MachineClientProperties` for OAuth2 clients

### 2. **AuthService (Business Logic)**

**File**: `AuthService.java`

#### Key Methods

##### `register(RegisterRequest)`

```java
@Transactional
public AuthResponse register(RegisterRequest request) {
    User user = createUserInternal(
        request.username(), 
        request.password(), 
        UserRole.POLICYHOLDER  // Always POLICYHOLDER
    );
    return issueTokens(user);
}
```

**Why @Transactional**:
- User creation + token issuance must both succeed or both fail
- If token generation fails after user created, rollback user
- ACID guarantee

##### `login(LoginRequest)`

```java
@Transactional
public AuthResponse login(LoginRequest request) {
    User user = userRepository.findByUsername(request.username())
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    return issueTokens(user);
}
```

**Why Same Error Message**:
```
Two possible failures:
1. Username doesn't exist → 401
2. Password wrong → 401

Both return same message: "Invalid credentials"

Why? Prevent username enumeration attack:
  Attacker learns usernames exist if error differs
  Same error forces attacker to guess both username + password
```

**Flow**:
```
1. Query user by username (single database hit)
2. If not found → throw immediately
3. If found → call passwordEncoder.matches()
4. BCrypt compares and returns true/false
5. If false → throw with same error message
```

##### `refresh(RefreshTokenRequest)`

```java
@Transactional
public AuthResponse refresh(RefreshTokenRequest request) {
    RefreshToken token = refreshTokenRepository
        .findByTokenHash(hashToken(request.refreshToken()))
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

    if (token.isRevoked() || token.getExpiresAt().isBefore(Instant.now())) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
    }

    token.setRevoked(true);  // Revoke old token
    return issueTokens(token.getUser());  // Issue new tokens
}
```

**Flow**:
```
1. Hash refresh token from request
2. Query database for matching hash
3. Check if revoked (might have been used before)
4. Check if expired (7-day window)
5. Mark as revoked (can't use again)
6. Generate new tokens for associated user
```

**Why Query by Hash, Not Raw Token**:
- Database never stores raw refresh tokens
- Even if database leaked, tokens unusable
- Hashing is one-way function

##### `issueClientCredentialsToken(clientId, clientSecret, scope)`

```java
public ClientCredentialsTokenResponse issueClientCredentialsToken(
    String clientId,
    String clientSecret,
    String requestedScope) {
    
    MachineClientProperties.Client client = 
        machineClientProperties.clients().stream()
            .filter(c -> c.clientId().equals(clientId))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, 
                "Invalid client credentials"));

    if (!constantTimeEquals(client.clientSecret(), clientSecret)) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid client credentials");
    }

    String scope = resolveScope(client.scopes(), requestedScope);
    String accessToken = jwtUtil.generateClientToken(clientId, scope);
    return new ClientCredentialsTokenResponse(
        accessToken, 
        "Bearer",
        jwtUtil.getClientAccessExpiration() / 1000, 
        scope
    );
}
```

**Why constantTimeEquals**:
```java
private boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8)
    );
}
```

**Prevents Timing Attacks**:
```
Regular string comparison:
  "secret1" vs "secret2"
  Fails at position 6 in ~1 microsecond
  
  "secretabcdefghij" vs "secret2"
  Fails at position 6 in ~1 microsecond
  
Attacker can measure timing:
  If comparison fails early → first characters don't match
  If comparison takes longer → more characters match
  Can brute-force character by character

Constant-time comparison:
  Always compares all bytes
  Takes same time regardless of where mismatch occurs
  Timing reveals no information
```

##### `issueTokens(User)`

```java
private AuthResponse issueTokens(User user) {
    // Revoke all existing tokens for this user
    refreshTokenRepository.findAllByUserIdAndRevokedFalse(user.getId())
        .forEach(token -> token.setRevoked(true));

    // Generate new JWT
    String accessToken = jwtUtil.generateToken(user);
    
    // Generate new refresh token
    String rawRefreshToken = UUID.randomUUID() + "." + UUID.randomUUID();

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setTokenHash(hashToken(rawRefreshToken));
    refreshToken.setUser(user);
    refreshToken.setRevoked(false);
    refreshToken.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
    refreshTokenRepository.save(refreshToken);

    return new AuthResponse(
        accessToken, 
        rawRefreshToken,  // Send raw token to client (one-time)
        jwtUtil.accessExpiration / 1000,
        "Bearer"
    );
}
```

**Token Generation Flow**:
```
1. Revoke all non-revoked refresh tokens for user
   └─ Only one active refresh token at a time
   
2. Generate JWT
   ├─ Includes userId, role, username
   ├─ Signed with private key (RS256)
   └─ Valid for 1 hour
   
3. Generate refresh token
   ├─ UUID.randomUUID() + "." + UUID.randomUUID()
   ├─ Example: "550e8400-e29b-41d4-a716-446655440000.c9c8b8f7-a6a5-4a3a-9b8b-5c7d8e9f0a1b"
   ├─ Very high entropy (2^256)
   └─ Printed once to client (never shown again)
   
4. Hash refresh token and save to database
   ├─ Raw token discarded from memory
   └─ Client stores raw token
   
5. Return tokens to client
```

**Why Two Tokens**:
| Token | Type | Expiry | Storage | Use |
|-------|------|--------|---------|-----|
| Access | JWT | 1 hour | Client | Send every API request |
| Refresh | Opaque | 7 days | Client + DB | Get new access token |

**Why Revoke Old Tokens**:
```
If user logs in 3 times:
  1st login: Token-A issued
  2nd login: Token-A revoked, Token-B issued
  3rd login: Token-B revoked, Token-C issued

If attacker steals Token-A from network:
  ├─ Can use it immediately
  └─ But next refresh invalidates it

Without revocation:
  ├─ Token-A remains valid for 7 days
  ├─ Attacker has 7 days to use stolen token
  └─ User has no way to log them out
```

### 3. **JwtUtil (JWT Generation & Validation)**

**File**: `JwtUtil.java`

#### Key Concepts

**RSA-256 (RS256) Algorithm**:
```
Public Key (known to all services):
  Used to VERIFY signatures only
  Cannot create new signatures

Private Key (secret in Auth Service):
  Used to SIGN tokens
  Never shared with other services

Token Signature:
  Created by: HMAC-SHA256(private_key, payload)
  Verified by: HMAC-SHA256(public_key, payload)
  
Only Auth Service can create valid signatures
All services can verify signatures
```

**Key Pair Generation** (Pre-generated, stored in environment):
```bash
# Generate RSA key pair (one-time setup)
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem

# Base64 encode for application.yml
cat private.pem | base64
cat public.pem | base64
```

#### Key Methods

##### `generateToken(User)`

```java
public String generateToken(User user) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("userId", user.getId());
    claims.put("role", user.getRole().name());
    claims.put("token_use", "user");
    
    return Jwts.builder()
        .setClaims(claims)
        .setSubject(user.getUsername())
        .setIssuer(issuer)                    // "auth-service"
        .setHeaderParam("kid", keyId)         // "rsa-key-1"
        .setIssuedAt(new Date())              // now()
        .setExpiration(new Date(System.currentTimeMillis() + accessExpiration))  // +1 hour
        .signWith(privateKey, SignatureAlgorithm.RS256)
        .compact();
}
```

**Generated JWT Structure**:
```
Header:
{
  "alg": "RS256",
  "kid": "rsa-key-1",
  "typ": "JWT"
}

Payload:
{
  "userId": "uuid-1234",
  "role": "POLICYHOLDER",
  "token_use": "user",
  "sub": "user@example.com",
  "iss": "auth-service",
  "iat": 1700000000,
  "exp": 1700003600
}

Signature:
Base64(HMAC-SHA256(
  privateKey,
  Header.Payload
))
```

**Why `token_use` Claim**:
- Differentiates between user and client tokens
- Backend can reject client tokens for user operations
- Prevents token type confusion attacks

**Why `kid` (Key ID)**:
- Allows key rotation
- API Gateway fetches multiple public keys from Auth Service
- Can specify which key was used to sign

##### `generateClientToken(clientId, scope)`

```java
public String generateClientToken(String clientId, String scope) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("scope", scope);
    claims.put("client_id", clientId);
    claims.put("token_use", "client");
    
    return Jwts.builder()
        .setClaims(claims)
        .setSubject(clientId)
        .setIssuer(issuer)
        .setHeaderParam("kid", keyId)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + clientAccessExpiration))  // +24 hours
        .signWith(privateKey, SignatureAlgorithm.RS256)
        .compact();
}
```

**Differences from User Token**:
```
User Token:
  ├─ Contains userId
  ├─ Expires in 1 hour
  ├─ token_use: "user"
  └─ Identifies person making request

Client Token:
  ├─ Contains client_id (not userId)
  ├─ Expires in 24 hours
  ├─ token_use: "client"
  └─ Identifies service making request
```

##### `extractClaims(String token)`

```java
public Claims extractClaims(String token) {
    return Jwts.parser()
        .verifyWith(publicKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
}
```

**Verification Process**:
```
1. Parse token structure (header.payload.signature)
2. Recompute signature using public key + payload
3. Compare computed signature with provided signature
4. If mismatch: throw JwtException
5. If match: Extract and return claims
```

**Why This Order**:
- Verification happens BEFORE returning claims
- Invalid tokens never expose claims
- Attacker can't tamper with token content

### 4. **AuthController**

**File**: `AuthController.java`

**Endpoints**:
```java
@PostMapping("/register")
public AuthResponse register(@RequestBody RegisterRequest request)

@PostMapping("/login")
public AuthResponse login(@RequestBody LoginRequest request)

@PostMapping("/refresh")
public AuthResponse refresh(@RequestBody RefreshTokenRequest request)

@GetMapping("/me")
public UserResponse currentUser(Authentication auth)

@GetMapping("/users")
public List<UserResponse> listUsers()

@PostMapping("/users")  // Admin only
public UserResponse createUser(@RequestBody CreateUserRequest request)
```

### 5. **AdminUserController**

**File**: `AdminUserController.java`

**Admin-only operations**:
```java
@PostMapping("/users")
public UserResponse createUser(CreateUserRequest request)

@DeleteMapping("/users/{userId}")
public void deleteUser(String userId)

@PatchMapping("/users/{userId}/role")
public void updateUserRole(String userId, UserRole newRole)
```

### 6. **OAuthController**

**File**: `OAuthController.java`

**OAuth2 Token Endpoint**:
```java
@PostMapping("/oauth2/token")
public ClientCredentialsTokenResponse getToken(
    @RequestParam String client_id,
    @RequestParam String client_secret,
    @RequestParam(required = false) String scope
)
```

---

## API Endpoints

### 1. User Registration

```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "john@example.com",
  "password": "SecurePass123!"
}

Response: 200 OK
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6InJzYS1rZXktMSJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000.c9c8b8f7-a6a5-4a3a-9b8b-5c7d8e9f0a1b",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}

Error: 409 CONFLICT
{
  "error": "Username already exists"
}
```

### 2. User Login

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "john@example.com",
  "password": "SecurePass123!"
}

Response: 200 OK
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6InJzYS1rZXktMSJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000.c9c8b8f7-a6a5-4a3a-9b8b-5c7d8e9f0a1b",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}

Error: 401 UNAUTHORIZED
{
  "error": "Invalid credentials"
}
```

### 3. Token Refresh

```http
POST /api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000.c9c8b8f7-a6a5-4a3a-9b8b-5c7d8e9f0a1b"
}

Response: 200 OK
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6InJzYS1rZXktMSJ9...",
  "refreshToken": "a1b2c3d4-e5f6-47g8-h9i0-j1k2l3m4n5o6.p7q8r9s0-t1u2-43v4-w5x6-y7z8a9b0c1d2",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}

Error: 401 UNAUTHORIZED
{
  "error": "Refresh token expired"
}
```

### 4. Get Current User

```http
GET /api/auth/me
Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6InJzYS1rZXktMSJ9...

Response: 200 OK
{
  "id": "uuid-1234",
  "username": "john@example.com",
  "role": "POLICYHOLDER"
}
```

### 5. Client Credentials (M2M Auth)

```http
POST /api/auth/oauth2/token
Content-Type: application/x-www-form-urlencoded

client_id=claims-service&client_secret=secret123&scope=policy.read

Response: 200 OK
{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6InJzYS1rZXktMSJ9...",
  "token_type": "Bearer",
  "expires_in": 86400,
  "scope": "policy.read"
}
```

---

## Security Implementation

### 1. **Password Security**

**BCrypt Configuration**:
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);  // Cost factor 12
}
```

**Cost Factor Logic**:
```
Cost 10: ~10ms to hash (fast, ~2010 era)
Cost 12: ~100ms to hash (secure, recommended)
Cost 14: ~1000ms to hash (slow, for high security)

Brute-force attack on Cost 12:
  To crack 1 password with 8-char alphanumeric (~10^14 possibilities):
  100ms * 10^14 = 10^16 ms = 317 years with 1 GPU
```

### 2. **JWT Security**

**RS256 Benefits**:
```
Symmetric (HS256):
  ├─ 1 secret key shared by all services
  ├─ Any service can forge tokens
  └─ Risky if service compromised

Asymmetric (RS256):
  ├─ Private key only in Auth Service
  ├─ Public key in all services
  ├─ Services VERIFY but can't SIGN
  └─ Compromising one service doesn't affect token security
```

### 3. **Refresh Token Security**

**Storage Strategy**:
```
Client      : "raw-token-uuid.uuid" (memory, httpOnly cookie)
Database    : "sha256-hash-of-raw-token" (persistent)

Attack 1: Attacker steals raw token from network
  ├─ Can use immediately (but TTL limits damage)
  └─ Each refresh revokes it (active defense)

Attack 2: Attacker gets database access
  ├─ Cannot use hashed token directly
  ├─ Client stores raw token, not hash
  └─ Hash helps no attacker with database

Attack 3: User's device compromised
  ├─ Attacker gets raw token from local storage
  ├─ Can use like normal attacker
  └─ TTL + refresh revocation limits damage
```

### 4. **Constant-Time Comparison**

**Prevents Side-Channel Attacks**:
```
Timing attack scenario:
  Attacker tries clientSecret: "secret..."
  
Regular string comparison:
  "secretABC" vs "secret123"
   ^^^^^^     ← Fails here in ~1 microsecond
  
  "secretXYZ" vs "secret123"
   ^^^^^^     ← Fails here in ~1 microsecond
  
  Attacker learns nothing (both fail at same time)

But with naive comparison on password:
  "secretABC" vs expected
   Fails at char 7 in ~1 µs
  
  "abcdefgh" vs expected
   Fails at char 1 in ~0.1 µs
  
  Attacker learns: more characters = closer to real password
  Can brute-force character by character by timing responses
```

**Constant-Time Fix**:
```java
public static boolean constantTimeEquals(String a, String b) {
    byte[] aBytes = a.getBytes(UTF_8);
    byte[] bBytes = b.getBytes(UTF_8);
    
    int result = 0;
    // Always compare all bytes, even after mismatch
    for (int i = 0; i < Math.max(aBytes.length, bBytes.length); i++) {
        result |= aBytes[i] ^ bBytes[i];
    }
    return result == 0;
}
```

---

## Code Logic Explanation

### Password Hashing & Verification

```java
// Registration - Hash password
String hashed = passwordEncoder.encode("SecurePassword123!");
// Result: "$2a$12$yF8jXq/T8YVi4Q3K9L2o7eL8Y9jK0m1n2o3p4q5r6s7t8u9v0w1x"

// Login - Verify password
boolean matches = passwordEncoder.matches("SecurePassword123!", hashed);
// BCrypt automatically extracts salt from hash and recomputes

User user = new User();
user.setPassword(hashed);  // Store hashed, never store plain
userRepository.save(user);
```

**Why BCrypt Better Than SHA-256**:
```
SHA-256:
  └─ sha256("password") = "5e884898da28047151d0e56f8dc62927" (instantly)
  └─ Brute-forceable: 10 billion SHA-256 hashes per second

BCrypt:
  └─ bcrypt("password") takes ~100ms (configurable)
  └─ Brute-force: 100 hashes per second (10 million times slower)
  └─ Uses salt (prevents rainbow tables)
  └─ Cost factor can increase over time
```

### Token Hash for Refresh Tokens

```java
// When generating refresh token
String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
String tokenHash = hashToken(rawToken);  // SHA-256

private String hashToken(String token) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
    }
}

// Save to DB
refreshToken.setTokenHash(tokenHash);  // Store hash, not raw
refreshTokenRepository.save(refreshToken);

// Client receives
return new AuthResponse(rawToken, ...);  // Send raw once
```

**Why One-Time Hash**:
```
1. Client receives raw token (only shown once)
2. Client stores raw token in httpOnly cookie
3. Each API request sends raw token for refresh
4. Server hashes incoming token, looks up in DB
5. Uses associated user to issue new tokens
6. Revokes old refresh token

If token stolen from network:
  ├─ Attacker gets raw token
  ├─ Can use immediately
  ├─ But next refresh invalidates it
  └─ Limited time window

If database compromised:
  ├─ Attacker gets hash
  ├─ Can't reverse hash to get raw token
  ├─ Can't use hash to refresh (server hashes incoming token)
  └─ Would need raw token (only in client cookies)
```

### Transaction Management

```java
@Transactional
public AuthResponse login(LoginRequest request) {
    User user = userRepository.findByUsername(request.username())
        .orElseThrow(...);
    
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
        throw new ApiException(...);
    }
    
    return issueTokens(user);  // Creates RefreshToken entity
}
```

**Why @Transactional**:
```
Scenario 1: Normal login
  1. Load user ✓
  2. Verify password ✓
  3. Issue tokens ✓
  4. COMMIT all changes
  
Scenario 2: Unexpected error
  1. Load user ✓
  2. Verify password ✓
  3. Revoke old tokens ✓
  4. Save new refresh token... ERROR!
  Result: ROLLBACK everything
          RefreshToken not saved to DB
          User not left in inconsistent state

Without @Transactional:
  RefreshToken saved, but exception thrown
  Client thinks login failed (no tokens returned)
  But database has the token
  Later, attacker uses orphaned token
```

---

## Configuration

### `application.yml`

```yaml
spring:
  application:
    name: auth-service
  
  datasource:
    url: jdbc:mysql://mysql:3306/auth_db
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:password}
    driver-class-name: com.mysql.cj.jdbc.Driver
  
  jpa:
    hibernate:
      ddl-auto: validate  # Don't auto-create schema
    show-sql: false

jwt:
  private-key: ${JWT_PRIVATE_KEY}  # RSA private key (base64)
  public-key: ${JWT_PUBLIC_KEY}    # RSA public key (base64)
  key-id: rsa-key-1
  issuer: auth-service
  expiration: 3600000              # 1 hour
  refresh-expiration: 604800000    # 7 days
  client-expiration: 86400000      # 24 hours

machine-client:
  clients:
    - client-id: claims-service
      client-secret: ${CLAIMS_SERVICE_SECRET}
      scopes: ["policy.read", "policy.write"]
    - client-id: policy-service
      client-secret: ${POLICY_SERVICE_SECRET}
      scopes: ["claims.read"]

server:
  port: 8081

bootstrap:
  admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:Admin@12345}
```

---

## Summary

The **Auth Service** is the security backbone providing:
1. **User Management**: Registration, login, authentication
2. **OAuth2 Token Server**: Issue JWTs and refresh tokens
3. **Machine Auth**: Service-to-service authentication
4. **Cryptographic Security**: RS256 JWT, BCrypt passwords, SHA-256 hashing
5. **Defense Against Attacks**: Timing attacks, brute-force, token stealing

Every token is signed, every password is hashed, and every design decision prioritizes security without sacrificing usability.
