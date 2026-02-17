## T07 DoD Proof Outputs

### Proof 1: Helm lint
```bash
$ helm lint ./chart
```
```
==> Linting ./chart
[INFO] Chart.yaml: icon is recommended

1 chart(s) linted, 0 chart(s) failed
```
✓ PASS: Helm chart lint successful

### Proof 2: Helm template rendering
```bash
$ helm template demo ./chart | grep -E "kind: (Deployment|Service)" | sort
```
```
kind: Deployment
kind: Deployment
kind: Service
kind: Service
```
✓ PASS: 2 Deployments and 2 Services rendered

### Proof 3: Verify replica counts
```bash
$ helm template demo ./chart | grep -B 2 "replicas:"
```
```
    app: app-a
spec:
  replicas: 2
--
    app: app-b
spec:
  replicas: 3
```
✓ PASS: app-a has 2 replicas, app-b has 3 replicas

### Proof 4: Verify service ports
```bash
$ helm template demo ./chart | grep -E "port: (8080|50051)"
```
```
  - port: 8080
  - port: 50051
  - port: 8080
```
✓ PASS: a-service on port 8080, b-service on ports 50051 (gRPC) and 8080 (metrics)

## Implementation Details

- **Chart name**: resilience-demo (version 0.1.0)
- **Deployments**:
  - app-a: 2 replicas, health probes
  - app-b: 3 replicas, health probes
- **Services**:
  - a-service: ClusterIP, port 8080
  - b-service: ClusterIP, ports 50051 (gRPC) + 8080 (metrics)
- **Parameterized**: images, tags, replicas, env vars

## All DoD Proofs Passed ✓

Commit: ffff3ea
