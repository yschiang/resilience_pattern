# Resilience Pattern Demo

Demonstration of resilience patterns for Spring Boot services calling downstream gRPC services.

## Architecture

- **Application A**: Spring Boot REST API (2 pods) - calls downstream B via gRPC
- **Application B**: Go gRPC service (3 pods, single-threaded) - exposes gRPC API and metrics

### Key Components

- **A pods**: 2 replicas
- **B pods**: 3 replicas
- **B gRPC port**: 50051
- **B metrics port**: 8080
- **Namespace**: demo
- **Cluster**: kind (resilience-pattern)

## Quick Start

### Prerequisites

- Docker
- kubectl
- Helm 3.x
- kind (Kubernetes in Docker)

### Build

```bash
# Build application images
./scripts/build-images.sh

# Load images into kind cluster
./scripts/load-images-kind.sh
```

### Deploy

```bash
# Create kind cluster
kind create cluster --name resilience-pattern

# Deploy baseline configuration
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-baseline.yaml

# Verify deployment
kubectl get pods -n demo
# Expected: 2 app-a pods, 3 app-b pods Running
```

### Test

```bash
# Test A service endpoint
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl -s http://a-service:8080/api/work | jq .
```

## Project Structure

```
.
├── apps/
│   ├── app-a/          # Spring Boot REST API
│   └── app-b/          # Go gRPC service
├── chart/              # Helm chart
├── scripts/            # Build and deployment scripts
├── tests/              # Verification scripts
├── artifacts/          # Scenario run outputs (gitignored)
├── docs/               # Documentation
└── skills/             # Task implementation guides
```

## Scenarios

- **S1**: Overload scenario (baseline vs resilient)
- **S4**: Connection reset scenario (baseline vs resilient)

See `docs/execution_order.md` for task sequencing and implementation order.

## Development

See `docs/plan.md` for detailed implementation plan and milestones.
