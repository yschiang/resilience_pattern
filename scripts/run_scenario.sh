#!/bin/bash
set -euo pipefail

# Usage: ./scripts/run_scenario.sh <scenario> <mode>
# Example: ./scripts/run_scenario.sh S1 baseline

SCENARIO="${1:-}"
MODE="${2:-}"

if [[ -z "$SCENARIO" ]] || [[ -z "$MODE" ]]; then
    echo "Usage: $0 <scenario> <mode>"
    echo "  scenario: S1 or S4"
    echo "  mode: baseline or resilient"
    exit 1
fi

if [[ ! "$SCENARIO" =~ ^(S1|S4)$ ]]; then
    echo "Error: scenario must be S1 or S4"
    exit 1
fi

if [[ ! "$MODE" =~ ^(baseline|resilient)$ ]]; then
    echo "Error: mode must be baseline or resilient"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
CHART_DIR="$REPO_ROOT/chart"
ARTIFACTS_DIR="$REPO_ROOT/tmp/artifacts/$SCENARIO/$MODE"
NAMESPACE="demo"

echo "==> Running scenario: $SCENARIO, mode: $MODE"

# Create artifacts directory
mkdir -p "$ARTIFACTS_DIR"

# Create namespace if it doesn't exist
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# Deploy helm chart with appropriate overlays
echo "==> Deploying helm chart..."
VALUES_FILES="-f $CHART_DIR/values-common.yaml -f $CHART_DIR/values-$MODE.yaml"

if [[ "$SCENARIO" == "S1" ]]; then
    VALUES_FILES="$VALUES_FILES -f $CHART_DIR/values-s1.yaml"
elif [[ "$SCENARIO" == "S4" ]]; then
    VALUES_FILES="$VALUES_FILES -f $CHART_DIR/values-s4.yaml"
fi

helm upgrade --install resilience-demo "$CHART_DIR" $VALUES_FILES --namespace "$NAMESPACE"

# Wait for pods to be ready
echo "==> Waiting for pods to be ready..."
kubectl wait --for=condition=Ready pod -l app=app-a --timeout=120s -n "$NAMESPACE"
kubectl wait --for=condition=Ready pod -l app=app-b --timeout=120s -n "$NAMESPACE"

# Get service names
A_SERVICE=$(kubectl get svc -l app=app-a -o jsonpath='{.items[0].metadata.name}' -n "$NAMESPACE")
echo "==> A service: $A_SERVICE"

# Run fortio load test (allow failures for baseline data collection)
echo "==> Running fortio load test (qps=200, c=80, t=60s)..."
kubectl run fortio-load --rm -i --restart=Never --image=fortio/fortio -n "$NAMESPACE" -- \
    load -qps 200 -c 80 -t 60s -timeout 2s http://$A_SERVICE:8080/api/work \
    > "$ARTIFACTS_DIR/fortio.txt" 2>&1 || true

echo "==> Collecting artifacts..."

# Collect A pod metrics and logs (expect 2 pods)
for pod in $(kubectl get pods -l app=app-a -o jsonpath='{.items[*].metadata.name}' -n "$NAMESPACE"); do
    echo "  - Collecting from A pod: $pod"
    kubectl exec "$pod" -n "$NAMESPACE" -- curl -s localhost:8080/actuator/prometheus > "$ARTIFACTS_DIR/${pod}.prom"
    kubectl logs "$pod" -n "$NAMESPACE" > "$ARTIFACTS_DIR/${pod}.log"
done

# Collect B pod metrics (expect 3 pods)
for pod in $(kubectl get pods -l app=app-b -o jsonpath='{.items[*].metadata.name}' -n "$NAMESPACE"); do
    echo "  - Collecting from B pod: $pod"
    kubectl exec "$pod" -n "$NAMESPACE" -- curl -s localhost:8080/metrics > "$ARTIFACTS_DIR/${pod}.metrics"
done

echo "==> Artifacts saved to $ARTIFACTS_DIR/"
echo "==> Artifact count: $(ls -1 "$ARTIFACTS_DIR" | wc -l | tr -d ' ')"
