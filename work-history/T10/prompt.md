# Developer Prompt — Phase 0 + T10 (S4 Fault Injection)

## Context

- Repo: `resilience-pattern`, branch `master`
- Cluster: kind `resilience-pattern`, namespace `demo`
- Pods currently: A=2 (ResilientBClient active), B=3
- Open issues: #11 (T10), #12 (T09C), #13 (T11), #14 (T12)
- Artifacts state:
  - `tmp/artifacts/S1/resilient/` — 8 files (done)
  - `tmp/artifacts/S1/baseline/` — MISSING (needs re-run)
  - `tmp/artifacts/S4/` — does not exist yet

---

## Task A — Re-collect S1 Baseline Artifacts (no code change)

S1 baseline artifacts were lost during a path refactor. Re-run the existing script:

```bash
./scripts/run_scenario.sh S1 baseline
```

**DoD:**
```bash
ls tmp/artifacts/S1/baseline/ | wc -l
# Expected: 8
```

No commit needed — artifacts are gitignored under `tmp/`.

---

## Task B — T10: S4 Fault Injection (issue #11)

Two deliverables:
1. `scripts/inject_s4.sh` — standalone injection script
2. Modify `scripts/run_scenario.sh` — S4 branch with background fortio + injection hook

---

### Deliverable 1: `scripts/inject_s4.sh`

**Spec:**
- Args: `<pod-name> [namespace]` (namespace defaults to `demo`)
- Primary method: `iptables -A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset`
- Fallback method: `tc qdisc add dev eth0 root netem loss 100%`
- Try iptables first; if `kubectl exec` fails (exit non-zero), fall back to tc
- Print `"Injection started (iptables)"` or `"Injection started (tc netem)"`
- Sleep 15 seconds
- Remove the rule (matching method)
- Print `"Injection removed"`
- Exit 0 on success

**Skeleton:**

```bash
#!/bin/bash
set -euo pipefail

POD="${1:-}"
NS="${2:-demo}"

if [[ -z "$POD" ]]; then
    echo "Usage: $0 <pod-name> [namespace]"
    exit 1
fi

IPTABLES_RULE="-A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset"

if kubectl exec "$POD" -n "$NS" -- iptables $IPTABLES_RULE 2>/dev/null; then
    echo "Injection started (iptables): pod=$POD"
    sleep 15
    kubectl exec "$POD" -n "$NS" -- iptables ${IPTABLES_RULE/-A/-D}
    echo "Injection removed (iptables): pod=$POD"
else
    echo "iptables unavailable, falling back to tc netem: pod=$POD"
    kubectl exec "$POD" -n "$NS" -- tc qdisc add dev eth0 root netem loss 100%
    echo "Injection started (tc netem): pod=$POD"
    sleep 15
    kubectl exec "$POD" -n "$NS" -- tc qdisc del dev eth0 root
    echo "Injection removed (tc netem): pod=$POD"
fi
```

Make executable: `chmod +x scripts/inject_s4.sh`

---

### Deliverable 2: Modify `scripts/run_scenario.sh` — S4 branch

**Current flow** (both S1 and S4): deploy → wait → run fortio (synchronous, 60s) → collect artifacts.

**S4 requires:** deploy → wait → run fortio **in background** → sleep 15s → inject fault (via inject_s4.sh, which internally sleeps 15s and removes) → wait for fortio → collect artifacts.

**Changes to make in `run_scenario.sh`:**

1. **Per-scenario load parameters** — S4 uses `c=50`, not `c=80`:
   ```bash
   if [[ "$SCENARIO" == "S1" ]]; then
       FORTIO_QPS=200; FORTIO_C=80; FORTIO_T=60s
   elif [[ "$SCENARIO" == "S4" ]]; then
       FORTIO_QPS=200; FORTIO_C=50; FORTIO_T=60s
   fi
   ```

