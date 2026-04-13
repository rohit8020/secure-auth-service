# 🚀 Complete Testing & Monitoring Guide - INDEX

Welcome! You now have **complete documentation** for testing and monitoring the Insurance Microservices Platform. Here's what you have:

---

## 📚 All Documentation Files

### 1. **API_TEST_CURLS.md** ⭐
**What:** Complete CURL commands for all microservices endpoints

**Contains:**
- Auth Service endpoints (8 commands)
- Policy Service endpoints (6 commands)
- Claims Service endpoints (6 commands)
- API Gateway routes
- Monitoring & infrastructure commands
- Complete testing workflow examples
- Database access commands

**Use this for:**
- Quick copy-paste CURL commands
- Terminal/CLI testing
- Understanding all available endpoints

---

### 2. **POSTMAN_GUIDE.md** ⭐⭐ (RECOMMENDED FOR POSTMAN)
**What:** Complete step-by-step guide for using Postman

**Contains:**
- How to import CURL into Postman
- How to create Postman collections
- Environment variable setup
- All 25+ endpoints with full Postman configuration
- Pre-request and test scripts
- Complete testing workflow
- Common issues & solutions
- Prometheus, Zipkin, Grafana integration in Postman

**Use this for:**
- **Postman users** - Most comprehensive guide
- Setting up variables and auto-capturing tokens
- Understanding request/response flow
- Monitoring tool integration

---

### 3. **Insurance_Microservices.postman_collection.json** ⭐⭐⭐ (QUICK IMPORT)
**What:** Postman collection ready to import

**Contains:**
- All 25 API endpoints pre-configured
- All environment variables set up
- All folders and organization included
- Test scripts for auto-capturing tokens and IDs
- Monitoring tool endpoints

**How to Use:**
1. Open Postman
2. Click **Import**
3. Select **File**
4. Choose `Insurance_Microservices.postman_collection.json`
5. Done! All endpoints ready to test

---

### 4. **MONITORING_TOOLS_GUIDE.md** ⭐⭐ (PROMETHEUS, ZIPKIN, GRAFANA)
**What:** Complete guide for all monitoring tools

**Contains:**
- **Prometheus:** PromQL queries cheat sheet (30+ queries)
- **Zipkin:** How to find slow requests, trace visualization
- **Grafana:** Dashboard creation, alert setup, visualization types
- Real-world troubleshooting scenarios
- Step-by-step guides for each tool
- Common monitoring workflows

**Use this for:**
- Setting up Prometheus queries
- Finding slow requests with Zipkin
- Creating Grafana dashboards
- Understanding performance metrics
- Setting up alerts

---

## 🎯 Quick Start Path

### For Postman Users (Recommended):

1. **Read:** POSTMAN_GUIDE.md (first 30 minutes)
2. **Import:** Insurance_Microservices.postman_collection.json
3. **Start Testing:** Follow the "Testing Workflow" section
4. **Monitor:** Use MONITORING_TOOLS_GUIDE.md for Prometheus/Zipkin/Grafana

### For Terminal/CLI Users:

1. **Read:** API_TEST_CURLS.md
2. **Copy-Paste:** Any CURL command and run in terminal
3. **Monitor:** Use MONITORING_TOOLS_GUIDE.md

### For Monitoring Setup:

1. **Read:** MONITORING_TOOLS_GUIDE.md
2. **Follow:** Step-by-step guides for each tool
3. **Create:** Dashboards, alerts, traces

---

## 📋 Service URLs & Access

| Service | URL | Purpose | Auth Required |
|---------|-----|---------|-----------------|
| **API Gateway** | http://localhost:8080 | Main entry point | Yes (most endpoints) |
| **Auth Service** | http://localhost:8081 | User authentication | No (auth endpoints) |
| **Policy Service** | http://localhost:8082 | Policy management | Yes |
| **Claims Service** | http://localhost:8083 | Claims management | Yes |
| **Prometheus** | http://localhost:9090 | Metrics database | No |
| **Grafana** | http://localhost:3000 | Dashboards | Yes (admin/Admin@12345) |
| **Zipkin** | http://localhost:9411 | Distributed traces | No |

