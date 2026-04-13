# ⚡ QUICK REFERENCE CARD - Testing & Monitoring

## 🎯 Where to Start?

```
POSTMAN USERS?
└─→ Read: POSTMAN_GUIDE.md (20 min)
└─→ Import: Insurance_Microservices.postman_collection.json
└─→ Start: Testing Workflow section

CLI USERS?
└─→ Read: API_TEST_CURLS.md (10 min)
└─→ Copy-Paste: Any CURL command
└─→ Test: curl http://localhost:8081/auth/health

WANT MONITORING?
└─→ Read: MONITORING_TOOLS_GUIDE.md (30 min)
└─→ Create: Prometheus queries
└─→ Visualize: Grafana dashboard
└─→ Trace: Zipkin traces
```

---

## 📋 All 25 Endpoints at a Glance

### Auth Service (9 endpoints)
```
1. GET  /auth/health                    ✅ Public
2. POST /api/auth/register              ✓ Register user
3. POST /api/auth/login                 ✓ Get token
4. GET  /api/auth/me                    ✓ Current user
5. POST /api/auth/refresh               ✓ Refresh token
6. POST /auth/admin/users               ✓ Create admin user
7. GET  /auth/admin/users               ✓ List all users
8. GET  /oauth2/jwks                    ✅ Public key
9. POST /oauth2/token                   ✓ Client credentials
```

### Policy Service (6 endpoints)
```
10. GET  /api/policies                           ✓ List
11. POST /api/policies (Idempotency-Key)         ✓ Create
12. GET  /api/policies/{policyId}                ✓ Get one
13. POST /api/policies/{policyId}/renew          ✓ Renew
14. POST /api/policies/{policyId}/lapse          ✓ Lapse
15. GET  /internal/policies/{policyId}/projection ✅ Internal
```

### Claims Service (6 endpoints)
```
16. GET  /api/claims                                 ✓ List
17. POST /api/claims (Idempotency-Key)               ✓ Submit
18. GET  /api/claims/{claimId}                       ✓ Get one
19. POST /api/claims/{claimId}/verify                ✓ Verify
20. POST /api/claims/{claimId}/approve               ✓ Approve
21. POST /api/claims/{claimId}/reject                ✓ Reject
```

### Monitoring (4 endpoints)
```
22. Prometheus    http://localhost:9090    View metrics
23. Grafana       http://localhost:3000    Create dashboards
24. Zipkin        http://localhost:9411    Find slow requests
25. Health Check  /api/auth/health         Check all services
```

---

## 🚀 3-Step Quick Test

### Step 1: Register
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "SecurePass@123"
  }'
```
**Copy the `token` from response**

### Step 2: Create Policy
```bash
curl -X POST http://localhost:8080/api/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN_FROM_STEP1" \
  -H "Idempotency-Key: unique-key-123" \
  -d '{
    "policyNumber": "POL-001",
    "policyType": "HOME",
    "coverageAmount": 500000,
    "startDate": "2026-04-14",
    "endDate": "2027-04-14",
    "premiumAmount": 2500.00
  }'
```
**Copy the `policyId` from response**

### Step 3: Submit Claim
```bash
curl -X POST http://localhost:8080/api/claims \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN_FROM_STEP1" \
  -H "Idempotency-Key: unique-claim-key-123" \
  -d '{
    "policyId": "POLICY_ID_FROM_STEP2",
    "claimType": "FIRE_DAMAGE",
    "claimAmount": 50000.00,
    "description": "House fire damage",
    "incidentDate": "2026-04-08T10:30:00Z"
  }'
```

**Done! You've created a complete workflow! ✨**

---

## 🎁 5-Minute Postman Setup

1. Open Postman
2. Click **Import**
3. Select **File**
4. Pick `Insurance_Microservices.postman_collection.json`
5. ✅ Done!

All endpoints are ready, organized in folders, with scripts that auto-save tokens!

---

## 📊 Monitoring Tools Cheat Sheet

| Tool | URL | What It Does | Key Feature |
|------|-----|--------------|-------------|
| **Prometheus** | http://localhost:9090 | Collects metrics | `up` → See if service is running |
| **Grafana** | http://localhost:3000 | Makes dashboards | Visual graphs of metrics |
| **Zipkin** | http://localhost:9411 | Traces requests | Find slow service! |

### Quick Prometheus Queries

```promql
up                                  → Service status (1=up, 0=down)

