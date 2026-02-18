# Architect Review — PR #20 (T11 + T12)

**Date:** 2026-02-19
**Commits:** fe24565 (README, mine) + d886a0a (T11) + 5c987b5 (T12)
**Files under review:** verify_s1.sh, verify_s4.sh, docs/runbook.md

---

## Gate Checks

| Gate | Result | Notes |
|---|---|---|
| Conventional commits | ✅ | `feat:` (T11), `docs:` (T12) |
| No Co-authored-by | ✅ | Both commits clean |
| Closes #13, #14 referenced | ✅ | Both in commit bodies + PR |
| DoD all pass | ✅ | PASS=2/2 (S1), PASS=3/3 (S4), exit 0 |
| Architecture constants unchanged | ✅ | Scripts are read-only, no config changes |
| work-history convention followed | ✅ | T11/ and T12/ plan + review saved |

---

## Code Review

### `tests/verify_s1.sh` — PASS

- `set -euo pipefail` ✅
- Guard clauses for missing artifact dirs with actionable error message ✅
- C01 float comparison via `awk "BEGIN { exit !($BASE_MAX > $RES_MAX) }"` — correct pattern for float comparison in bash (shell `[[ ]]` cannot handle floats) ✅
- C01 field extraction `awk '{print $2}'` — correct for Prometheus text format: metric_name{labels} VALUE ✅
- C02 uses `-E` flag on second grep for `QUEUE_FULL|CIRCUIT_OPEN` alternation ✅
- `PASS=$((PASS + 1))` arithmetic expansion — works correctly in bash ✅
- Final `[[ "$FAIL" -eq 0 ]]` exits with implicit 0/1 — clean, no redundant `exit` call ✅

### `tests/verify_s4.sh` — PASS

- Same guard/structure pattern as verify_s1.sh — consistent ✅
- `sum_unavailable()` helper function — clean extraction, reduces repetition ✅
- `grep 'code="UNAVAILABLE"'` — literal match, no `-E` needed ✅
- `awk '{sum += $2} END {print int(sum+0)}'` — `int()` handles float values from Prometheus correctly ✅
- All three assertions (C05/C06/C07) are independent — correct, one failing doesn't skip others ✅

### `docs/runbook.md` — PASS

- Prerequisites section covers kind, docker, kubectl, helm ✅
- 4-command drill is correct and matches run_scenario.sh interface ✅
- Evidence tables use real numbers from actual runs ✅
- Verify commands match actual script paths (tests/verify_*.sh) ✅
- Troubleshooting covers the three most likely failure modes: image pull, NET_ADMIN, fortio stuck ✅

---

## Verdict

**APPROVED.** All gates pass. The `awk BEGIN` float comparison in C01 is the correct technique — a subtle but important detail that would have caused silent failures with integer `[[ ]]` comparison. Guard clauses and actionable error messages make the scripts safe to run in CI.

**Post-merge actions:**
1. Move `tmp/T11/` → `work-history/T11/`
2. Close P4 milestone (all issues closed: #13, #14)
3. Project complete