---

## 🔑 Key Concepts

### Environment Variables (in Postman)

```javascript
// Auto-set after login
{{token}}           // JWT token
{{refresh_token}}   // Refresh token

// Auto-set after operations
{{policy_id}}       // Created policy ID
{{claim_id}}        // Created claim ID

// Base URLs
{{base_url}}        // http://localhost:8080
{{auth_service_url}}    // http://localhost:8081
{{policy_service_url}}  // http://localhost:8082
{{claims_service_url}}  // http://localhost:8083
```

### Important Headers

```
Content-Type: application/json
Authorization: Bearer {{token}}
Idempotency-Key: {{$timestamp}}-policy  // For POST endpoints
```

---

## 📊 Monitoring Tools Overview

### Prometheus (9090)
- **Collects:** Application metrics every 15 seconds
- **Stores:** Time-series data
- **Use for:** Performance metrics, error rate, resource usage
- **Best Query:** `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))`

### Zipkin (9411)
- **Tracks:** Request flow across services
- **Shows:** Service latency breakdown
- **Use for:** Finding bottlenecks, slow endpoints
- **Example:** Auth service took 120ms, Policy service took 300ms ← slow!

### Grafana (3000)
- **Visualizes:** Prometheus metrics in dashboards
- **Creates:** Beautiful graphs and alerts
- **Use for:** Monitoring dashboard, setting up alerts, team visibility
- **Credentials:** admin / Admin@12345

---

## 🧪 Testing Workflow

### Complete Flow (Start to Finish)

```
1. Register User
   ↓
2. Login (capture token)
   ↓
3. Create Policy (capture policy_id)
   ↓
4. View Policies
   ↓
5. Create Claim (using policy_id)
   ↓
6. Approve Claim
   ↓
7. Check logs in Zipkin
   ↓
8. Review metrics in Prometheus
   ↓
9. Create dashboard in Grafana
```

---

## 💡 Pro Tips

### Postman Pro Tips

1. **Auto-capture values:** Use Test Scripts to save IDs to variables
2. **Reuse across requests:** Use `{{variable_name}}` in URLs/bodies
3. **Organize requests:** Use folders (Auth, Policy, Claims, Monitoring)
4. **Tests validation:** Add tests to verify responses
5. **Generate reports:** Export test run results

### Prometheus Pro Tips

1. **Always add time range:** Most queries need `[5m]` or `[1h]`
2. **Use rate():** Shows trends better than raw counts
3. **Group by:** Use `by (job)` to separate by service
4. **Combine queries:** Use `()` to combine conditions

### Zipkin Pro Tips

1. **Search by duration:** Find slow requests easily
2. **Look for RED traces:** Errors stand out visually
3. **Export traces:** Click export to share with team
4. **Tags:** Filter by custom tags in search

### Grafana Pro Tips

1. **Use variables:** Create dropdowns for service selection
2. **Templating:** `$__range` = current time range selected
3. **Alert thresholds:** Set realistic limits (e.g., 5ms for latency)
4. **Shared dashboards:** Export as JSON and share with team

---

## 🔍 Common Workflows

### Workflow 1: Test Single Endpoint

**In Postman:**
1. Open collection
2. Click endpoint (e.g., "Get Health")
3. Click **Send**
4. View Response tab

**In CLI:**
```bash
curl -X GET http://localhost:8081/auth/health
```

---

### Workflow 2: Complete User Journey

**In Postman:**
1. Run **Register User** → Saves token to `{{token}}`
2. Run **Login** → Updates token
3. Run **Issue Policy** → Saves policy_id to `{{policy_id}}`
4. Run **Submit Claim** → Saves claim_id to `{{claim_id}}`
5. Run **Approve Claim** → Uses claim_id

All IDs automatically captured! ✨