rate(http_server_requests_seconds_count[5m])
                                    → Requests per second

histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
                                    → 95% of requests are faster than X

rate(http_server_requests_seconds_count{status=~"5.."}[5m])
                                    → Error rate (5xx errors)

jvm_memory_used_bytes{area="heap"} / 1024 / 1024
                                    → Memory usage in MB
```

### Quick Zipkin Steps

1. Open http://localhost:9411
2. Click **Search**
3. Select **Service** dropdown
4. Click **Find Traces**
5. Click any trace to see breakdown

---

## 🔧 Postman Environment Variables

**Auto-magically filled after running requests:**

```javascript
{{token}}           ← Login/Register
{{refresh_token}}   ← Login/Register
{{policy_id}}       ← Issue Policy
{{claim_id}}        ← Submit Claim
{{base_url}}        ← http://localhost:8080
{{timestamp}}       ← Auto-generated timestamp
```

---

## ⚙️ Essential Headers

### For Authenticated Requests
```
Authorization: Bearer {{token}}
```

### For Create/Update Requests
```
Content-Type: application/json
```

### For Idempotent Requests (POST policies/claims)
```
Idempotency-Key: {{$timestamp}}-policy
```

---

## 🆘 Troubleshooting 1-2-3

### Service not running?
```bash
docker-compose ps
```
Look for "Up" status

### Service not responding?
```bash
docker-compose logs auth-service --tail=20
```
Check error messages

### Database issue?
```bash
docker-compose logs mysql --tail=20
```
or
```bash
docker-compose logs postgres --tail=20
```

---

## 📖 File Purposes

| File | Purpose | Read Time |
|------|---------|-----------|
| **API_TEST_CURLS.md** | All CURL commands | 5 min |
| **POSTMAN_GUIDE.md** | Postman setup & use | 20 min |
| **Insurance_Microservices.postman_collection.json** | Ready-to-import collection | 1 min to import |
| **MONITORING_TOOLS_GUIDE.md** | Prometheus/Zipkin/Grafana | 30 min |
| **README_TESTING_GUIDE.md** | Navigation guide | 5 min |
| **QUICK_REFERENCE_CARD.md** | This file! | 2 min |

---

## 🎯 One-Page Workflow

```
┌─────────────────────────────────────────────────┐
│ 1. REGISTER / LOGIN                             │
│    POST /api/auth/register                      │
│    ↓ Copy token                                 │
├─────────────────────────────────────────────────┤
│ 2. CREATE POLICY                                │
│    POST /api/policies + Idempotency-Key         │
│    Authorization: Bearer {token}                │
│    ↓ Copy policyId                              │
├─────────────────────────────────────────────────┤
│ 3. CREATE CLAIM                                 │
│    POST /api/claims + Idempotency-Key           │
│    Authorization: Bearer {token}                │
│    policyId: {policyId from step 2}             │
│    ↓ Copy claimId                               │
├─────────────────────────────────────────────────┤
│ 4. MANAGE CLAIM                                 │
│    POST /api/claims/{claimId}/verify            │
│    POST /api/claims/{claimId}/approve           │
│    POST /api/claims/{claimId}/reject            │
├─────────────────────────────────────────────────┤
│ 5. MONITOR                                      │
│    Prometheus: http://localhost:9090            │
│    Grafana: http://localhost:3000               │
│    Zipkin: http://localhost:9411                │
└─────────────────────────────────────────────────┘
```

---

## 🚀 Next Actions

- [ ] Read POSTMAN_GUIDE.md
- [ ] Import Insurance_Microservices.postman_collection.json
- [ ] Run Register request
- [ ] Run Login request (token auto-saved!)
- [ ] Run Issue Policy request (policy_id auto-saved!)
- [ ] Run Submit Claim request
- [ ] Check health endpoint
- [ ] View Prometheus metrics
- [ ] Check Zipkin trace
- [ ] Create Grafana dashboard
- [ ] Set up alert

---

## 💻 Copy-Paste Available

**All endpoints have:**
- ✅ Full CURL commands (API_TEST_CURLS.md)
- ✅ Postman instructions (POSTMAN_GUIDE.md)
- ✅ Postman JSON ready to import (Insurance_Microservices.postman_collection.json)
- ✅ Monitoring setup (MONITORING_TOOLS_GUIDE.md)

**Just pick your tool and go!**

---

**Happy Testing! 🎉**