2. **S4 fortio + injection orchestration** — replace the single `kubectl run fortio-load` block with a conditional:
   ```bash
   if [[ "$SCENARIO" == "S4" ]]; then
       echo "==> Running S4: fortio in background, fault injection at t=15s..."
       # Run fortio as background process; use </dev/null to avoid stdin hang
       kubectl run fortio-load --rm -i --restart=Never --image=fortio/fortio -n "$NAMESPACE" -- \
           load -qps "$FORTIO_QPS" -c "$FORTIO_C" -t "$FORTIO_T" -timeout 2s \
           http://$A_SERVICE:8080/api/work \
           </dev/null > "$ARTIFACTS_DIR/fortio.txt" 2>&1 &
       FORTIO_PID=$!

       # Wait for load to warm up, then inject at t=15s
       sleep 15
       A_POD=$(kubectl get pods -l app=app-a -o jsonpath='{.items[0].metadata.name}' -n "$NAMESPACE")
       echo "==> Injecting fault into A pod: $A_POD"
       "$SCRIPT_DIR/inject_s4.sh" "$A_POD" "$NAMESPACE"

       # Wait for fortio to finish
       echo "==> Waiting for fortio to complete..."
       wait $FORTIO_PID || true
   else
       # S1: synchronous (existing behavior)
       echo "==> Running fortio load test (qps=$FORTIO_QPS, c=$FORTIO_C, t=$FORTIO_T)..."
       kubectl run fortio-load --rm -i --restart=Never --image=fortio/fortio -n "$NAMESPACE" -- \
           load -qps "$FORTIO_QPS" -c "$FORTIO_C" -t "$FORTIO_T" -timeout 2s \
           http://$A_SERVICE:8080/api/work \
           > "$ARTIFACTS_DIR/fortio.txt" 2>&1 || true
   fi
   ```

**Important:** The existing hardcoded `load -qps 200 -c 80 -t 60s` line must be replaced entirely by the conditional above.

---

### DoD for T10 (issue #11)

Proof commands (copy-paste in order):

```bash
# 1. Script exists and is executable
ls -la scripts/inject_s4.sh
# Expected: -rwxr-xr-x ... scripts/inject_s4.sh

# 2. Standalone injection test (pick any running A pod)
A_POD=$(kubectl get pods -l app=app-a -o jsonpath='{.items[0].metadata.name}' -n demo)
echo "Testing inject on: $A_POD"
./scripts/inject_s4.sh "$A_POD"
# Expected output contains: "Injection started" ... "Injection removed"

# 3. Check pod logs show connection errors during injection window
kubectl logs "$A_POD" -n demo | grep -c -i "UNAVAILABLE\|connection\|reset"
# Expected: number > 0 (there should be errors logged during injection)

# 4. S4 baseline scenario runs end-to-end
./scripts/run_scenario.sh S4 baseline
ls tmp/artifacts/S4/baseline/ | wc -l
# Expected: 8
```

---

### Commit

After Task B is done (both inject_s4.sh and run_scenario.sh changes), commit as:

```
feat: Add S4 fault injection (inject_s4.sh + scenario hook)

- scripts/inject_s4.sh: iptables tcp-reset injection with tc netem fallback
  - targets one A pod, sleeps 15s, self-removes
  - prints "Injection started/removed" for grep-able log
- scripts/run_scenario.sh: S4 branch runs fortio in background
  - per-scenario concurrency: S1=c80, S4=c50 (per plan spec)
  - S4: fortio background + sleep 15s + inject_s4.sh + wait

Closes #11
```

Save to `tmp/T10_COMMIT_MSG` before committing.

---

## After T10 — T09C (issue #12, unblocked)

Once T10 DoD is met, immediately run:

```bash
./scripts/run_scenario.sh S4 baseline
./scripts/run_scenario.sh S4 resilient
```

Both must produce 8 artifacts each. That closes #12. No code changes — script-only.

---

## After T09C — Hand off to Architect for PR review

Create a PR with:
- `scripts/inject_s4.sh` (new file)
- `scripts/run_scenario.sh` (modified)

Title: `feat: Add S4 fault injection and scenario runner hook`
Closes: #11

T09C artifact collection does not need a PR (artifacts are gitignored).

---

## Invariants (DO NOT CHANGE)

- A=2 pods, B=3 pods (never scale)
- Namespace: `demo`
- No `Co-authored-by:` in commits
- Conventional commit prefix required (`feat:`, `fix:`, etc.)
