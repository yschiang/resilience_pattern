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

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `B_DELAY_MS` | `5` | Simulated work delay per request (ms) |
| `FAIL_RATE` | `0.0` | Fraction of requests to fail with `RESOURCE_EXHAUSTED` (0.0–1.0) |
