## T08 DoD Proof Outputs

### Proof 1: Count values overlay files
```bash
$ ls chart/values-*.yaml | wc -l
```
```
4
```

Files:
```
chart/values-baseline.yaml
chart/values-common.yaml
chart/values-s1.yaml
chart/values-s4.yaml
```
✓ PASS: 4 values overlay files exist

### Proof 2: Verify overlay merging with replicas
```bash
$ helm template demo ./chart -f chart/values-common.yaml -f chart/values-baseline.yaml -f chart/values-s1.yaml | grep -E "replicas:"
```
```
  replicas: 2
  replicas: 3
```
✓ PASS: A=2, B=3 replicas from values-common.yaml

### Proof 3: Verify B_DELAY_MS from S1 overlay
```bash
$ helm template demo ./chart -f chart/values-common.yaml -f chart/values-baseline.yaml -f chart/values-s1.yaml | grep -B 2 -A 1 "B_DELAY_MS"
```
```
        env:
        - name: B_DELAY_MS
          value: "200"
```
✓ PASS: B_DELAY_MS=200 from values-s1.yaml overlay

## Implementation Details

- **values-common.yaml**: Shared config (A=2, B=3 replicas, images)
- **values-baseline.yaml**: resilience.enabled=false, baseline env vars
- **values-s1.yaml**: B_DELAY_MS=200, load params (qps=200, concurrency=80)
- **values-s4.yaml**: B_DELAY_MS=5, fault injection params

## All DoD Proofs Passed ✓

Commit: ccaf9d2

---

## 🎉 P0 MILESTONE COMPLETE

All P0 tasks (T01→T06→T02→T07→T08) are now complete!
- ✓ T01: Repo hygiene
- ✓ T06: app-b baseline (gRPC service)
- ✓ T02: app-a baseline (Spring Boot REST)
- ✓ T07: Helm chart (A=2, B=3)
- ✓ T08: Values overlays

Next: P1 Baseline Evidence Ready (T03 → T09A)
