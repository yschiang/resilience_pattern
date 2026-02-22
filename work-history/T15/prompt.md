# Developer Prompt — T15: Chart values-scenario{1,2,3,4}.yaml

## Context
GitHub issue #17. Read .clinerules/ before starting. Read tmp/T15/plan.md.
DEPENDS ON #16 (T14) being merged (RETRY_ENABLED env var must exist in app-a).

## Task
Create 4 new Helm values files. Add deprecated header to 4 old files.

---

## 1. chart/values-scenario1.yaml (NEW)

```yaml
# Scenario 1 — Baseline: no resilience, no retry
# Failure mode: FAIL_RATE=0.3 (30% RESOURCE_EXHAUSTED)
# Lesson: raw failure propagates end-to-end
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "false"}
    - {name: RETRY_ENABLED,      value: "false"}
appB:
  env:
    - {name: B_DELAY_MS, value: "5"}
    - {name: FAIL_RATE,  value: "0.3"}
```

---

## 2. chart/values-scenario2.yaml (NEW)

```yaml
# Scenario 2 — +Retry+Idempotency
# Failure mode: same FAIL_RATE=0.3
# Lesson: gRPC retry absorbs ~90% of transient errors; also shows retry
#         amplifies load under slow B (anti-pattern lesson follows in S3)
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "false"}
    - {name: RETRY_ENABLED,      value: "true"}
appB:
  env:
    - {name: B_DELAY_MS, value: "5"}
    - {name: FAIL_RATE,  value: "0.3"}
```

---

## 3. chart/values-scenario3.yaml (NEW)

```yaml
# Scenario 3 — +Deadline+Bulkhead+CircuitBreaker
# Failure mode: B_DELAY_MS=200 (slow B triggers overload cascade)
# Lesson: deadline + bulkhead + CB sever the overload cascade; retry without
#         CB would amplify load — this scenario adds the guardrails
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "true"}
    - {name: RETRY_ENABLED,      value: "true"}
    - {name: DEADLINE_MS,        value: "800"}
    - {name: MAX_INFLIGHT,       value: "10"}
    - {name: CHANNEL_POOL_SIZE,  value: "1"}
appB:
  env:
    - {name: B_DELAY_MS, value: "200"}
    - {name: FAIL_RATE,  value: "0.3"}
```

---

## 4. chart/values-scenario4.yaml (NEW)

```yaml
# Scenario 4 — +Keepalive+ChannelPool (+TCP reset fault injection)
# Failure mode: iptables TCP reset injected at t=15s
# Lesson: keepalive detects dead connections; channel pool distributes
#         reconnection load; connection failure self-heals
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "true"}
    - {name: RETRY_ENABLED,      value: "true"}
    - {name: DEADLINE_MS,        value: "800"}
    - {name: MAX_INFLIGHT,       value: "10"}
    - {name: CHANNEL_POOL_SIZE,  value: "4"}
appB:
  env:
    - {name: B_DELAY_MS, value: "5"}
    - {name: FAIL_RATE,  value: "0.3"}
```

---

## 5. Add deprecated header to old values files

Add this comment at the TOP of each old file (before any existing content):

chart/values-baseline.yaml:
```yaml
# DEPRECATED: Use values-scenario1.yaml instead (P5 learning roadmap)
```

chart/values-resilient.yaml:
```yaml
# DEPRECATED: Use values-scenario3.yaml or values-scenario4.yaml instead
```

chart/values-s1.yaml:
```yaml
# DEPRECATED: Use values-scenario1.yaml + values-scenario3.yaml instead
```

chart/values-s4.yaml:
```yaml
# DEPRECATED: Use values-scenario4.yaml instead
```

---

## DoD Proof Commands (save to tmp/T15/proof.txt)
```bash
# Proof 1: 4 new files exist
ls chart/values-scenario{1,2,3,4}.yaml | wc -l
# Expected: 4

# Proof 2: helm lint scenario1 (no errors)
helm lint chart -f chart/values-common.yaml -f chart/values-scenario1.yaml
# Expected: 1 chart linted, 0 chart(s) failed

# Proof 3: helm lint scenario4 (no errors)
helm lint chart -f chart/values-common.yaml -f chart/values-scenario4.yaml
# Expected: 1 chart linted, 0 chart(s) failed
```

## Commit Message (save to tmp/T15/commit_msg.txt)
```
feat: add values-scenario{1,2,3,4}.yaml for learning roadmap

- Four cumulative scenario values files replace baseline/resilient binary
- Old values files kept with deprecated header comments

Fixes #17
```

## After Implementing
Follow 10_workflow_implement_task.md Steps 5-8.
Do NOT merge your own PR. Post proof to issue, wait for architect review.
