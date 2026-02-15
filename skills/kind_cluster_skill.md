# skills/kind_cluster_skill.md

## Purpose
Provide a **reproducible local K8S environment** (kind) with fast reset, for demo runs.

## Inputs
- kind cluster name (default): `resilience-pattern`
- namespace (default): `demo`

## Outputs
- `scripts/kind-up.sh` and `scripts/kind-reset.sh` (or Makefile targets)
- Cluster created, namespace ready

## Steps
1) Create kind cluster with fixed name.
2) Create namespace `demo`.
3) Basic sanity: nodes ready, kube-system pods running.

## DoD (Proof commands + Expected)

### Proof commands
```bash
kind version
kubectl version --client

# create or ensure cluster
bash scripts/kind-up.sh

kubectl cluster-info
kubectl get nodes
kubectl get ns demo
```

### Expected
- kind cluster exists with name `resilience-pattern`
- `kubectl get nodes` shows Ready
- namespace `demo` exists

## Guardrails
- Scripts must be idempotent:
  - kind-up does nothing if cluster exists
  - kind-reset always deletes & recreates cleanly
- Do not install unnecessary addons unless needed for demo.

## Commit policy
- Title: `Add kind cluster scripts`
- Body: include proof commands
- No “Co-authored-by:”
