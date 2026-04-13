# Prometheus, Zipkin & Grafana - Quick Reference Guide

## 📊 PROMETHEUS - Quick Start

### Access
```
http://localhost:9090
```

### What Prometheus Does
- Collects metrics every 15 seconds
- Stores metrics in time-series database
- Provides query language (PromQL)

---

## Prometheus Queries Cheat Sheet

### 1️⃣ Service Health Status
```promql
up
```
**Returns:** 1 = UP, 0 = DOWN

**Example Response:**
```
up{job="prometheus"}           1
up{job="auth-service"}         1
up{job="policy-service"}       1
up{job="claims-service"}       1
```

---

### 2️⃣ Request Rate (requests/second)
```promql
rate(http_server_requests_seconds_count[5m])
```
**Shows:** How many requests per second

**By Endpoint:**
```promql
rate(http_server_requests_seconds_count[5m]) by (uri)
```

**Example Output:**
```
{uri="/api/auth/login"}        5.2 requests/sec
{uri="/api/policies"}          2.1 requests/sec
{uri="/api/claims"}            1.8 requests/sec
```

---

### 3️⃣ Response Time (Latency) - P95
```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```
**Shows:** 95th percentile response time (95% of requests are faster)

**By Service:**
```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) by (job)
```

**Example Output:**
```
{job="auth-service"}      0.15 seconds (150ms)
{job="policy-service"}    0.35 seconds (350ms)  ← SLOW!
```

---

### 4️⃣ Error Rate (5xx Errors)
```promql
rate(http_server_requests_seconds_count{status=~"5.."}[5m])
```
**Shows:** Number of 500 errors per second

**Percentage Error Rate:**
```promql
(rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m])) * 100
```

**Example Output:**
```
0.05 errors/sec
2.5% error rate
```

---

### 5️⃣ JVM Memory Usage
```promql
jvm_memory_used_bytes{area="heap"}
```
**Shows:** Memory consumed by application (in bytes)

**Convert to GB:**
```promql
jvm_memory_used_bytes{area="heap"} / 1024 / 1024 / 1024
```

**Example Output:**
```
{instance="localhost:8081"}  0.35 GB
{instance="localhost:8082"}  0.42 GB
{instance="localhost:8083"}  0.38 GB
```

---

### 6️⃣ JVM Threads
```promql
jvm_threads_live_count
```
**Shows:** Number of active threads

**Example:**
```
{instance="localhost:8081"}  42 threads
{instance="localhost:8082"}  38 threads
{instance="localhost:8083"}  40 threads
```

---

### 7️⃣ Garbage Collection Time
```promql
rate(jvm_gc_pause_seconds_sum[5m])
```
**Shows:** Time spent in GC per second

**Example:**
```
{instance="localhost:8081"}  0.002 seconds/sec = ~0.2% CPU
```

---

### 8️⃣ HTTP Response Time - P99
```promql
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))
```
**Shows:** 99th percentile (slowest 1% of requests)

---

### 9️⃣ Request Count by Status
```promql
sum(rate(http_server_requests_seconds_count[5m])) by (status)
```
**Shows:** Breakdown of 200s, 401s, 404s, 500s, etc.

---

### 🔟 CPU Usage
```promql
rate(process_cpu_seconds_total[5m]) * 100
```
**Shows:** CPU usage percentage

---

## How to Use Prometheus

### Step-by-Step

1. **Open** http://localhost:9090
2. **Click** the **Graph** tab (default is "Graph")
3. **Paste** query (e.g., `up`)
4. **Click** **Execute** button
5. Choose visualization:
   - **Graph** - Line chart over time
   - **Table** - Current values
   - **Inspect** - Raw JSON data

### Time Range Selection

- `-5m` = Last 5 minutes
- `-1h` = Last 1 hour
- `-24h` = Last 24 hours
- `-7d` = Last 7 days

**Change Time Range:**
- Top right corner dropdown
- Or add to URL: `http://localhost:9090/graph?g0.range_input=5m`

---

## Common Prometheus Scenarios

### Scenario 1: API is slow - Find bottleneck
```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) by (job)
```
Look for highest value = slowest service

### Scenario 2: Error spike - Check error rate
```promql
rate(http_server_requests_seconds_count{status=~"5.."}[5m])
```

### Scenario 3: Memory leak - Check memory trend
```promql
jvm_memory_used_bytes{area="heap"}
```
Memory continuously increasing = leak!

### Scenario 4: High CPU - Find cause
```promql
rate(process_cpu_seconds_total[5m]) * 100
```

---

---

## 🔍 ZIPKIN - Distributed Tracing

### Access
```
http://localhost:9411
```

### What Zipkin Does
- Tracks requests across multiple services
- Shows latency breakdown
- Identifies slowest service

### Sample Trace Flow
```
User Request
   │
   └─→ API Gateway (2ms)
       │
       ├─→ Auth Service (120ms)  ← Check token
       │
       ├─→ Policy Service (180ms)  ← Slow!
       │   │
       │   └─→ PostgreSQL (150ms)
       │
       └─→ Claims Service (90ms)
           │
           └─→ Redis (10ms)

Total: ~392ms
```

