# T17 Architect Plan — Tests: verify_scenario{2,3,4}.sh

## Problem
Old verify_s1.sh and verify_s4.sh reference old artifact paths (S1/baseline,
S4/resilient) and old error code labels (QUEUE_FULL for RESOURCE_EXHAUSTED).
New verify scripts must reference scenario<N> artifact paths and use the new
RATE_LIMITED error code label from T14.

## Design Decisions

### verify_scenario2.sh — Retry lesson
Compares Scenario 1 artifacts vs Scenario 2 artifacts.
- C08: Scenario 1 RATE_LIMITED errors > 1000 (without retry: every ~30% fails)
- C09: Scenario 2 RATE_LIMITED errors < 100 (with retry: gRPC retries absorb ~90%)
- C10: S1 RATE_LIMITED > S2 RATE_LIMITED (directional improvement)

Artifact paths:
- S1 dir: tmp/artifacts/scenario1/
- S2 dir: tmp/artifacts/scenario2/

Prerequisite: BOTH scenario 1 AND scenario 2 must have been run first.
The script checks for both dirs and fails fast with instructions if missing.

### verify_scenario3.sh — Deadline/Bulkhead/CB lesson
Adapted from verify_s1.sh (same conceptual checks, new paths).
- C01: Scenario 1 max latency > Scenario 3 max latency (CB sheds load → lower p99)
  Source: a_downstream_latency_ms_seconds_max across app-a-*.prom
- C02: Scenario 3 QUEUE_FULL + CIRCUIT_OPEN > 100 (fail-fast patterns firing)

Artifact paths:
- S1 dir: tmp/artifacts/scenario1/
- S3 dir: tmp/artifacts/scenario3/

### verify_scenario4.sh — Keepalive/Pool lesson
Adapted from verify_s4.sh (same logic, new paths, new label).
- C05: Scenario 3 UNAVAILABLE > 100 (TCP reset without full resilience = chaos)
  Note: We compare S3 (baseline for connection) vs S4 (with keepalive+pool)
  Actually: compare a run without keepalive vs with keepalive.
  Simplest: Scenario 1 has no keepalive. Scenario 4 has keepalive+pool.
  C05: S1 UNAVAILABLE after fault > 100 (but S1 doesn't inject faults...)

  REVISED: Use Scenario 3 (RESILIENCE_ENABLED=true, CHANNEL_POOL_SIZE=1)
  vs Scenario 4 (RESILIENCE_ENABLED=true, CHANNEL_POOL_SIZE=4).
  Both inject iptables reset. But run_scenario.sh only injects for Scenario 4.

  FINAL DESIGN: verify_scenario4.sh checks Scenario 4 alone:
  - C05: Scenario 4 total UNAVAILABLE > 100 (fault injection caused errors)
  - C06: Scenario 4 UNAVAILABLE after fault window (t>15s) decreases (self-heal)
    This is hard to measure from final snapshots. Use total UNAVAILABLE instead.
  - C07: Scenario 4 SUCCESS count > 1000 (traffic continues after fault — self-heal confirmed)

  Actually the simplest approach matching the original S4 pattern:
  We need a "before keepalive" and "after keepalive" comparison.
  For the new roadmap: use Scenario 1 (no keepalive) + Scenario 4 (keepalive+pool)
  both with TCP fault injected. But Scenario 1 doesn't inject faults...

  DECISION: Match the spirit of verify_s4.sh — compare S3 vs S4 UNAVAILABLE:
  - S3: RESILIENCE_ENABLED=true, CHANNEL_POOL_SIZE=1, no fault injection in run_scenario3
  - S4: RESILIENCE_ENABLED=true, CHANNEL_POOL_SIZE=4, fault injected
  Problem: S3 has no fault → UNAVAILABLE ≈ 0.

  FINAL FINAL: Keep the same structure as old verify_s4.sh but use new paths.
  The "baseline" for TCP faults is running scenario4 without keepalive (not supported
  in current architecture). So verify_scenario4.sh will:
  - C05: S4 total UNAVAILABLE > 50 (fault was injected, errors occurred)
  - C06: S4 SUCCESS count > 5000 (most traffic succeeded, self-heal worked)
  - C07: S4 UNAVAILABLE < S1 UNAVAILABLE (directional: keepalive heals faster)
    Use S1 as proxy for "no resilience" baseline (but S1 has no TCP fault either)

  SIMPLEST SOUND DESIGN: Stick to single-scenario assertions for S4:
  - C05: S4 UNAVAILABLE > 100 (errors visible during fault window)
  - C06: S4 SUCCESS > 10000 (self-heal occurred, majority succeeded)
  - These can be verified with just Scenario 4 artifacts.

### Deprecated headers
Add deprecation header comment to verify_s1.sh and verify_s4.sh:
"DEPRECATED: Use verify_scenario3.sh and verify_scenario4.sh instead"

## Files Changed
- tests/verify_scenario2.sh (new)
- tests/verify_scenario3.sh (new)
- tests/verify_scenario4.sh (new)
- tests/verify_s1.sh (add deprecated header comment only)
- tests/verify_s4.sh (add deprecated header comment only)

## DoD
1. ls tests/verify_scenario{2,3,4}.sh | wc -l → 3
2. bash -n tests/verify_scenario2.sh → no errors
3. bash -n tests/verify_scenario3.sh → no errors
4. bash -n tests/verify_scenario4.sh → no errors
