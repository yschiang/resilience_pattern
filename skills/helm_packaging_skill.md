# skills/helm_packaging_skill.md

## Purpose
Create a Helm chart that deploys:
- **A = 2 pods**
- **B = 3 pods**
with consistent ports, env wiring, and values overlays.

## Inputs
- Chart path: `chart/`
- Values overlays:
  - `chart/values-common.yaml`
  - `chart/values-baseline.yaml`
  - placeholders: `chart/values-s1.yaml`, `chart/values-s4.yaml`

## Outputs
- `chart/Chart.yaml` + templates for:
  - deployments/services for A and B
- Values keys are stable and match plan.md:
  - B gRPC port: 50051
  - B metrics port: 9091
  - A HTTP port: 8080 (example)
  - B delay env: `DELAY_MS`

## Steps
1) Create minimal chart structure.
2) Template A deployment/service:
   - replicas=2
   - env: B target host/port
3) Template B deployment/service:
   - replicas=3
   - env: DELAY_MS
   - expose ports 50051, 9091
4) Create overlays:
   - common: replicas/images/ports
   - baseline: `resilience.enabled=false`
   - s1/s4 placeholders: set `DELAY_MS` and scenario params (even if not used yet)

## DoD (Proof commands + Expected)

### Proof commands
```bash
helm version

# lint + template sanity
helm lint ./chart
helm template demo ./chart -f chart/values-common.yaml -f chart/values-baseline.yaml > ./tmp/demo.yaml
wc -l ./tmp/demo.yaml

# deploy baseline to demo namespace
kubectl create ns demo --dry-run=client -o yaml | kubectl apply -f -
helm upgrade --install demo ./chart -n demo -f chart/values-common.yaml -f chart/values-baseline.yaml

kubectl -n demo get po
kubectl -n demo get svc
```

### Expected
- `helm lint` passes
- Deploy succeeds
- `kubectl -n demo get po` shows:
  - 2 pods for app-a Running/Ready
  - 3 pods for app-b Running/Ready
- Services exist for A and B

## Guardrails
- Keep template minimal; don’t over-engineer.
- Values overlays must not require code changes to switch modes/scenarios.

## Commit policy
- Title: `Add Helm chart for demo`
- Body: include proof commands + expected counts
- No “Co-authored-by:”
