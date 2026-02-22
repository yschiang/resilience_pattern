# T15 Architect Plan — Chart: values-scenario{1,2,3,4}.yaml

## Problem
The current chart has values-baseline.yaml and values-resilient.yaml (binary).
The learning roadmap needs 4 scenario-specific values files, each building
cumulatively on the previous, to drive different env var combinations.

## Design Decisions

### Scenario values mapping
| Scenario | RESILIENCE_ENABLED | RETRY_ENABLED | B_DELAY_MS | FAIL_RATE | CHANNEL_POOL_SIZE |
|---|---|---|---|---|---|
| 1 | false | false | 5 | 0.3 | (default) |
| 2 | false | true | 5 | 0.3 | (default) |
| 3 | true | true | 200 | 0.3 | 1 |
| 4 | true | true | 5 | 0.3 | 4 |

### Scenario 3: B_DELAY_MS=200
- Simulates slow B to trigger overload cascade
- Requires higher concurrency in run_scenario.sh (C=80 vs C=50)
- CHANNEL_POOL_SIZE=1: keepalive is there but pool is minimal (lesson: pool doesn't help with overload)
- CB + bulkhead in ResilientBClient will shed load and protect A

### Scenario 4: CHANNEL_POOL_SIZE=4 + B_DELAY_MS=5
- B is fast again (reset from S3)
- iptables TCP reset injected at t=15s
- CHANNEL_POOL_SIZE=4 means 4 channels, keepalive on each → self-heals faster
- Lesson: connection pooling + keepalive recovers from TCP reset

### DEADLINE_MS and MAX_INFLIGHT in Scenario 3+4
- Scenario 3: DEADLINE_MS=800, MAX_INFLIGHT=10
- Scenario 4: DEADLINE_MS=800, MAX_INFLIGHT=10 (same)

### Backward compatibility
- Keep values-baseline.yaml, values-resilient.yaml, values-s1.yaml, values-s4.yaml
- Add deprecation comment at top of each old file

### Helm template check
- Both app-a-deployment.yaml and app-b-deployment.yaml already use
  {{- range .Values.appA.env }} loops — no template changes needed.
- FAIL_RATE and RETRY_ENABLED will flow through cleanly.

## Files Changed
- chart/values-scenario1.yaml (new)
- chart/values-scenario2.yaml (new)
- chart/values-scenario3.yaml (new)
- chart/values-scenario4.yaml (new)
- chart/values-baseline.yaml (add deprecated comment)
- chart/values-resilient.yaml (add deprecated comment)
- chart/values-s1.yaml (add deprecated comment)
- chart/values-s4.yaml (add deprecated comment)

## DoD
1. ls chart/values-scenario{1,2,3,4}.yaml | wc -l → 4
2. helm lint chart -f chart/values-common.yaml -f chart/values-scenario1.yaml → 0 errors
3. helm lint chart -f chart/values-common.yaml -f chart/values-scenario4.yaml → 0 errors
