# Complete Postman Guide - Microservices Testing & Monitoring

## 📋 Table of Contents
1. [Postman Collection Setup](#postman-collection-setup)
2. [Base URL Configuration](#base-url-configuration)
3. [All CURL Commands for Postman](#all-curl-commands-for-postman)
4. [Testing Workflow](#testing-workflow)
5. [Prometheus Setup & Usage](#prometheus-setup--usage)
6. [Zipkin Setup & Usage](#zipkin-setup--usage)
7. [Grafana Setup & Usage](#grafana-setup--usage)
8. [Environment Variables in Postman](#environment-variables-in-postman)

---

## Postman Collection Setup

### Option 1: Import from RAW CURL (Recommended)
1. Open **Postman**
2. Click **Import** (top-left)
3. Select **Raw Text** tab
4. Copy any CURL command from below
5. Click **Continue** → Name the request → Save
6. Repeat for all endpoints

### Option 2: Create Postman Collection Manually
1. Click **+** to create new collection
2. Name it: `Insurance Microservices`
3. Create folders:
   - Auth Service
   - Policy Service
   - Claims Service
   - Monitoring

---

## Base URL Configuration

### Set Up Postman Environment Variables

1. **Click Settings** → **Environments** → **Create**
2. **Environment Name:** `Insurance-Platform-Dev`
3. **Add Variables:**

| Variable | Initial Value | Current Value |
|----------|---------------|---------------|
| `base_url` | `http://localhost:8080` | `http://localhost:8080` |
| `auth_service_url` | `http://localhost:8081` | `http://localhost:8081` |
| `policy_service_url` | `http://localhost:8082` | `http://localhost:8082` |
| `claims_service_url` | `http://localhost:8083` | `http://localhost:8083` |
| `token` | (empty) | (will be filled after login) |
| `refresh_token` | (empty) | (will be filled after login) |
| `policy_id` | (empty) | (will be filled after issue policy) |
| `claim_id` | (empty) | (will be filled after submit claim) |

4. Click **Save**

### Use Variables in Requests
```
{{base_url}}/api/auth/login
{{auth_service_url}}/auth/health
{{policy_service_url}}/policies/{{policy_id}}
```

---

## All CURL Commands for Postman

### ⭐ FOLDER 1: AUTH SERVICE

#### 1.1 Health Check
```
curl -X GET {{auth_service_url}}/auth/health
```

**Postman Setup:**
- Method: `GET`
- URL: `{{auth_service_url}}/auth/health`
- Expected Response: `200 OK`
```json
{
  "status": "UP",
  "service": "auth-service"
}
```

---

#### 1.2 Register User
```
curl -X POST {{base_url}}/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "testuser@example.com",
    "password": "SecurePass@123"
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/auth/register`
- Headers:
  ```
  Content-Type: application/json
  ```
- Body (raw JSON):
  ```json
  {
    "username": "testuser",
    "email": "testuser@example.com",
    "password": "SecurePass@123"
  }
  ```

**Save Response:**
- In Tests tab add:
  ```javascript
  if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("token", jsonData.token);
    pm.environment.set("refresh_token", jsonData.refreshToken);
  }
  ```

---

#### 1.3 Login
```
curl -X POST {{base_url}}/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "password": "SecurePass@123"
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/auth/login`
- Headers: `Content-Type: application/json`
- Body (raw JSON):
  ```json
  {
    "email": "testuser@example.com",
    "password": "SecurePass@123"
  }
  ```

**Tests Tab (Auto-capture token):**
```javascript
if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("token", jsonData.token);
    pm.environment.set("refresh_token", jsonData.refreshToken);
}
```

**Expected Response:**
```json
{
  "userId": "123",
  "token": "eyJhbGciOiJSUzI1NiIsInR5cC6...",
  "refreshToken": "eye2ZXJzaW9uIjoiMTAsIm...",
  "expiresIn": 3600000
}
```

---

#### 1.4 Get Current User (Authenticated)
```
curl -X GET {{base_url}}/api/auth/me \
  -H "Authorization: Bearer {{token}}"
```

**Postman Setup:**
- Method: `GET`
- URL: `{{base_url}}/api/auth/me`
- Headers:
  ```
  Authorization: Bearer {{token}}
  ```

---

#### 1.5 Refresh Token
```
curl -X POST {{base_url}}/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "{{refresh_token}}"
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/auth/refresh`
- Headers: `Content-Type: application/json`
- Body (raw JSON):
  ```json
  {
    "refreshToken": "{{refresh_token}}"
  }
  ```

---

#### 1.6 Admin: Create User
```
curl -X POST {{auth_service_url}}/auth/admin/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin2",
    "email": "admin2@example.com",
    "password": "AdminPass@123"
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{auth_service_url}}/auth/admin/users`
- Headers: `Content-Type: application/json`
- Body (raw JSON):
  ```json
  {
    "username": "admin2",
    "email": "admin2@example.com",
    "password": "AdminPass@123"
  }
  ```

---

#### 1.7 Admin: List Users
```
curl -X GET {{auth_service_url}}/auth/admin/users
```

**Postman Setup:**
- Method: `GET`
- URL: `{{auth_service_url}}/auth/admin/users`

---

#### 1.8 OAuth2: Get JWKS
```
curl -X GET {{auth_service_url}}/oauth2/jwks
```

**Postman Setup:**
- Method: `GET`
- URL: `{{auth_service_url}}/oauth2/jwks`
- Expected: Returns public key set for JWT verification

---

#### 1.9 OAuth2: Client Credentials
```
curl -X POST {{auth_service_url}}/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'grant_type=client_credentials&client_id=claims-service&client_secret=claims-service-secret&scope=read%20write'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{auth_service_url}}/oauth2/token`
- Headers: `Content-Type: application/x-www-form-urlencoded`
- Body (x-www-form-urlencoded):
  ```
  Key: grant_type          Value: client_credentials
  Key: client_id           Value: claims-service
  Key: client_secret       Value: claims-service-secret
  Key: scope               Value: read write
  ```

---

### ⭐ FOLDER 2: POLICY SERVICE

#### 2.1 List Policies
```
curl -X GET "{{base_url}}/api/policies?page=0&size=20&sort=createdAt,desc" \
  -H "Authorization: Bearer {{token}}"
```

**Postman Setup:**
- Method: `GET`
- URL: `{{base_url}}/api/policies`
- Params:
  ```
  page: 0
  size: 20
  sort: createdAt,desc
  ```
- Headers: `Authorization: Bearer {{token}}`

---

#### 2.2 Issue Policy (Create)
```
curl -X POST {{base_url}}/api/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -H "Idempotency-Key: policy-$(date +%s%N)" \
  -d '{
    "policyNumber": "POL-2026-001",
    "policyType": "HOME",
    "coverageAmount": 500000,
    "startDate": "2026-04-14",
    "endDate": "2027-04-14",
    "premiumAmount": 2500.00
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/policies`
- Headers:
  ```
  Content-Type: application/json
  Authorization: Bearer {{token}}
  Idempotency-Key: {{$timestamp}}-policy
  ```
- Body (raw JSON):
  ```json
  {
    "policyNumber": "POL-2026-001",
    "policyType": "HOME",
    "coverageAmount": 500000,
    "startDate": "2026-04-14",
    "endDate": "2027-04-14",
    "premiumAmount": 2500.00
  }
  ```

**Tests Tab (Auto-capture policy_id):**
```javascript
if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("policy_id", jsonData.policyId);
}
```

---

#### 2.3 Get Policy by ID
```
curl -X GET {{base_url}}/api/policies/{{policy_id}} \
  -H "Authorization: Bearer {{token}}"
```

**Postman Setup:**
- Method: `GET`
- URL: `{{base_url}}/api/policies/{{policy_id}}`
- Headers: `Authorization: Bearer {{token}}`

---

#### 2.4 Renew Policy
```
curl -X POST {{base_url}}/api/policies/{{policy_id}}/renew \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{
    "renewalStartDate": "2027-04-14",
    "renewalEndDate": "2028-04-14",
    "premiumAmount": 2600.00
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/policies/{{policy_id}}/renew`
- Headers: `Authorization: Bearer {{token}}`
- Body (raw JSON):
  ```json
  {
    "renewalStartDate": "2027-04-14",
    "renewalEndDate": "2028-04-14",
    "premiumAmount": 2600.00
  }
  ```

---

#### 2.5 Lapse Policy
```
curl -X POST {{base_url}}/api/policies/{{policy_id}}/lapse \
  -H "Authorization: Bearer {{token}}"
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/policies/{{policy_id}}/lapse`
- Headers: `Authorization: Bearer {{token}}`

---

#### 2.6 Get Policy Projection (Internal)
```
curl -X GET {{policy_service_url}}/internal/policies/{{policy_id}}/projection
```

**Postman Setup:**
- Method: `GET`
- URL: `{{policy_service_url}}/internal/policies/{{policy_id}}/projection`
- No auth required (internal endpoint)

---

### ⭐ FOLDER 3: CLAIMS SERVICE

#### 3.1 List Claims
```
curl -X GET "{{base_url}}/api/claims?page=0&size=20&sort=createdAt,desc" \
  -H "Authorization: Bearer {{token}}"
```

**Postman Setup:**
- Method: `GET`
- URL: `{{base_url}}/api/claims`
- Params:
  ```
  page: 0
  size: 20
  sort: createdAt,desc
  ```
- Headers: `Authorization: Bearer {{token}}`

---

#### 3.2 Submit Claim
```
curl -X POST {{base_url}}/api/claims \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -H "Idempotency-Key: claim-$(date +%s%N)" \
  -d '{
    "policyId": "{{policy_id}}",
    "claimType": "FIRE_DAMAGE",
    "claimAmount": 50000.00,
    "description": "House fire damage",
    "incidentDate": "2026-04-08T10:30:00Z"
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/claims`
- Headers:
  ```
  Content-Type: application/json
  Authorization: Bearer {{token}}
  Idempotency-Key: {{$timestamp}}-claim
  ```
- Body (raw JSON):
  ```json
  {
    "policyId": "{{policy_id}}",
    "claimType": "FIRE_DAMAGE",
    "claimAmount": 50000.00,
    "description": "House fire damage",
    "incidentDate": "2026-04-08T10:30:00Z"
  }
  ```

**Tests Tab (Auto-capture claim_id):**
```javascript
if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("claim_id", jsonData.claimId);
}
```

---

#### 3.3 Get Claim by ID
```
curl -X GET {{base_url}}/api/claims/{{claim_id}} \
  -H "Authorization: Bearer {{token}}"
```

**Postman Setup:**
- Method: `GET`
- URL: `{{base_url}}/api/claims/{{claim_id}}`
- Headers: `Authorization: Bearer {{token}}`

---

#### 3.4 Verify Claim
```
curl -X POST {{base_url}}/api/claims/{{claim_id}}/verify \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{
    "notes": "Claim verification completed. All documents verified."
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/claims/{{claim_id}}/verify`
- Headers: `Authorization: Bearer {{token}}`
- Body (raw JSON):
  ```json
  {
    "notes": "Claim verification completed. All documents verified."
  }
  ```

---

#### 3.5 Approve Claim
```
curl -X POST {{base_url}}/api/claims/{{claim_id}}/approve \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{
    "approvedAmount": 50000.00,
    "notes": "Claim approved. Payment will be processed."
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/claims/{{claim_id}}/approve`
- Headers: `Authorization: Bearer {{token}}`
- Body (raw JSON):
  ```json
  {
    "approvedAmount": 50000.00,
    "notes": "Claim approved. Payment will be processed."
  }
  ```

---

#### 3.6 Reject Claim
```
curl -X POST {{base_url}}/api/claims/{{claim_id}}/reject \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{
    "rejectionReason": "Claim amount exceeds policy coverage"
  }'
```

**Postman Setup:**
- Method: `POST`
- URL: `{{base_url}}/api/claims/{{claim_id}}/reject`
- Headers: `Authorization: Bearer {{token}}`
- Body (raw JSON):
  ```json
  {
    "rejectionReason": "Claim amount exceeds policy coverage"
  }
  ```

---

## Testing Workflow

### Complete Flow in Postman (Step-by-Step)

**Step 1: Register User**
- Request: `Register User` (from Auth folder)
- Check response → Token is automatically saved to environment

**Step 2: Login**
- Request: `Login` (from Auth folder)
- Check response → New token is captured

**Step 3: Create Policy**
- Request: `Issue Policy` (from Policy folder)
- Check response → Policy ID is captured
- Take note of returned `policyId`

**Step 4: Verify Policy Created**
- Request: `Get Policy by ID` (from Policy folder)
- URL will use `{{policy_id}}` automatically

**Step 5: Submit Claim**
- Request: `Submit Claim` (from Claims folder)
- Use `{{policy_id}}` in request body
- Check response → Claim ID is captured

**Step 6: Verify Claim Created**
- Request: `Get Claim by ID` (from Claims folder)
- URL will use `{{claim_id}}` automatically

**Step 7: Approve Claim**
- Request: `Approve Claim` (from Claims folder)
- Check response → Claim status changes to APPROVED

---

## Prometheus Setup & Usage

### What is Prometheus?
- Time-series metrics database
- Scrapes metrics from services every 15 seconds
- Stores data for querying and alerting

### Access Prometheus
```
URL: http://localhost:9090
```

### Common Metrics to Query

#### 1. **Service Health Check**
```
up{job="prometheus"}
```

**What it means:**
- `1` = Service is UP
- `0` = Service is DOWN

#### 2. **HTTP Request Rate (Requests per second)**
```
rate(http_server_requests_seconds_count[5m])
```

**What it shows:**
- How many requests are hitting each endpoint per second
- Filter by job/instance to see specific service

#### 3. **HTTP Response Time (Latency)**
```
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```

**What it shows:**
- 95th percentile response time in seconds
- Higher = Slower responses

#### 4. **Error Rate**
```
rate(http_server_requests_seconds_count{status=~"5.."}[5m])
```

**What it shows:**
- Number of 5xx errors per second
- Indicates service health problems

#### 5. **JVM Memory Usage**
```
jvm_memory_used_bytes{area="heap"}
```

**What it shows:**
- Memory consumed by application
- Helps identify memory leaks

#### 6. **CPU Usage**
```
process_cpu_seconds_total
```

**What it shows:**
- CPU time consumed by process

### Prometheus Query Steps

1. Open http://localhost:9090
2. Click **Graph** tab
3. Enter query in search box (e.g., `up`)
4. Click **Execute**
5. View results in **Graph** or **Table** tabs
6. Adjust time range using **-5m** button (last 5 minutes)

### Set Up Grafana Dashboard for Prometheus
*(See Grafana section below)*

---

## Zipkin Setup & Usage

### What is Zipkin?
- Distributed tracing system
- Tracks requests across multiple services
- Shows latency breakdown between services

### Access Zipkin
```
URL: http://localhost:9411
```

### How Requests are Traced

When you make a request:
```
API Gateway (8080)
    └─→ Auth Service (8081) [5ms]
    └─→ Policy Service (8082) [150ms]
    └─→ Claims Service (8083) [100ms]
Total: 255ms
```

Zipkin shows each segment and helps find bottlenecks!

### Using Zipkin

#### 1. **View Service Dependencies**
1. Open http://localhost:9411
2. Click **Dependencies** tab
3. See diagram of how services call each other

#### 2. **Search for Traces**
1. Click **Search** tab
2. Select **Service**: `api-gateway`
3. Click **Find Traces**
4. See all requests made to API Gateway

#### 3. **View Trace Details**
1. Click on any trace
2. See timeline of all service calls
3. Expands to show:
   - Service name
   - Operation (endpoint)
   - Duration in ms
   - Logs/Errors if any

#### 4. **Analyze Latency**
- Look at **Service Latency** graph
- Find slowest service call
- Optimize that service

### Example Zipkin Query

**Scenario:** Login is taking too long

**Steps:**
1. Open Zipkin
2. Search Service: `api-gateway`, Operation: `/api/auth/login`
3. Click trace
4. See breakdown:
   - Api-gateway process: 2ms
   - Call to auth-service: 150ms  ← **Bottleneck!**
5. Optimize auth-service response

---

## Grafana Setup & Usage

### What is Grafana?
- Visualization dashboard for metrics
- Creates graphs, charts, alerts
- Uses Prometheus as data source

### Access Grafana
```
URL: http://localhost:3000
Credentials:
  - Username: admin
  - Password: Admin@12345
```

### Initial Setup

#### Step 1: Add Prometheus Data Source
1. Log in to http://localhost:3000
2. Click **Configuration** (gear icon) → **Data Sources**
3. Click **Add data source**
4. Select **Prometheus**
5. Set URL: `http://prometheus:9090`
6. Click **Save & Test**

#### Step 2: Create Dashboard
1. Click **Create** (+ icon) → **Dashboard**
2. Click **Add panel**
3. In data source, select **Prometheus**
4. Enter query: `up`
5. Click **Apply**
6. Save dashboard

### Pre-built Dashboard Shortcuts

#### Dashboard 1: Service Health
```
Panel 1 - Service Status:
Query: up{job="prometheus"}
Type: Stat (Shows number)

Panel 2 - Request Rate:
Query: rate(http_server_requests_seconds_count[5m])
Type: Graph

Panel 3 - Error Rate:
Query: rate(http_server_requests_seconds_count{status=~"5.."}[5m])
Type: Graph

Panel 4 - Latency (P95):
Query: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
Type: Graph
```

#### Dashboard 2: Performance Metrics
```
Panel 1 - JVM Memory:
Query: jvm_memory_used_bytes
Type: Graph

Panel 2 - Thread Count:
Query: jvm_threads_live_count
Type: Stat

Panel 3 - GC Time:
Query: rate(jvm_gc_pause_seconds_sum[5m])
Type: Graph

Panel 4 - HTTP Response Time:
Query: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))
Type: Graph
```

### Create Custom Panel

**Example: Request Rate per Endpoint**

1. Create new dashboard
2. Add panel
3. In Prometheus query:
   ```
   sum(rate(http_server_requests_seconds_count[5m])) by (uri)
   ```
4. Visualization: **Table** or **Graph**
5. Save

### Alerts in Grafana

**Example: Alert if error rate > 1%**

1. Edit panel
2. Click **Alert** tab
3. Set condition: `error_rate > 0.01`
4. Set notification channel (Email, Slack, etc.)
5. Save

---

## Environment Variables in Postman

### Pre-request Scripts (Auto-generate values)

#### Auto-generate Timestamp
```javascript
pm.environment.set("timestamp", Date.now());
```

#### Auto-generate UUID
```javascript
const uuid = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
});
pm.environment.set("requestId", uuid());
```

#### Extract Token from Login Response
```javascript
if (pm.response.code === 200) {
    var jsonData = pm.response.json();
    pm.environment.set("token", jsonData.token);
    pm.environment.set("refreshToken", jsonData.refreshToken);
}
```

### Test Scripts (Validate Responses)

#### Validate Response Status
```javascript
pm.test("Status code is 200", function() {
    pm.response.to.have.status(200);
});
```

#### Validate Response Body
```javascript
pm.test("Response contains userId", function() {
    var jsonData = pm.response.json();
    pm.expect(jsonData).to.have.property('userId');
});
```

#### Validate Response Time
```javascript
pm.test("Response time is less than 500ms", function() {
    pm.expect(pm.response.responseTime).to.be.below(500);
});
```

---

## 🚀 Complete Testing Checklist

- [ ] Create Postman Collection
- [ ] Set up Environment Variables
- [ ] Register User
- [ ] Login (capture token)
- [ ] Create Policy (capture policy_id)
- [ ] List Policies
- [ ] Get Policy Details
- [ ] Renew Policy
- [ ] Submit Claim (capture claim_id)
- [ ] List Claims
- [ ] Get Claim Details
- [ ] Verify Claim
- [ ] Approve Claim
- [ ] Check Prometheus metrics
- [ ] View Zipkin traces
- [ ] Create Grafana dashboard

---

## 📚 Quick Reference

| Tool | URL | Purpose |
|------|-----|---------|
| **API Gateway** | http://localhost:8080 | Route to all services |
| **Auth Service** | http://localhost:8081 | User authentication |
| **Policy Service** | http://localhost:8082 | Policy management |
| **Claims Service** | http://localhost:8083 | Claims management |
| **Prometheus** | http://localhost:9090 | Metrics collection |
| **Grafana** | http://localhost:3000 | Dashboards (admin/Admin@12345) |
| **Zipkin** | http://localhost:9411 | Distributed tracing |

---

## 🎯 Common Issues & Solutions

### Issue: Token expired
**Solution:** Call refresh endpoint or login again to get new token

### Issue: Idempotency-Key error
**Solution:** Ensure header `Idempotency-Key` is unique for each POST request

### Issue: 401 Unauthorized
**Solution:** Check if token is valid and not expired. Re-login to get fresh token.

### Issue: 404 Not Found
**Solution:** Verify endpoints are correct. Check service is running.

### Issue: Prometheus shows no data
**Solution:** Wait 15 seconds for scraping cycle. Check prometheus.yml configuration.

### Issue: Zipkin shows no traces
**Solution:** Ensure ZIPKIN_ENDPOINT environment variable is set correctly. Restart services.

### Issue: Grafana cannot connect to Prometheus
**Solution:** In datasource settings, use `http://prometheus:9090` (container-to-container) not localhost.

---

## 📞 Support

For issues, check:
1. Service logs: `docker-compose logs service-name`
2. Network: `docker network inspect secure-auth-service_default`
3. Database: `mysql -h localhost -u auth_user -pAuth@123Password! -D authdb`