---

## Zipkin Interface Guide

### 1️⃣ **Search Tab** - Find Traces

**How to Search:**
1. Click **Search**
2. Select **Service**: `api-gateway` (dropdown)
3. Select **Span Name** (optional): `/api/auth/login`
4. Click **Find Traces**

**Filters Available:**
- Service
- Span Name
- Min Duration
- Max Duration
- Tags (custom filters)

**Example:**
- Service: `policy-service`
- Min Duration: `100ms`
- Shows all policies requests taking >100ms

---

### 2️⃣ **Dependencies Tab** - Service Diagram

Shows how services call each other:

```
┌─────────────┐
│  API Gateway │
└──────┬──────┘
       │
       ├──→ Auth Service
       ├──→ Policy Service
       │    └──→ PostgreSQL
       └──→ Claims Service
            ├──→ Redis
            └──→ Kafka
```

**What it means:**
- If line is RED = Errors in that path
- If line is THICK = High latency

---

### 3️⃣ **Trace Details** - Full Timeline

**Click on any trace to see:**

1. **Timeline View** - Gantt chart of service calls
2. **Service Name** - Which service handled request
3. **Span Duration** - How long that service took
4. **Logs** - Any errors/warnings logged
5. **Tags** - Request metadata (URL, status code, etc.)

**Example Trace:**
```
┌─ API Gateway          [0.0ms  - 2.0ms]   Green ✓
│  └─ Auth Service      [2.0ms  - 122ms]   Green ✓
│     └─ MySQL (query)  [5.0ms  - 120ms]   Green ✓
│
├─ Policy Service       [122ms  - 302ms]   Yellow ⚠️ (Slow)
│  └─ PostgreSQL        [124ms  - 300ms]   Yellow ⚠️
│     └─ Table Scan     [200ms  - 290ms]   Orange 🔴 (SLOW!)
│
└─ Claims Service       [302ms  - 392ms]   Green ✓
   └─ Redis            [305ms  - 315ms]   Green ✓
```

**Analysis:**
- PostgreSQL table scan is the **bottleneck** = Optimize query!

---

## How to Use Zipkin

### Find Slow Requests

1. Open http://localhost:9411/zipkin/
2. Click **Search**
3. Service: `api-gateway`
4. Min Duration: `200ms`
5. Click **Find Traces**
6. Click slowest trace
7. Find the RED/ORANGE segment = **Problem area**

### Compare Two Requests

1. Search for trace 1, note duration & services
2. Search for trace 2 with different parameters
3. Compare timings
4. Which request was faster?

### Monitor API Response Time

1. Go to **Dependencies** tab
2. Look for RED lines = Errors happening
3. Thickness of line = How frequently called
4. Hover over line to see error % and latency

---

## Zipkin Trace Example

**Scenario:** Login takes 400ms instead of expected 100ms

**Steps:**
1. Search: Service=`api-gateway`, span=`/api/auth/login`
2. Find trace with duration > 300ms
3. Click the trace
4. **See breakdown:**
   - API Gateway: 5ms ✓
   - Auth Service: 350ms ⚠️ ← **Problem here!**
     - Database query: 320ms ← **Real problem!**
   - Response: 5ms ✓

**Action:** Optimize Auth Service database queries!

---

---

## 📈 GRAFANA - Dashboard & Visualization

### Access
```
URL: http://localhost:3000
Username: admin
Password: Admin@12345
```

### What Grafana Does
- Creates beautiful dashboards
- Visualizes Prometheus metrics
- Sets up alerts

---

## Initial Setup (First Time)

### Step 1: Add Prometheus Data Source

1. **Log in** to http://localhost:3000
2. Click **Settings** (gear icon, bottom left)
3. Click **Data Sources**
4. Click **Add data source**
5. Select **Prometheus**
6. **URL:** `http://prometheus:9090`
7. Click **Save & Test** → Should see "Data source is working"

### Step 2: Create First Dashboard

1. Click **+ (Create)** → **Dashboard**
2. Click **Add panel**
3. In **Metrics**, type: `up`
4. **Visualization:** Select "Stat" (shows number)
5. Click **Apply**
6. Click **Save** → Name: "Service Health"

---

## Pre-built Dashboard Templates

### Dashboard 1: Service Health Monitoring

**Create these panels:**

#### Panel 1: Service Status (Stat)
```
Query: up
Stat display: Number
Color: Green if 1, Red if 0
```

#### Panel 2: Request Rate (Graph)
```
Query: rate(http_server_requests_seconds_count[5m]) by (job)
Type: Time Series
Legend: Show job names
```

#### Panel 3: Error Rate (Graph)
```
Query: (rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m])) * 100
Type: Time Series
Y-axis: Percent
```

#### Panel 4: Response Time P95 (Graph)
```
Query: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) by (job)
Type: Time Series
Legend: Show job
Y-axis Unit: seconds
```

