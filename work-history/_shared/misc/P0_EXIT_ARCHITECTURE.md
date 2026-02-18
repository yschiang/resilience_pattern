# P0 Exit: Baseline Architecture Documentation

**Status:** P0 Milestone Complete (PR #15 merged)
**Date:** 2026-02-16
**Commit:** a04a67a (P0 Milestone: Baseline E2E Running)

---

## Executive Summary

P0 milestone established a working baseline E2E system with:
- **Service A**: Spring Boot REST API (2 pods) calling downstream gRPC service
- **Service B**: Go gRPC service (3 pods, single-threaded) with configurable delay
- **Infrastructure**: Helm charts, Kubernetes manifests, values overlays
- **No resilience patterns**: Baseline for measuring impact of future patterns

This document serves as the architectural reference for the baseline system.

---

## Table of Contents

1. [System Architecture](#system-architecture)
2. [Messaging Flow](#messaging-flow)
3. [Component Details](#component-details)
4. [Network Topology](#network-topology)
5. [Configuration](#configuration)
6. [Proto Contract](#proto-contract)
7. [Metrics & Observability](#metrics--observability)
8. [Deployment Architecture](#deployment-architecture)
9. [Load Testing Setup](#load-testing-setup)
10. [Architecture Invariants](#architecture-invariants)
11. [P0 Deliverables](#p0-deliverables)
12. [Known Limitations](#known-limitations)

---

## System Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────────┐
│                     External Load Testing                        │
│                    (Fortio - not in P0)                          │
└────────────────────────┬────────────────────────────────────────┘
                         │ HTTP GET
                         │ /api/work
                         ▼
        ┌────────────────────────────────────────┐
        │      Kubernetes Service: a-service     │
        │         ClusterIP:8080 (HTTP)          │
        └────────────┬───────────────┬───────────┘
                     │               │
                     ▼               ▼
        ┌─────────────────┐  ┌─────────────────┐
        │   app-a-pod-0   │  │   app-a-pod-1   │
        │  Spring Boot    │  │  Spring Boot    │
        │  Port 8080      │  │  Port 8080      │
        └────────┬────────┘  └────────┬─────────┘
                 │                    │
                 │ gRPC               │ gRPC
                 │ DemoService.Work   │ DemoService.Work
                 ▼                    ▼
        ┌────────────────────────────────────────┐
        │      Kubernetes Service: b-service     │
        │   ClusterIP:50051 (gRPC), 8080 (HTTP) │
        └────┬──────────┬──────────┬─────────────┘
             │          │          │
             ▼          ▼          ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ app-b-0  │ │ app-b-1  │ │ app-b-2  │
    │   Go     │ │   Go     │ │   Go     │
    │ gRPC:    │ │ gRPC:    │ │ gRPC:    │
    │  50051   │ │  50051   │ │  50051   │
    │ Metrics: │ │ Metrics: │ │ Metrics: │
    │  8080    │ │  8080    │ │  8080    │
    └──────────┘ └──────────┘ └──────────┘
```

### Technology Stack

| Component | Technology | Version/Details |
|-----------|-----------|-----------------|
| **Service A** | Spring Boot | Java 17, Spring Boot 3.x |
| **Service A RPC Client** | gRPC Java | Generated from proto |
| **Service B** | Go | Go 1.21+ |
| **Service B RPC Server** | gRPC Go | Generated from proto |
| **Container Runtime** | Docker | Multi-stage builds |
| **Orchestration** | Kubernetes (kind) | Local cluster: resilience-pattern |
| **Package Manager** | Helm 3.x | Chart: resilience-demo v0.1.0 |
| **Proto Compiler** | protoc | v3+ |

---

## Messaging Flow

### Request Path: External → A → B

#### 1. External Request to Service A (REST)

```http
GET /api/work HTTP/1.1
Host: a-service:8080
```

**Handler:** `WorkController.java:20`
```java
@GetMapping("/api/work")
public WorkResponse work() {
    String requestId = UUID.randomUUID().toString();
    BClient.WorkResult result = bClient.callWork(requestId);
    return new WorkResponse(result.isOk(), result.getCode(), result.getLatencyMs());
}
```

#### 2. Service A → Service B (gRPC)

**Client:** `BClient.java:58`
```java
WorkRequest request = WorkRequest.newBuilder()
    .setId(requestId)
    .build();
WorkReply reply = blockingStub.work(request);
```

**Proto Call:**
```protobuf
service DemoService {
  rpc Work(WorkRequest) returns (WorkReply);
}
```

**Target:** `b-service:50051` (Kubernetes service DNS)

#### 3. Service B Processing (Go)

**Handler:** `main.go:33`
```go
func (s *server) Work(ctx context.Context, req *pb.WorkRequest) (*pb.WorkReply, error) {
    workerMutex.Lock()         // Single-thread enforcement
    defer workerMutex.Unlock()

    time.Sleep(time.Duration(delayMS) * time.Millisecond)

    return &pb.WorkReply{
        Ok:        true,
        Code:      "SUCCESS",
        LatencyMs: latency,
    }, nil
}
```

#### 4. Response Path: B → A → External

**Service B returns:**
```json
{
  "ok": true,
  "code": "SUCCESS",
  "latency_ms": 5
}
```

**Service A transforms and returns:**
```json
{
  "ok": true,
  "code": "SUCCESS",
  "latencyMs": 12
}
```

---

## Component Details

### Service A (app-a)

**Purpose:** REST API gateway that calls downstream gRPC service B

**Technology:**
- Framework: Spring Boot 3.x
- Language: Java 17
- Dependencies: spring-boot-starter-web, grpc-java, spring-boot-actuator

**Ports:**
- `8080`: HTTP REST API + Actuator endpoints

**Endpoints:**
- `GET /api/work` — Main work endpoint (calls B)
- `GET /actuator/health` — Health check
- `GET /actuator/prometheus` — Metrics (Micrometer)

**Key Classes:**
- `WorkController` — REST controller exposing /api/work
- `BClient` — gRPC client for calling service B
- `ErrorCode` — Semantic error taxonomy (P1 feature)
- `MetricsService` — Metrics collection (P1 feature)

**Configuration:**
- `b.service.url`: Target B service (default: `b-service:50051`)
- `resilience.enabled`: Feature flag (default: `false` in baseline)

**Replicas:** 2 (IMMUTABLE)

**Resource Limits:** Not set in P0 (to be defined in P1+)

---

### Service B (app-b)

**Purpose:** Single-threaded gRPC service simulating backend work with configurable delay

**Technology:**
- Language: Go 1.21+
- Framework: google.golang.org/grpc
- Concurrency: Mutex-enforced single-threaded processing

**Ports:**
- `50051`: gRPC service (DemoService)
- `8080`: HTTP metrics endpoint

**Endpoints:**
- `DemoService.Work(WorkRequest)` — gRPC work RPC
- `GET /metrics` — Prometheus-style metrics (custom format)

**Key Features:**
- **Single-threaded:** `workerMutex` ensures only 1 request processed at a time
- **Configurable delay:** `B_DELAY_MS` environment variable (default: 5ms)
- **Metrics:** `b_busy` (gauge), `b_requests_total` (counter)

**Configuration:**
- `B_DELAY_MS`: Processing delay in milliseconds (NOT `DELAY_MS`)

**Replicas:** 3 (IMMUTABLE)

**Resource Limits:** Not set in P0

---

## Network Topology

### Kubernetes Services

#### a-service (Service A)
```yaml
apiVersion: v1
kind: Service
metadata:
  name: a-service
spec:
  type: ClusterIP
  ports:
  - port: 8080
    targetPort: 8080
    protocol: TCP
  selector:
    app: app-a
```

**DNS:** `a-service.demo.svc.cluster.local` (short: `a-service`)

#### b-service (Service B)
```yaml
apiVersion: v1
kind: Service
metadata:
  name: b-service
spec:
  type: ClusterIP
  ports:
  - name: grpc
    port: 50051
    targetPort: 50051
  - name: metrics
    port: 8080
    targetPort: 8080
  selector:
    app: app-b
```

**DNS:** `b-service.demo.svc.cluster.local` (short: `b-service`)

### Load Balancing

- **A → B gRPC calls:** Kubernetes service load balancing (round-robin by default)
- **External → A:** Kubernetes service load balancing
- **No service mesh:** Plain Kubernetes services (no Istio/Linkerd in P0)

---

## Configuration

### Helm Chart Structure

```
chart/
├── Chart.yaml                  # Chart metadata (v0.1.0)
├── values-common.yaml          # Architecture invariants (A=2, B=3, ports)
├── values-baseline.yaml        # Baseline config (resilience disabled)
├── values-s1.yaml              # S1 scenario config (B_DELAY_MS=200)
├── values-s4.yaml              # S4 scenario config (B_DELAY_MS=5)
└── templates/
    ├── app-a-deployment.yaml   # A deployment (2 replicas)
    ├── app-a-service.yaml      # A service (ClusterIP:8080)
    ├── app-b-deployment.yaml   # B deployment (3 replicas)
    ├── app-b-service.yaml      # B service (ClusterIP:50051,8080)
    └── namespace.yaml          # demo namespace
```

### Values Hierarchy

**Common values (values-common.yaml):**
```yaml
appA:
  replicas: 2              # IMMUTABLE
  service:
    port: 8080

appB:
  replicas: 3              # IMMUTABLE
  service:
    grpcPort: 50051        # IMMUTABLE
    metricsPort: 8080      # IMMUTABLE
```

**Baseline values (values-baseline.yaml):**
```yaml
resilience:
  enabled: false           # No resilience patterns

appB:
  env:
    - name: B_DELAY_MS     # NOT DELAY_MS
      value: "5"           # 5ms processing time
```

**Scenario-specific values:**
- `values-s1.yaml`: B_DELAY_MS=200 (slow backend scenario)
- `values-s4.yaml`: B_DELAY_MS=5 (fast baseline scenario)

### Deployment Command

```bash
# Deploy baseline configuration
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-baseline.yaml \
  --namespace demo \
  --create-namespace
```

---

## Proto Contract

### File: proto/demo.proto

```protobuf
syntax = "proto3";

package demo.v1;

option go_package = "app-b/gen";
option java_package = "com.demo.grpc";
option java_multiple_files = true;

service DemoService {
  rpc Work(WorkRequest) returns (WorkReply);
}

message WorkRequest {
  string id = 1;
}

message WorkReply {
  bool ok = 1;
  string code = 2;
  int64 latency_ms = 3;
}
```

### Contract Semantics

**WorkRequest:**
- `id`: Unique request identifier (UUID generated by A)

**WorkReply:**
- `ok`: Success flag (true/false)
- `code`: Semantic result code (e.g., "SUCCESS", error codes in P1+)
- `latency_ms`: Server-side processing latency

**RPC Characteristics:**
- **Synchronous:** Blocking call from A to B
- **Timeout:** Not set in baseline (P1 will add deadline)
- **Retry:** None in baseline (P1 will add resilience)
- **Load balancing:** Kubernetes service (round-robin)

---

## Metrics & Observability

### Service A Metrics (Spring Boot Actuator)

**Endpoint:** `GET http://a-service:8080/actuator/prometheus`

**Key Metrics (Baseline P0):**
- `http_server_requests_seconds_*` — HTTP request duration
- `jvm_*` — JVM metrics (heap, threads, GC)
- `system_*` — System metrics (CPU, load)

**Future Metrics (P1):**
- `a_downstream_latency_ms{quantile="0.95|0.99"}` — Downstream call latency
- `a_downstream_errors_total{code="..."}` — Error counts by semantic code
- `a_downstream_inflight` — Current inflight requests to B
- `a_breaker_state` — Circuit breaker state (0=closed, 1=open, 2=half-open)

### Service B Metrics (Custom Go Exporter)

**Endpoint:** `GET http://b-service:8080/metrics`

**Current Metrics (P0):**
```prometheus
# HELP b_busy Whether worker is currently busy (0 or 1)
# TYPE b_busy gauge
b_busy 0

# HELP b_requests_total Total number of requests processed
# TYPE b_requests_total counter
b_requests_total 42
```

**Metric Semantics:**
- `b_busy`: Instantaneous worker state (0=idle, 1=processing)
- `b_requests_total`: Cumulative request count since pod start

---

## Deployment Architecture

### Kubernetes Cluster

**Cluster Name:** `resilience-pattern`
**Provider:** kind (Kubernetes in Docker)
**Namespace:** `demo`
**Network Plugin:** Default (kindnet)

### Pod Distribution

**Service A:**
- Pod count: 2
- Naming: `app-a-<replica-hash>-<pod-hash>`
- Anti-affinity: Not configured (default scheduling)

**Service B:**
- Pod count: 3
- Naming: `app-b-<replica-hash>-<pod-hash>`
- Anti-affinity: Not configured

### Resource Requests/Limits

**Not configured in P0.** All pods use node-default resources.

**Future consideration (P1+):**
- Set explicit CPU/memory requests
- Set limits to prevent resource exhaustion
- Consider QoS class (Guaranteed vs Burstable)

---

## Load Testing Setup

### Fortio Configuration (P1 - Not in P0)

**Tool:** Fortio (https://github.com/fortio/fortio)
**Target:** `http://a-service:8080/api/work`

**S1 Scenario (Slow Backend):**
```bash
fortio load \
  -qps 200 \
  -c 80 \
  -t 60s \
  -timeout 2s \
  http://a-service:8080/api/work
```

**Parameters:**
- QPS: 200 requests/second
- Concurrency: 80 parallel connections
- Duration: 60 seconds (+ 10s warmup)
- Timeout: 2 seconds per request

**Expected Behavior (Baseline):**
- No resilience patterns → timeouts/errors expected in S1
- B_DELAY_MS=200 causes queueing at B (single-threaded)
- A has no deadline/timeout enforcement → waits indefinitely

---

## Architecture Invariants

### IMMUTABLE Constants

These values MUST NOT change across all milestones (P0-P4):

| Constant | Value | Rationale |
|----------|-------|-----------|
| **A pods** | 2 | Demonstrates client-side load distribution |
| **B pods** | 3 | Demonstrates server-side load balancing |
| **B gRPC port** | 50051 | Standard gRPC port convention |
| **B metrics port** | 8080 | Standard HTTP metrics port |
| **B env var** | B_DELAY_MS | Semantic clarity (delay is on B side) |
| **A HTTP port** | 8080 | Standard Spring Boot port |
| **Namespace** | demo | Isolation from other workloads |
| **Cluster name** | resilience-pattern | Consistent local environment |

### Verification Commands

```bash
# Verify A=2 pods
kubectl get pods -n demo -l app=app-a | grep Running | wc -l
# Expected: 2

# Verify B=3 pods
kubectl get pods -n demo -l app=app-b | grep Running | wc -l
# Expected: 3

# Verify B gRPC port
kubectl get svc b-service -n demo -o jsonpath='{.spec.ports[?(@.name=="grpc")].port}'
# Expected: 50051

# Verify B_DELAY_MS env var (not DELAY_MS)
kubectl get deployment app-b -n demo -o yaml | grep -A1 B_DELAY_MS
# Expected:
#   - name: B_DELAY_MS
#     value: "5"
```

---

## P0 Deliverables

### Code Artifacts

```
apps/
├── app-a/                      # Spring Boot REST API
│   ├── Dockerfile              # Multi-stage build
│   ├── pom.xml                 # Maven dependencies
│   └── src/main/java/com/demo/appa/
│       ├── Application.java
│       ├── WorkController.java # REST endpoint
│       ├── BClient.java        # gRPC client
│       ├── ErrorCode.java      # Semantic errors
│       └── MetricsService.java # Metrics collection
├── app-b/                      # Go gRPC service
│   ├── Dockerfile              # Multi-stage build
│   ├── go.mod                  # Go dependencies
│   ├── main.go                 # gRPC server + metrics
│   └── gen/                    # Generated proto code
│       └── demo.pb.go
├── proto/
│   └── demo.proto              # gRPC contract
└── chart/                      # Helm chart
    ├── Chart.yaml
    ├── values-*.yaml           # 4 overlays
    └── templates/              # K8S manifests
```

### Documentation

- `README.md` — Project overview, quick start
- `docs/plan.md` — Master plan (P0-P4 roadmap)
- `docs/execution_order.md` — Task sequence (T01→T06→T02→T07→T08)
- `docs/P0_EXIT_ARCHITECTURE.md` — **This document**
- `.clinerules/` — Developer guidance (6 files)

### Quality Gates Passed

✅ All P0 issues closed (#1-5)
✅ PR #15 merged (squash commit a04a67a)
✅ All commits follow conventional format
✅ No Co-authored-by lines
✅ All DoD proofs passed and documented
✅ Architecture constants verified
✅ README accuracy confirmed (Go not C++)

---

## Known Limitations

### Baseline System (By Design)

1. **No resilience patterns:**
   - No deadlines/timeouts on A→B calls
   - No inflight request limiting (bulkhead)
   - No circuit breaker
   - No retries
   - No fallback responses

2. **No distributed tracing:**
   - No correlation IDs propagated
   - No OpenTelemetry instrumentation
   - Manual request ID generation only

3. **Basic metrics:**
   - A: Spring Boot Actuator defaults only (P1 adds semantic metrics)
   - B: Custom simple metrics (busy, requests_total)

4. **No resource limits:**
   - Pods can consume unbounded CPU/memory
   - Risk of node resource exhaustion under load

5. **No health/readiness probes:**
   - Kubernetes uses default behavior
   - No graceful shutdown handling

### Expected Failures in S1 Scenario

When B_DELAY_MS=200 (slow backend):
- **High latency:** A waits for B without timeout
- **Queue buildup:** B processes 1 request at a time (5 req/sec max)
- **Client timeouts:** Fortio 2s timeout may trigger
- **Cascading delay:** A threads blocked waiting for B

**This is intentional** — baseline establishes the problem that P1-P3 resilience patterns will solve.

---

## Next Steps (P1 Milestone)

### T03: Metrics & Semantic Error Taxonomy
- Implement semantic error codes in A (SUCCESS, DEADLINE_EXCEEDED, UNAVAILABLE, QUEUE_FULL, CIRCUIT_OPEN, UNKNOWN)
- Add Micrometer metrics: latency p95/p99, error counts, inflight gauge, breaker state
- Export via `/actuator/prometheus`

### T09A: Scenario Runner S1 Baseline
- Create `scripts/run_scenario.sh` (S1/S4, baseline/resilient modes)
- Run Fortio load against A (qps=200, c=80, duration=60s)
- Collect 8 artifacts: 1 fortio + 2 A prom + 2 A log + 3 B metrics
- Establish baseline evidence for S1 scenario

**P1 Goal:** Gather evidence of baseline system behavior under stress (S1 slow backend scenario).

---

## Appendix: File Reference

### Key Configuration Files

| File | Purpose | Key Values |
|------|---------|------------|
| `chart/values-common.yaml` | Architecture invariants | A=2, B=3, ports |
| `chart/values-baseline.yaml` | Baseline config | resilience.enabled=false, B_DELAY_MS=5 |
| `chart/values-s1.yaml` | S1 scenario | B_DELAY_MS=200 (slow) |
| `chart/values-s4.yaml` | S4 scenario | B_DELAY_MS=5 (fast) |
| `proto/demo.proto` | gRPC contract | DemoService.Work RPC |
| `.clinerules/00_global_invariants.md` | Immutable constants | Full verification commands |

### Key Source Files

| File | LOC | Purpose |
|------|-----|---------|
| `apps/app-a/src/main/java/com/demo/appa/WorkController.java` | 58 | REST endpoint |
| `apps/app-a/src/main/java/com/demo/appa/BClient.java` | 131 | gRPC client |
| `apps/app-b/main.go` | ~120 | gRPC server + metrics |

---

**Document Version:** 1.0
**Last Updated:** 2026-02-16
**Maintained By:** Architect Agent
**Status:** P0 Complete, P1 Ready

---

**End of P0 Exit Architecture Documentation**
