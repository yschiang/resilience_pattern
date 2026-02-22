# Workstream — Retry Gating by Retryable / Non-Retryable Classification (App A gRPC Client)

## Objective
Implement **retry behavior gating** in App A using the existing classifier output from the observability workstream:
- Retry only when `retryable=true`
- Do not retry when `retryable=false`

This workstream upgrades retry behavior from static retry conditions to a **classification-aware retry policy** while preserving the teaching intent of the scenarios.

---

## Scope (In)

### 1. Enforce Retry Gating in App A (client-side)
Use the existing classifier as the single decision source for retry behavior.

Target behavior:
- **Retryable errors** → retry allowed (subject to existing max attempts / backoff)
- **Non-retryable errors** → no retry (fail fast / propagate immediately)

This should apply to the retry-enabled code path(s) used in Scenario 2 (and any shared resilient client path if applicable).

### 2. Retry Policy v1 (based on current taxonomy)
Use the current taxonomy (do not rename enums in this workstream).

Recommended policy (v1):
- `CONNECTION_FAILURE` → retryable = true
- `TIMEOUT` → retryable = false (avoid overload amplification)
- `BACKEND_ERROR` → retryable = false by default
  - NOTE: if Scenario 2 requires retry on specific transient backend errors, preserve that behavior only when the classifier (or call context) can positively identify a transient/retryable subtype.
- `CLIENT_ERROR` → retryable = false
- `SERVER_ERROR` → retryable = false (conservative)
- `CIRCUIT_OPEN` → retryable = false
- `BULKHEAD_REJECTED` → retryable = false
- `UNKNOWN` → retryable = false

### 3. Preserve Scenario Teaching Intent (Important)
This repo is a teaching demo. Scenario 2 intentionally demonstrates retry effects.

If Scenario 2 depends on retries for a transient gRPC status (e.g., `RESOURCE_EXHAUSTED`) and the current taxonomy maps that into a broader category like `BACKEND_ERROR`, this workstream must preserve Scenario 2 behavior using a minimal mechanism **without taxonomy redesign**.

Acceptable approaches:
- **Option A (preferred):** use classifier `CallContext` / original gRPC status metadata in the retry gate
- **Option B:** classifier returns an internal `retry_hint` (not a metrics label)
- **Option C:** keep a targeted exception/status predicate, but wrap it so the final decision still respects classifier output

Rules:
- **Do not break Scenario 2 teaching flow unintentionally**
- **Do prevent retries for protection signals** (`CIRCUIT_OPEN`, `BULKHEAD_REJECTED`)
- **Unknowns default to non-retryable**

### 4. Observability Validation (reuse existing metrics)
No new metrics contract is required in this workstream.
Reuse existing metrics and dashboards to validate behavior changes:
- `grpc_client_requests_total{reason,retryable}`
- client latency histogram
- existing resilience metrics (CB/Bulkhead), if present

Expected visible outcomes:
- Fewer retries on non-retryable reasons
- Clearer separation between backend failures vs protection rejections

---

## Scope (Out)
- No experiment harness (`run.json`, artifacts, verdict automation)
- No taxonomy enum redesign (unless a tiny compatibility fix is unavoidable)
- No major refactor of App A client architecture
- No App B changes (unless absolutely required for compatibility)
- No PromQL redesign (reuse existing panels; only add small notes if needed)

---

## Design Principles
1. **Single decision source**: retry gating must use classifier output (directly or via an adapter).
2. **Minimal behavior change**: preserve existing scenario behavior except where gating intentionally blocks retries.
3. **Protection signals are terminal**: `CIRCUIT_OPEN` and `BULKHEAD_REJECTED` are always non-retryable.
4. **Safe default**: unknowns are non-retryable.
5. **Test-first on decision matrix**: cover mapping + retry gate outcomes with unit tests.

---

## Implementation Notes (Expected)
Introduce a retry gate adapter (name TBD), for example:
- `RetryDecisionPolicy`
- `RetryGate`

Inputs may include:
- `Throwable`
- classifier result (`reason`, `retryable`)
- optional call context / original gRPC status

Outputs:
- `shouldRetry` boolean
- (optional) debug reason for logs/tests only

Potential integration points:
- gRPC retry predicate wrapper
- Resilience4j retry predicate (if used)
- App A resilient client call path where exceptions are handled

---

## Verification (Human-run)

### Functional verification
1. Run Scenario 2 (retry) using current scripts.
2. Confirm retries still happen for intended transient failures (teaching scenario preserved).
3. Confirm no retry occurs for:
   - `CIRCUIT_OPEN`
   - `BULKHEAD_REJECTED`
   - `TIMEOUT` (if generated in scenario)
4. Run Scenario 3 (failfast).
5. Confirm protection events are visible and non-retryable.

### Observability verification
Use the existing dashboard / PromQL panels:
- Non-retryable reasons appear without hidden retry amplification
- Protection reasons (`CIRCUIT_OPEN`, `BULKHEAD_REJECTED`) are visible and not treated as backend retries
- Error split remains interpretable

---

## Definition of Done (DoD)
- Retry gating is enforced via classifier-based decision flow in App A
- Unit tests cover retry/no-retry decision matrix
- Scenario 2 still demonstrates retry behavior intentionally (no accidental regression)
- Scenario 3 protection signals are never retried
- No taxonomy/metrics contract regression from the observability workstream