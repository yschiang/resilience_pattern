# Skill: Apply Resilience Patterns to Spring Boot gRPC Client (Downstream single-thread)

## Goal
Transform Application A (Spring Boot on K8S, 3 pods) calling downstream B (C++ gRPC, single-thread)
from queueing collapse + correlated failures to fail-fast + isolated + self-healing + observable.

## Outputs
- BaselineBClient and ResilientBClient switchable by flag `resilience.enabled`
- P0: per-call deadline, inflight cap, circuit breaker
- P1: connection self-heal with backoff; optional channel pool
- P3: semantic error taxonomy + /actuator/prometheus metrics
- Demo scripts: ./scripts/run_scenario.sh S1|S4 baseline|resilient

## Steps
Step 0 Baseline Assessment -> docs/BASELINE.md
Step 1 Error Taxonomy (enum + exception + mapping tests)
Step 2 Resilient Wrapper (deadline + inflight + breaker)
Step 3 Self-heal + optional channel pool
Step 4 Fallback (optional) -> docs/FALLBACK.md
Step 5 Metrics -> /actuator/prometheus
Step 6 Drill scripts + docs/SCENARIOS.md
