# Resilience Pattern Demo — Runbook

Operational guide for running, verifying, and troubleshooting the four resilience scenarios.

---

## Prerequisites

| Requirement | Check |
|---|---|
| kind cluster running | `kubectl cluster-info --context kind-kind` |
| app-a:dev image loaded | `kind load docker-image app-a:dev` |
| app-b:dev image loaded | `kind load docker-image app-b:dev` |
| helm ≥ 3.x | `helm version` |
| kubectl | `kubectl version --client` |

Images must be rebuilt and reloaded after any code change:
```bash
docker build -t app-a:dev -f apps/app-a/Dockerfile .
docker build -t app-b:dev -f apps/app-b/Dockerfile .
kind load docker-image app-a:dev
kind load docker-image app-b:dev
```

---

## Four-Scenario Drill

Run all four scenarios in order. Each takes ~2 minutes.

```bash
./scripts/run_scenario.sh 1   # baseline — raw failure propagates
./scripts/run_scenario.sh 2   # +retry+idempotency
./scripts/run_scenario.sh 3   # +deadline+bulkhead+CB (slow B, C=80)
./scripts/run_scenario.sh 4   # +keepalive+pool (+TCP reset at t=15s)
```

Artifacts are saved to `tmp/artifacts/scenario<N>/`. Each run produces 8 files:
- `app-a-<pod>.prom` × 2 — Prometheus metrics from each A pod
- `app-a-<pod>.log`  × 2 — Application logs from each A pod
- `app-b-<pod>.metrics` × 3 — Go metrics from each B pod
- `fortio.txt` — Load test output

---

## Scenario Reference

### S1 — Downstream Saturation (B_DELAY_MS=200)

B pods respond slowly (200ms each), causing queue buildup at A.

| Metric | Baseline | Resilient |
|---|---|---|
| `a_downstream_latency_ms_seconds_max` | ~4s | ~1s (deadline-capped) |
| `a_breaker_state` | 0 (no breaker) | 1 (OPEN under load) |
| `CIRCUIT_OPEN` errors | 0 | >10,000 |
| `QUEUE_FULL` errors | 0 | >800 |

### S4 — Connection Reset (iptables tcp-reset, 15s)

One A pod has its gRPC connection to B reset for 15s mid-load.

| Metric | Baseline | Resilient |
|---|---|---|
| `UNAVAILABLE` errors (injected pod) | >1,000 | <100 |
| `CIRCUIT_OPEN` errors | 0 | >2,000 |
| `a_channel_pool_size` | N/A | 4 |

---

## Verify Commands

After running scenarios, verify assertions:

```bash
./tests/verify_scenario2.sh
# Expected: PASS=3 FAIL=0, exit 0

./tests/verify_scenario3.sh
# Expected: PASS=2 FAIL=0, exit 0

./tests/verify_scenario4.sh
# Expected: PASS=3 FAIL=0, exit 0
```

Manual spot-checks:
```bash
# Scenario 1: confirm RATE_LIMITED errors propagate
grep 'RATE_LIMITED' tmp/artifacts/scenario1/app-a-*.prom

# Scenario 2: confirm retry absorbed failures
grep 'RATE_LIMITED' tmp/artifacts/scenario2/app-a-*.prom

# Scenario 3: confirm fail-fast patterns fired
grep -E 'QUEUE_FULL|CIRCUIT_OPEN' tmp/artifacts/scenario3/app-a-*.prom

# Scenario 4: confirm UNAVAILABLE then self-heal
grep 'UNAVAILABLE' tmp/artifacts/scenario4/app-a-*.prom
```

---

## Helm Values Reference

| File | Purpose |
|---|---|
| `chart/values-common.yaml` | A=2 pods, B=3 pods (immutable) |
| `chart/values-scenario1.yaml` | Baseline: no resilience, no retry, FAIL_RATE=0.3 |
| `chart/values-scenario2.yaml` | +Retry+Idempotency: RETRY_ENABLED=true |
| `chart/values-scenario3.yaml` | +Deadline+Bulkhead+CB: RESILIENCE_ENABLED=true, B_DELAY_MS=200 |
| `chart/values-scenario4.yaml` | +Keepalive+Pool: CHANNEL_POOL_SIZE=4 |

The scenario runner composes: `values-common.yaml` + `values-scenario<N>.yaml`.

---

## Troubleshooting

**Pods not ready after helm upgrade**
```bash
kubectl rollout restart deployment/app-a -n demo
kubectl rollout status deployment/app-a -n demo --timeout=120s
```

**Old image still running after rebuild**
Kind caches images by digest, not tag. After rebuild:
```bash
kind load docker-image app-a:dev
kubectl rollout restart deployment/app-a -n demo
```

**iptables injection fails (no such file)**
The app-a image must include `iptables` and `iproute2`. The Dockerfile installs them. Rebuild if missing.

**Fortio not found**
`kubectl run fortio/fortio` pulls the image from Docker Hub automatically. Requires internet access from the kind node.

**Namespace missing**
`run_scenario.sh` creates the `demo` namespace automatically via `kubectl apply --dry-run`. No manual action needed.

**verify_scenario*.sh: missing artifact directory**
Run the corresponding scenario first:
```bash
./scripts/run_scenario.sh 1   # required for verify_scenario2.sh and verify_scenario3.sh
./scripts/run_scenario.sh 2   # required for verify_scenario2.sh
./scripts/run_scenario.sh 3   # required for verify_scenario3.sh
./scripts/run_scenario.sh 4   # required for verify_scenario4.sh
```
