# Microservices API Test CURLs

This document contains all CURLs to test all microservices in the Insurance Microservices Platform.

## Service Endpoints & Ports
- **API Gateway**: `http://localhost:8080`
- **Auth Service**: `http://localhost:8081`
- **Policy Service**: `http://localhost:8082`
- **Claims Service**: `http://localhost:8083`

---

## 1. AUTH SERVICE (Port 8081)

### 1.1 Health Check
```bash
curl -X GET http://localhost:8081/auth/health
```

### 1.2 Register User
```bash
curl -X POST http://localhost:8081/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "testuser@example.com",
    "password": "SecurePass@123"
  }'
```

### 1.3 Login
```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "password": "SecurePass@123"
  }'
```

### 1.4 Get Current User (requires token)
```bash
curl -X GET http://localhost:8081/auth/me \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### 1.5 Refresh Token
```bash
curl -X POST http://localhost:8081/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "YOUR_REFRESH_TOKEN_HERE"
  }'
```

### 1.6 Create User (Admin Endpoint)
```bash
curl -X POST http://localhost:8081/auth/admin/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "newadmin",
    "email": "admin@example.com",
    "password": "AdminPass@123"
  }'
```

### 1.7 List Users (Admin Endpoint)
```bash
curl -X GET http://localhost:8081/auth/admin/users
```

### 1.8 OAuth2 - Get JWKS
```bash
curl -X GET http://localhost:8081/oauth2/jwks
```

### 1.9 OAuth2 - Alternative JWKS Endpoint
```bash
curl -X GET http://localhost:8081/.well-known/jwks.json
```

### 1.10 OAuth2 - Client Credentials Token
```bash
curl -X POST http://localhost:8081/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'grant_type=client_credentials&client_id=claims-service&client_secret=claims-service-secret&scope=read%20write'
```

---

## 2. POLICY SERVICE (Port 8082)

### 2.1 Issue Policy (requires token + Idempotency-Key)
```bash
curl -X POST http://localhost:8082/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -H "Idempotency-Key: policy-$(date +%s%N)" \
  -d '{
    "policyNumber": "POL-2026-00001",
    "policyType": "AUTO",
    "coverageAmount": 500000,
    "startDate": "2026-01-01",
    "endDate": "2027-01-01",
    "premiumAmount": 1200.50
  }'
```

### 2.2 List Policies (requires token)
```bash
curl -X GET 'http://localhost:8082/policies?page=0&size=20&sort=createdAt,desc' \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### 2.3 Get Policy by ID (requires token)
```bash
curl -X GET http://localhost:8082/policies/POLICY_ID \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### 2.4 Renew Policy (requires token)
```bash
curl -X POST http://localhost:8082/policies/POLICY_ID/renew \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -d '{
    "renewalStartDate": "2027-01-01",
    "renewalEndDate": "2028-01-01",
    "premiumAmount": 1250.00
  }'
```

### 2.5 Lapse Policy (requires token)
```bash
curl -X POST http://localhost:8082/policies/POLICY_ID/lapse \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### 2.6 Get Policy Projection (Internal - no auth)
```bash
curl -X GET http://localhost:8082/internal/policies/POLICY_ID/projection
```

---

## 3. CLAIMS SERVICE (Port 8083)

### 3.1 Submit Claim (requires token + Idempotency-Key)
```bash
curl -X POST http://localhost:8083/claims \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -H "Idempotency-Key: claim-$(date +%s%N)" \
  -d '{
    "policyId": "POLICY_ID",
    "claimType": "ACCIDENT",
    "claimAmount": 5000.00,
    "description": "Car accident on highway",
    "incidentDate": "2026-04-08T10:30:00Z"
  }'
```

### 3.2 List Claims (requires token)
```bash
curl -X GET 'http://localhost:8083/claims?page=0&size=20&sort=createdAt,desc' \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### 3.3 Get Claim by ID (requires token)
```bash
curl -X GET http://localhost:8083/claims/CLAIM_ID \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### 3.4 Verify Claim (requires token)
```bash
curl -X POST http://localhost:8083/claims/CLAIM_ID/verify \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -d '{
    "notes": "Claim verification completed. All documents verified."
  }'
```

### 3.5 Approve Claim (requires token)
```bash
curl -X POST http://localhost:8083/claims/CLAIM_ID/approve \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -d '{
    "approvedAmount": 5000.00,
    "notes": "Claim approved. Payment will be processed."
  }'
```

