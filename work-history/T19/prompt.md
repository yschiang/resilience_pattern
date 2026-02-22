⏺ # Developer Task: Implement Workstream A - Observability Standardization                                                                        
                                                         
  ## Objective                                                                                                                                    
  Add standardized gRPC client metrics with error taxonomy to App A (Spring Boot).
  **Critical: NO behavior changes to retry/circuit breaker/bulkhead/deadline logic.**                                                             
                                                                            
  ## Your Assignment

  Implement observability instrumentation for App A's gRPC client following the architecture spec.

  **Time estimate:** 2-3 hours
  **Complexity:** Medium (mostly boilerplate, careful copy-paste)
  **Risk:** Low (additive changes only, keeps legacy metrics)

  ---

  ## Step 0: Read the Specs

  ```bash
  # Read contract (1 page)
  cat docs/workstream_A_observability.md

  # Read implementation plan (detailed guide)
  cat docs/workstream_A_implementation_plan.md

  Key concepts:
  - New metrics: grpc_client_requests_total (counter), grpc_client_latency_ms (histogram)
  - Error taxonomy: 9-value enum (SUCCESS, CONNECTION_FAILURE, TIMEOUT, etc.)
  - Single classifier: GrpcErrorClassifier maps exceptions → {reason, retryable}
  - Instrumentation: Wrap all gRPC calls in 3 client classes

  ---
  Step 1-9: Implementation

  Follow docs/workstream_A_implementation_plan.md steps 1-9 exactly:

  1. Create ErrorReason.java enum (9 values)
  2. Create CallOutcome.java record (3 fields)
  3. Create GrpcErrorClassifier.java (classifier logic)
  4. Update MetricsService.java (add recordCall() method)
  5. Update AppABaseline.java (add instrumentation)
  6. Update AppARetry.java (add instrumentation)
  7. Update AppAResilient.java (add instrumentation + protection events)
  8. Create GrpcErrorClassifierTest.java (11 unit tests)
  9. Create 7 PromQL query files in observability/promql/

  Coding tips:
  - Copy code snippets from plan verbatim (already tested)
  - Keep legacy recordDownstreamCall() calls (backward compat)
  - Add new recordCall() calls alongside legacy ones
  - Use @Nullable for optional parameters

  ---
  Step 10: Build & Test

  # Compile
  cd apps/app-a
  mvn clean compile

  # Run tests (should include 11 new tests)
  mvn test

  # Build Docker image
  cd ../..
  ./scripts/build-images.sh

  # Load into kind
  ./scripts/load-images-kind.sh

  Expected: All green, no errors.

  ---
  Step 11: Verify No Behavior Change

  This is critical - must verify retry/CB/bulkhead unchanged.

  # Run baseline scenario
  ./scripts/run_scenario.sh baseline

  # Port-forward to app-a
  kubectl port-forward -n demo svc/app-a 8080:8080 &

  # Check new metrics exist
  curl -s http://localhost:8080/actuator/prometheus | grep grpc_client

  # Expected: See grpc_client_requests_total and grpc_client_latency_ms

  Verify:
  - ✅ New metrics present with correct labels
  - ✅ Cardinality ≤36 time series
  - ✅ Legacy metrics still present

  Run all scenarios:
  for scenario in baseline retry failfast selfheal; do
      ./scripts/run_scenario.sh $scenario
  done

  Run verification scripts (must still pass):
  ./tests/verify_retry.sh      # PASS=3 FAIL=0
  ./tests/verify_failfast.sh   # PASS=2 FAIL=0
  ./tests/verify_selfheal.sh   # PASS=3 FAIL=0

  If any test fails → STOP and debug. Workstream A must not change behavior.

  ---
  Definition of Done

  Checklist (copy to your PR description):

  - [ ] Created observability package with 3 classes
  - [ ] Updated MetricsService with recordCall() method
  - [ ] Instrumented AppABaseline
  - [ ] Instrumented AppARetry
  - [ ] Instrumented AppAResilient (including protection events)
  - [ ] Added GrpcErrorClassifierTest with 11 test cases
  - [ ] Created 7 PromQL query files
  - [ ] mvn clean compile succeeds
  - [ ] mvn test passes (all tests green)
  - [ ] Docker build succeeds
  - [ ] New metrics visible at /actuator/prometheus
  - [ ] Labels correct: service, method, result, reason, retryable
  - [ ] Cardinality ≤36 time series
  - [ ] Legacy metrics still present
  - [ ] verify_retry.sh passes
  - [ ] verify_failfast.sh passes
  - [ ] verify_selfheal.sh passes

  ---
  Acceptance Criteria

  Must have:
  1. New metrics emit with correct labels
  2. All unit tests pass
  3. All integration tests pass (verify_*.sh)
  4. No behavior changes (retry counts identical)

  Sample metric output:
  grpc_client_requests_total{service="demo-service-b",method="Work",result="SUCCESS",reason="SUCCESS",retryable="false"} 8234.0
  grpc_client_requests_total{service="demo-service-b",method="Work",result="FAILURE",reason="BACKEND_ERROR",retryable="true"} 3566.0
  grpc_client_latency_ms_count{service="demo-service-b",method="Work"} 11800.0

  ---
  Common Issues & Solutions

  Issue: Compilation error "record not found"
  Fix: Ensure Java 17+ in pom.xml

  Issue: Test fails for switch expression
  Fix: Check Java version, switch expressions need 17+

  Issue: Metrics not appearing
  Fix: Check MeterRegistry is autowired, verify Micrometer is in classpath

  Issue: Cardinality explosion (>100 time series)
  Fix: Check reason enum is used (not exception.getMessage())

  Issue: Verify script fails
  Fix: Compare artifact counts before/after, ensure no retry behavior changed

  ---
  Deliverables

  When done, create PR with:

  1. Title: feat: add Workstream A observability instrumentation
  2. Description:
    - Link to docs/workstream_A_observability.md
    - DoD checklist (copied from above)
    - Screenshot of new metrics from /actuator/prometheus
    - Confirmation: "All verify_*.sh tests pass"
  3. Files changed:
    - New: 3 classes + 1 test + 7 promql files
    - Modified: MetricsService + 3 client classes
  4. Test evidence:
    - mvn test output showing 11 new tests pass
    - verify_*.sh output showing PASS=3/2/3 FAIL=0

  ---
  Questions?

  If stuck:
  1. Re-read the implementation plan step
  2. Check code snippet matches exactly
  3. Verify imports are correct
  4. Run mvn clean compile to see actual error
  5. Ask architect with specific error message

  ---
  Get Started

  # Create feature branch
  git checkout -b workstream-a-observability

  # Start with Step 1
  mkdir -p apps/app-a/src/main/java/com/demo/appa/observability

  # Follow the plan!
  cat docs/workstream_A_implementation_plan.md

  Good luck! 🚀

  ---

  **This prompt is ready to hand off to a developer. It includes:**
  - Clear objective and constraints
  - Step-by-step instructions with references
  - Build/test/verify procedures
  - Definition of Done checklist
  - Common issues and solutions
  - PR template

  **Developer can now execute independently with this prompt + the two plan documents.**