---

### Dashboard 2: Performance Metrics

#### Panel 1: Memory Usage (Graph)
```
Query: jvm_memory_used_bytes{area="heap"} / 1024 / 1024
Type: Time Series
Y-axis Unit: short (MB)
```

#### Panel 2: JVM Threads (Stat)
```
Query: jvm_threads_live_count
Type: Stat
```

#### Panel 3: GC Pause Time (Graph)
```
Query: rate(jvm_gc_pause_seconds_sum[5m])
Type: Time Series
Y-axis Unit: short
```

#### Panel 4: CPU Usage (Gauge)
```
Query: rate(process_cpu_seconds_total[5m]) * 100
Type: Gauge
Min: 0, Max: 100
```

---

## Grafana Visualization Types

| Type | Best For | Example |
|------|----------|---------|
| **Graph** | Trends over time | Request rate trending up |
| **Stat** | Single number | Current memory usage |
| **Gauge** | Percentage/Ratio | CPU usage 45% |
| **Table** | Multiple values | Requests per endpoint |
| **Heatmap** | Density over time | Error distribution |
| **Alert List** | Firing alerts | Which alerts are active |

---

## Set Up Alerts in Grafana

### Example Alert: High Error Rate

1. Edit a panel
2. Click **Alert** tab (left side)
3. Click **Create Alert**
4. **Condition:**
   ```
   query(A) > 0.01
   (Error rate > 1%)
   ```
5. **Alerting rules:**
   - Evaluate every: `1m`
   - For: `5m` (alert if true for 5 minutes)
6. **Notification channel:** (create one first)
7. Save

### Notification Channels

1. **Settings** → **Notification channels**
2. Click **New Channel**
3. Choose:
   - **Email** - Get email alert
   - **Slack** - Post to Slack channel
   - **Webhook** - Custom integration

**Example Slack Alert:**
```
High error rate detected!
Error Rate: 2.5%
Service: policy-service
Duration: 5 minutes
```

---

## Grafana Dashboard Layout

### Good Dashboard Structure

```
Row 1: Overall Health
├─ Service Status (Stat)
├─ Request Rate (Graph)
├─ Error Rate (Gauge)
└─ Response Time (Graph)

Row 2: Performance
├─ Memory Usage (Graph)
├─ Threads (Stat)
├─ CPU (Gauge)
└─ GC Time (Graph)

Row 3: Business Metrics
├─ Requests per Endpoint (Table)
├─ Error by Service (Graph)
├─ Slowest Endpoints (Table)
└─ Uptime (Stat)
```

---

## Useful Grafana Features

### 1. Templating (Dynamic Variables)

Create dropdown to switch services:

1. Dashboard settings → **Variables**
2. New Variable:
   - Name: `service`
   - Query: `label_values(up, job)`
   - Type: Query
3. Use in queries: `{job="$service"}`

Now you can switch between services!

### 2. Cross-Cutting Time

All panels show same time range when you change time picker.

### 3. Export Dashboard

1. Click dashboard title → **Export**
2. Choose **Export for sharing** or **Save as JSON**
3. Share JSON with team

---

---

## 🎯 Complete Monitoring Workflow

### Daily Monitoring Checklist

1. **Morning - Check Health**
   - Open Grafana
   - Look at service status (all green?)
   - Check error rate (near 0%?)

2. **During Day - Monitor Performance**
   - Watch request rate graph
   - If spike, check Zipkin for slow traces
   - Use Prometheus to find bottleneck

3. **Evening - Review Metrics**
   - Check memory trends (any leaks?)
   - Review error logs
   - Check slowest endpoints table

### Troubleshooting Examples

**Problem: Response time increased from 100ms to 500ms**

**Steps:**
1. Open Prometheus
2. Query: `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) by (job)`
3. Find service with highest value
4. Open Zipkin
5. Search that service for traces with >500ms
6. Click trace
7. Find RED/ORANGE segment
8. That's your bottleneck!

**Problem: Error rate is 5%**

**Steps:**
1. Open Prometheus
2. Query: `rate(http_server_requests_seconds_count{status=~"5.."}[5m])`
3. Find which service has errors
4. Open Zipkin
5. Search that service
6. Click error trace
7. Read log message
8. Fix the error!

---

## Quick Command Reference

| Task | Where | How |
|------|-------|-----|
| **View metrics** | Prometheus (9090) | Graph tab → Type query |
| **Find slow request** | Zipkin (9411) | Search tab → Min duration |
| **Create dashboard** | Grafana (3000) | Create → Dashboard → Add panel |
| **Set alert** | Grafana (3000) | Edit panel → Alert tab |
| **Check service health** | Prometheus (9090) | Query: `up` |

---

## 📞 Support Quick Links

- **Prometheus Docs:** https://prometheus.io/docs/prometheus/latest/querying/basics/
- **Zipkin UI Guide:** https://zipkin.io/pages/quickstart.html
- **Grafana Docs:** https://grafana.com/docs/grafana/latest/

