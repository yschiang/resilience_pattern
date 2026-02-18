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
./scripts/run_scenario.sh S1 baseline
./scripts/run_scenario.sh S1 resilient
./scripts/run_scenario.sh S4 baseline
./scripts/run_scenario.sh S4 resilient
```

Artifacts are saved to `tmp/artifacts/<scenario>/<mode>/`. Each run produces 8 files:
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

After running all four scenarios:

```bash
./tests/verify_s1.sh
# Expected: PASS=2 FAIL=0, exit 0

./tests/verify_s4.sh
# Expected: PASS=3 FAIL=0, exit 0
```

Manual spot-checks:
```bash
# S1: confirm baseline max latency
grep '^a_downstream_latency_ms_seconds_max' tmp/artifacts/S1/baseline/app-a-*.prom

# S1: confirm resilient fast-fail fired
grep -E 'QUEUE_FULL|CIRCUIT_OPEN' tmp/artifacts/S1/resilient/app-a-*.prom

# S4: confirm UNAVAILABLE counts
grep 'UNAVAILABLE' tmp/artifacts/S4/baseline/app-a-*.prom
grep 'UNAVAILABLE' tmp/artifacts/S4/resilient/app-a-*.prom
```

---

## Helm Values Reference

| File | Purpose |
|---|---|
| `chart/values-common.yaml` | A=2 pods, B=3 pods (immutable) |
| `chart/values-baseline.yaml` | `RESILIENCE_ENABLED=false` |
| `chart/values-resilient.yaml` | `RESILIENCE_ENABLED=true`, `DEADLINE_MS=800`, `MAX_INFLIGHT=10`, `CHANNEL_POOL_SIZE=4` |
| `chart/values-s1.yaml` | `B_DELAY_MS=200` |
| `chart/values-s4.yaml` | `B_DELAY_MS=5` (normal delay; fault injected by script) |

The scenario runner composes: `values-common` + `values-<mode>` + `values-<scenario>`.

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

**verify_s1.sh / verify_s4.sh: missing artifact directory**
Run the corresponding scenario first:
```bash
./scripts/run_scenario.sh S1 baseline   # required for verify_s1.sh
./scripts/run_scenario.sh S1 resilient  # required for verify_s1.sh
./scripts/run_scenario.sh S4 baseline   # required for verify_s4.sh
./scripts/run_scenario.sh S4 resilient  # required for verify_s4.sh
```
