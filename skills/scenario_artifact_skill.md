# skills/scenario_artifact_skill.md

## Purpose
Make demo runs **reproducible and evidence-driven**:
- One command runs a scenario (S1 or S4) in a mode (baseline/resilient)
- Produces `artifacts/<scenario>/<mode>/...` with logs/metrics/load output

## Inputs
- Scenario: `S1` or `S4`
- Mode: `baseline` or `resilient`
- Namespace: `demo`
- Required endpoints:
  - A: `/api/work`, `/actuator/prometheus`
  - B: `/metrics`
- Load tool: Fortio (recommended via `fortio/fortio` pod)

## Outputs
- `scripts/run_scenario.sh`
- Artifacts folder:
  - `artifacts/S1/baseline/*`
  - `artifacts/S1/resilient/*`
  - `artifacts/S4/baseline/*`
  - `artifacts/S4/resilient/*`

Minimum artifact set per run:
- `fortio.txt`
- `a-<pod>.prom`
- `a-<pod>.log`
- `b-<pod>.metrics`

## Steps
1) Deploy correct values overlays for scenario+mode.
2) Ensure loadgen exists (create fortio pod if missing).
3) Run load for configured duration/concurrency.
4) Collect:
   - A prometheus from each A pod (exec curl localhost)
   - A logs from each A pod
   - B /metrics from each B pod
5) Save all under artifacts path.

## DoD (Proof commands + Expected)

### Proof commands
```bash
# run S1 baseline
bash scripts/run_scenario.sh S1 baseline

# check artifacts exist
find artifacts/S1/baseline -maxdepth 1 -type f | sort

# quick sanity: ensure key files exist
ls artifacts/S1/baseline/fortio.txt
ls artifacts/S1/baseline/a-*.prom
ls artifacts/S1/baseline/b-*.metrics
```

### Expected
- Script exits 0
- artifacts directory contains:
  - fortio.txt
  - at least 2 A prom files and 2 A log files
  - at least 3 B metrics files

## Guardrails
- Script must be safe to rerun (overwrite same run dir or create timestamped subdir).
- No manual port-forward dependency for artifact capture (prefer kubectl exec from pods).

## Commit policy
- Title: `Add scenario runner and artifact collector`
- Body: include proof commands + a sample artifact listing
- No “Co-authored-by:”
