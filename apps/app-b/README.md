# Service B

Go gRPC backend intentionally single-threaded via a mutex to demonstrate saturation and queueing behavior.

## Metrics

| Metric | Type | Description |
|---|---|---|
| `b_requests_received_total` | Counter | All requests entering `Work()` handler (cache hits, fail injection, normal) |
| `b_requests_started_total` | Counter | Requests that acquired the worker mutex and entered the single-thread section |
| `b_requests_completed_total` | Counter | Successful completions (work done inside mutex-protected section) |
| `b_requests_failed_total{reason}` | Counter | Failures by reason (`reason="fail_injection"`) |
| `b_busy` | Gauge | Worker utilization: 1 while holding mutex, 0 otherwise |
| `b_requests_total` | Counter | Legacy: total requests processed (inside mutex, same as started) |
| `b_fail_rate` | Gauge | Configured failure injection rate (from `FAIL_RATE` env var) |

## Metric Relationships

These invariants always hold:

```
received_total >= started_total + failed_total
started_total  >= completed_total
```

Derived quantities:
- **Queue depth** (waiting for mutex): `received - started - failed`
- **Worker utilization**: `b_busy` (1=busy, 0=idle)

## Cache-Hit Decision

Cache hits increment `received_total` but **NOT** `started_total` or `completed_total`.

Rationale: no actual work is performed for a cache hit — the handler returns immediately before acquiring the mutex. Counting cache hits as completions would inflate throughput numbers and obscure true worker utilization.

## Latency Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `b_request_latency_ms` | Histogram | `outcome` | End-to-end handler latency (ms) — all code paths |
| `b_processing_latency_ms` | Histogram | `outcome` | Worker processing latency (ms) — mutex holders only |
| `b_queue_wait_ms` | Histogram | `outcome` | Queue wait time (ms) — handler entry to mutex acquisition |

### Histogram Buckets

`b_request_latency_ms` and `b_processing_latency_ms`: `1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000` ms

`b_queue_wait_ms`: `0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000` ms

### Outcome Labels

| `outcome` | Code path | Request latency | Processing latency | Queue wait |
|---|---|---|---|---|
| `success` | Normal processing | ✅ | ✅ | ✅ |
| `failure` | Fail injection (before mutex) | ✅ | ❌ | ❌ |
| `cache_hit` | Idempotency cache (before mutex) | ✅ | ❌ | ❌ |

### Latency Relationships

For requests that enter the worker:
```
b_request_latency_ms ≈ b_queue_wait_ms + b_processing_latency_ms
```

For early-return paths (cache hit, fail injection): only `b_request_latency_ms` is observed.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `B_DELAY_MS` | `5` | Simulated work delay per request (ms) |
| `FAIL_RATE` | `0.0` | Fraction of requests to fail with `RESOURCE_EXHAUSTED` (0.0–1.0) |
