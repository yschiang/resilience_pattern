# skills/proto_codegen_skill.md

## Purpose
Define a single gRPC contract in `/proto` and ensure **both app-a (Java) and app-b (C++) build against the same proto** with a reproducible codegen story.

## Inputs
- `proto/demo.proto`
- Target languages:
  - app-a: grpc-java client (Spring Boot)
  - app-b: grpc++ server

## Outputs
- `proto/demo.proto` committed
- app-a build generates stubs automatically (Gradle/Maven plugin recommended)
- app-b build generates stubs in build step (CMake + protoc) or vendored generation script

## Recommended design
- Package name stable (e.g., `demo.v1`)
- One unary RPC: `Work(WorkRequest) returns (WorkReply)`
- WorkReply includes minimal fields needed by demo:
  - `bool ok`
  - `string code`
  - `int64 latency_ms` (optional; can be measured by caller too)

## Steps
1) Create `proto/demo.proto` with unary Work.
2) Wire app-a build to compile proto without committing generated code:
   - Gradle protobuf plugin OR Maven protobuf plugin.
3) Wire app-b build to compile proto:
   - Option A: use protoc within Docker build (no local deps)
   - Option B: use a containerized protoc generator script (`scripts/proto/gen.sh`) producing into build dir
4) Add CI-like local checks: both builds succeed.

## DoD (Proof commands + Expected)

### Proof commands
```bash
# show proto exists
ls -la proto/demo.proto

# app-a build (choose one)
( cd app-a && ./mvnw -q -DskipTests package ) || ( cd app-a && ./gradlew -q build )

# app-b build (example)
( cd app-b && docker build -t resilience-pattern/app-b:dev . )

# optional: quick grep for generated symbols (implementation-dependent)
grep -R "service .*Demo" -n proto/demo.proto
```

### Expected
- `proto/demo.proto` exists and is used by both sides
- app-a builds without manual protoc install (preferred)
- app-b docker build succeeds and includes generated sources

## Guardrails
- Do not commit large generated trees unless unavoidable.
- Keep proto stable; changing field numbers is a breaking change.

## Commit policy
- Separate commit for proto introduction:
  - Title: `Add gRPC demo proto`
  - Body: include proof commands executed
  - No “Co-authored-by:”
