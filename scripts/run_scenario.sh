#!/bin/bash
set -euo pipefail

# Usage: ./scripts/run_scenario.sh <baseline|retry|failfast|selfheal>

SCENARIO="${1:-}"

if [[ -z "$SCENARIO" ]]; then
    echo "Usage: $0 <baseline|retry|failfast|selfheal>"
    exit 1
fi

if [[ ! "$SCENARIO" =~ ^(baseline|retry|failfast|selfheal)$ ]]; then
    echo "Error: argument must be baseline, retry, failfast, or selfheal (got: $SCENARIO)"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
CHART_DIR="$REPO_ROOT/chart"
ARTIFACTS_DIR="$REPO_ROOT/tmp/artifacts/${SCENARIO}"
NAMESPACE="demo"

echo "==> Running scenario: ${SCENARIO}"

# Create artifacts directory
mkdir -p "$ARTIFACTS_DIR"

# Create namespace if it doesn't exist
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# Deploy helm chart
echo "==> Deploying helm chart (${SCENARIO})..."
helm upgrade --install resilience-demo "$CHART_DIR" \
    -f "$CHART_DIR/values-common.yaml" \
    -f "$CHART_DIR/values-${SCENARIO}.yaml" \
    --namespace "$NAMESPACE"

# Wait for pods to be ready
echo "==> Waiting for pods to be ready..."
kubectl wait --for=condition=Ready pod -l app=app-a --timeout=120s -n "$NAMESPACE"
kubectl wait --for=condition=Ready pod -l app=app-b --timeout=120s -n "$NAMESPACE"

# Get service name
A_SERVICE=$(kubectl get svc -l app=app-a -o jsonpath='{.items[0].metadata.name}' -n "$NAMESPACE")
echo "==> A service: $A_SERVICE"

# Per-scenario fortio params
FORTIO_QPS=200
FORTIO_T=60s
case "$SCENARIO" in
    failfast) FORTIO_C=80 ;;   # Overload scenario: higher concurrency
    *) FORTIO_C=50 ;;
esac

if [[ "$SCENARIO" == "selfheal" ]]; then
    # selfheal scenario: run fortio in background, inject fault at t=15s
    echo "==> Running fortio load test with fault injection (qps=$FORTIO_QPS, c=$FORTIO_C, t=$FORTIO_T)..."
    kubectl run fortio-load --rm -i --restart=Never --image=fortio/fortio -n "$NAMESPACE" -- \
        load -qps $FORTIO_QPS -c $FORTIO_C -t $FORTIO_T -timeout 2s \
        http://$A_SERVICE:8080/api/work \
        > "$ARTIFACTS_DIR/fortio.txt" 2>&1 < /dev/null &
    FORTIO_PID=$!

    sleep 15

    A_POD=$(kubectl get pods -l app=app-a -o jsonpath='{.items[0].metadata.name}' -n "$NAMESPACE")
    echo "==> Injecting TCP reset fault into A pod: $A_POD"
    "$SCRIPT_DIR/inject_selfheal.sh" "$A_POD" "$NAMESPACE"

    wait $FORTIO_PID || true
else
    # baseline, retry, failfast: plain load test
    echo "==> Running fortio load test (qps=$FORTIO_QPS, c=$FORTIO_C, t=$FORTIO_T)..."
    kubectl run fortio-load --rm -i --restart=Never --image=fortio/fortio -n "$NAMESPACE" -- \
        load -qps $FORTIO_QPS -c $FORTIO_C -t $FORTIO_T -timeout 2s \
        http://$A_SERVICE:8080/api/work \
        > "$ARTIFACTS_DIR/fortio.txt" 2>&1 || true
fi

echo "==> Collecting artifacts..."

# Collect A pod metrics and logs (expect 2 pods)
for pod in $(kubectl get pods -l app=app-a -o jsonpath='{.items[*].metadata.name}' -n "$NAMESPACE"); do
    echo "  - Collecting from A pod: $pod"
    kubectl exec "$pod" -n "$NAMESPACE" -- curl -s localhost:8080/actuator/prometheus \
        > "$ARTIFACTS_DIR/${pod}.prom"
    kubectl logs "$pod" -n "$NAMESPACE" > "$ARTIFACTS_DIR/${pod}.log"
done

# Collect B pod metrics (expect 3 pods)
for pod in $(kubectl get pods -l app=app-b -o jsonpath='{.items[*].metadata.name}' -n "$NAMESPACE"); do
    echo "  - Collecting from B pod: $pod"
    kubectl exec "$pod" -n "$NAMESPACE" -- curl -s localhost:8080/metrics \
        > "$ARTIFACTS_DIR/${pod}.metrics"
done

echo "==> Artifacts saved to $ARTIFACTS_DIR/"
echo "==> Artifact count: $(ls -1 "$ARTIFACTS_DIR" | wc -l | tr -d ' ')"