### 3.6 Reject Claim (requires token)
```bash
curl -X POST http://localhost:8083/claims/CLAIM_ID/reject \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -d '{
    "rejectionReason": "Claim amount exceeds policy coverage"
  }'
```

---

## 4. API GATEWAY (Port 8080) - Routes to All Services

All endpoints from Auth, Policy, and Claims services are accessible through the API Gateway:

### 4.1 Auth Endpoints via Gateway
```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser", "email": "test@example.com", "password": "SecurePass@123"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "test@example.com", "password": "SecurePass@123"}'

# Get Current User
curl -X GET http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"

# Health Check
curl -X GET http://localhost:8080/api/auth/health
```

### 4.2 Policy Endpoints via Gateway
```bash
# List Policies
curl -X GET 'http://localhost:8080/api/policies?page=0&size=20' \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"

# Get Policy
curl -X GET http://localhost:8080/api/policies/POLICY_ID \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"

# Issue Policy
curl -X POST http://localhost:8080/api/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -H "Idempotency-Key: policy-$(date +%s%N)" \
  -d '{"policyNumber": "POL-2026-00001", "policyType": "AUTO", "coverageAmount": 500000}'
```

### 4.3 Claims Endpoints via Gateway
```bash
# List Claims
curl -X GET 'http://localhost:8080/api/claims?page=0&size=20' \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"

# Get Claim
curl -X GET http://localhost:8080/api/claims/CLAIM_ID \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"

# Submit Claim
curl -X POST http://localhost:8080/api/claims \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -H "Idempotency-Key: claim-$(date +%s%N)" \
  -d '{"policyId": "POLICY_ID", "claimType": "ACCIDENT", "claimAmount": 5000.00}'
```

---

## 5. MONITORING & INFRASTRUCTURE

### 5.1 Prometheus Metrics
```bash
curl -X GET http://localhost:9090/api/v1/query?query=up
```

### 5.2 Grafana Dashboard
```bash
# Access in browser
http://localhost:3000
# Default credentials: admin / Admin@12345
```

### 5.3 Zipkin Distributed Tracing
```bash
# Access in browser
http://localhost:9411
```

### 5.4 Database Connections

#### MySQL (for Auth Service)
```bash
mysql -h localhost -u auth_user -pAuth@123Password! -D authdb
```

#### PostgreSQL (for Policy & Claims)
```bash
psql -h localhost -U policy_user -d postgres
# Password: Policy@123Password!
```

#### Redis Cache
```bash
redis-cli -h localhost -p 6379
```

#### Kafka
```bash
# List topics
docker exec -it secure-auth-service-kafka-1 /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# Consume messages from a topic
docker exec -it secure-auth-service-kafka-1 /opt/bitnami/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic policy-events.v1 --from-beginning
```

---

## 6. TESTING WORKFLOW

### Step 1: Register and Login
```bash
# 1. Register a new user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "rohit8020",
    "email": "rohit@insurance.com",
    "password": "SecurePass@123"
  }'

# 2. Login and capture token (use a tool like jq to extract)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "rohit@insurance.com",
    "password": "SecurePass@123"
  }' | jq -r '.token')

echo $TOKEN
```

### Step 2: Issue a Policy
```bash
curl -X POST http://localhost:8080/api/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: policy-$(date +%s%N)" \
  -d '{
    "policyNumber": "POL-2026-001",
    "policyType": "HOME",
    "coverageAmount": 500000,
    "startDate": "2026-01-01",
    "endDate": "2027-01-01",
    "premiumAmount": 2500.00
  }'
```

### Step 3: Submit a Claim
```bash
curl -X POST http://localhost:8080/api/claims \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: claim-$(date +%s%N)" \
  -d '{
    "policyId": "POLICY_ID_FROM_STEP_2",
    "claimType": "FIRE_DAMAGE",
    "claimAmount": 50000.00,
    "description": "House fire damage",
    "incidentDate": "2026-04-08T10:30:00Z"
  }'
```

---

## Notes

- Replace `YOUR_JWT_TOKEN_HERE` with actual JWT token from login response
- Replace `POLICY_ID` and `CLAIM_ID` with actual IDs returned from API responses
- `Idempotency-Key` header is required for POST requests to `/policies` and `/claims` to ensure idempotence
- All endpoints that require authentication expect Bearer token in `Authorization` header
- Use `jq` tool for better JSON formatting: `curl ... | jq '.'`
- Rate limiting is enabled on the API Gateway for `/api/auth/login` (10 requests/min) and `/api/claims` (20 requests/min)
