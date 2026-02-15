# Skill Index — Pointers to skills/

## Purpose
This file maps task types to relevant skills. All skill content lives in `skills/*.md` (SSOT).

## Skill Directory
All skills are in: `../skills/*.md`

## Skill to Task Mapping

### Infrastructure & Tooling
- **Kind cluster:** `skills/kind_cluster_skill.md`
  - Applies to: local K8S setup, any task needing `kubectl`
  - Required for: T07, T08, T09A, T09B, T09C, T10, T11
  - Sets up: resilience-pattern cluster, demo namespace

- **Image build/load:** `skills/image_build_load_skill.md`
  - Applies to: T02, T06 (app builds)
  - Required for: building app-a, app-b Docker images
  - Loads images into kind cluster

- **Helm packaging:** `skills/helm_packaging_skill.md`
  - Applies to: T07, T08 (chart + values)
  - Required for: creating chart structure, values overlays
  - Validates: helm lint, template rendering

### Application Code
- **Proto codegen:** `skills/proto_codegen_skill.md`
  - Applies to: T02, T06 (gRPC contract)
  - Required for: generating Go/Java gRPC stubs from proto/demo.proto
  - Tools: protoc, protoc-gen-go, protoc-gen-grpc-java

- **Resilience patterns:** `skills/resilience_patterns_skill.md`
  - Applies to: T03, T04, T05 (metrics + P0/P1 resilience)
  - Required for: implementing deadline, bulkhead, circuit breaker, self-heal
  - Must satisfy: `.clinerules/40_resilience_patterns.md`

### Testing & Scenarios
- **Scenario artifact:** `skills/scenario_artifact_skill.md`
  - Applies to: T09A, T09B, T09C (run_scenario.sh)
  - Required for: collecting fortio, prometheus, logs, metrics
  - Output: 8 files per scenario (1 fortio + 2 A prom + 2 A log + 3 B metrics)

- **Fault injection:** `skills/fault_injection_skill.md` (if exists)
  - Applies to: T10 (iptables reset + tc fallback)
  - Required for: S4 scenario TCP connection reset
  - Runs: inside single A pod with NET_ADMIN capability

### Project Management
- **Issue ops:** `skills/issue_ops_skill.md`
  - Applies to: T01 (bootstrap), T12 (docs)
  - Required for: creating/updating issues, milestones, labels
  - Tools: gh CLI

- **Git commit:** `skills/git_commit_skill.md`
  - Applies to: ALL tasks (every commit)
  - Required for: conventional commits, no Co-authored-by
  - Enforces: title ≤72 chars (plain) or ≤80 chars (with prefix)

## How to Use
1. Check issue "Skill references" section
2. Open relevant skill from `skills/` directory
3. Follow skill's DoD and steps exactly
4. Verify implementation against skill's success criteria

## Task-to-Skill Quick Reference

| Task | Primary Skill(s) | Supporting Skills |
|------|------------------|-------------------|
| T01 | issue_ops | git_commit |
| T02 | proto_codegen, image_build_load | git_commit |
| T03 | resilience_patterns | git_commit |
| T04 | resilience_patterns | git_commit |
| T05 | resilience_patterns | git_commit |
| T06 | proto_codegen, image_build_load | git_commit |
| T07 | helm_packaging | kind_cluster, git_commit |
| T08 | helm_packaging | git_commit |
| T09A | scenario_artifact | kind_cluster, git_commit |
| T09B | scenario_artifact | kind_cluster, git_commit |
| T09C | scenario_artifact | kind_cluster, git_commit |
| T10 | fault_injection (if exists) | kind_cluster, git_commit |
| T11 | scenario_artifact | git_commit |
| T12 | issue_ops | git_commit |

## Skill Hierarchy
```
git_commit_skill.md (ALWAYS required, every task)
    │
    ├── Infrastructure tasks
    │   ├── kind_cluster_skill.md
    │   ├── image_build_load_skill.md
    │   └── helm_packaging_skill.md
    │
    ├── Application tasks
    │   ├── proto_codegen_skill.md
    │   └── resilience_patterns_skill.md
    │
    └── Testing tasks
        ├── scenario_artifact_skill.md
        └── fault_injection_skill.md (if exists)
```

---

**Remember:** Skills are the SSOT for implementation details. This index is just a roadmap.