---

### Workflow 3: Find Performance Bottleneck

**Prometheus:**
1. Query: `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) by (job)`
2. Find highest value = slowest service

**Zipkin:**
1. Search: Service = the slow service
2. Min Duration: > 1 second
3. Click trace
4. Find RED/ORANGE segment = problem

**Grafana:**
1. Create dashboard with P95 latency
2. Alert if > 500ms
3. Get notified when slow

---

## 📞 If Something Doesn't Work

### Check These First

1. **Services running?** `docker-compose ps`
   - All should show "Up"

2. **Can connect to service?** 
   ```bash
   curl -v http://localhost:8081/auth/health
   ```

3. **Check service logs:**
   ```bash
   docker-compose logs auth-service --tail=50
   ```

4. **Database connected?**
   ```bash
   mysql -h localhost -u auth_user -pAuth@123Password! -D authdb
   ```

5. **Prometheus scraping?**
   - Go to http://localhost:9090/targets
   - All should be "UP"

---

## 📖 Documentation Sequence

### If you have 30 minutes:
1. Read POSTMAN_GUIDE.md (Postman setup)
2. Import Insurance_Microservices.postman_collection.json
3. Run 5 requests to verify setup

### If you have 1 hour:
1. POSTMAN_GUIDE.md (complete workflow)
2. Insurance_Microservices.postman_collection.json (import & test)
3. Run full testing workflow (Register → Policy → Claim → Approve)

### If you have 2 hours:
1. POSTMAN_GUIDE.md (complete read)
2. Insurance_Microservices.postman_collection.json (import & test)
3. MONITORING_TOOLS_GUIDE.md (Prometheus basics - 20 min)
4. Create first Grafana dashboard (20 min)
5. Find a slow request using Zipkin (20 min)

### If you have 4+ hours:
1. Read all guides completely
2. Set up complete Postman workflows with all validations
3. Master Prometheus PromQL queries
4. Create comprehensive Grafana dashboards
5. Set up alerts and notifications
6. Do complete end-to-end testing and monitoring

---

## 📁 File Structure

```
secure-auth-service/
├── API_TEST_CURLS.md                              ← Basic CURL commands
├── POSTMAN_GUIDE.md                               ← Postman best guide!
├── Insurance_Microservices.postman_collection.json ← Import this!
├── MONITORING_TOOLS_GUIDE.md                      ← Prometheus/Zipkin/Grafana
├── README.md (this file)
├── docker-compose.yml                             ← Container setup
├── pom.xml                                        ← Maven config
└── services/
    ├── auth-service/
    ├── policy-service/
    ├── claims-service/
    └── api-gateway/
```

---

## 🎉 You're All Set!

You have:
- ✅ Complete CURL commands for all endpoints
- ✅ Postman collection ready to import
- ✅ Step-by-step Postman guide
- ✅ Prometheus/Zipkin/Grafana setup guide
- ✅ Real-world monitoring scenarios
- ✅ Troubleshooting guides

**Next Step:** 
1. Open **POSTMAN_GUIDE.md**
2. Import **Insurance_Microservices.postman_collection.json**
3. Start testing! 🚀

---

## 🤔 Quick Questions?

**Q: Which file should I use first?**
A: Start with **POSTMAN_GUIDE.md** if using Postman. Use **API_TEST_CURLS.md** for CLI.

**Q: How do I set up monitoring?**
A: Follow **MONITORING_TOOLS_GUIDE.md** - it has step-by-step instructions.

**Q: How do I find slow requests?**
A: Use **Zipkin** (http://localhost:9411) - search for traces and check timeline.

**Q: How do I create dashboards?**
A: Use **Grafana** (http://localhost:3000) - follow the "Pre-built Dashboard" section in **MONITORING_TOOLS_GUIDE.md**.

**Q: Why is response time slow?**
A: Check **Prometheus** queries to identify bottleneck service, then use **Zipkin** to find the slow operation.

---

**Happy Testing! 🎯**

