# Issue #36 Progress: Rename scenarios to baseline/retry/failfast/selfheal

## Completed ✓

**Helm charts:**
- ✅ Deleted old deprecated files (values-baseline.yaml, values-resilient.yaml, values-s1.yaml, values-s4.yaml)
- ✅ Renamed: values-scenario{1,2,3,4}.yaml → values-{baseline,retry,failfast,selfheal}.yaml

**Test scripts:**
- ✅ Renamed: verify_scenario{2,3,4}.sh → verify_{retry,failfast,selfheal}.sh

**Injection script:**
- ✅ Renamed: inject_s4.sh → inject_selfheal.sh

## Remaining

**scripts/run_scenario.sh:**
- [ ] Update usage: `<1|2|3|4>` → `<baseline|retry|failfast|selfheal>`
- [ ] Update argument validation
- [ ] Update values file references
- [ ] Update artifact directory paths
- [ ] Update case statements (N == "4" → SCENARIO == "selfheal")
- [ ] Update inject script call

**Test scripts content:**
- [ ] verify_retry.sh: update artifact paths (scenario1 → baseline, scenario2 → retry)
- [ ] verify_failfast.sh: update artifact paths (scenario1 → baseline, scenario3 → failfast)
- [ ] verify_selfheal.sh: update artifact paths (scenario4 → selfheal)

**Documentation:**
- [ ] README.md: all S1/S2/S3/S4 → baseline/retry/failfast/selfheal
- [ ] README.md: scenario{1,2,3,4} paths → new names
- [ ] docs/plan2.md: scenario references
- [ ] docs/runbook.md: commands and paths
- [ ] CONTRIBUTING.md: example references

**Verification:**
- [ ] grep -r "scenario[1-4]" active files → 0 (except work-history)
- [ ] grep -r "S[1-4]" only in valid prose contexts
- [ ] Build succeeds
- [ ] Can run: `./scripts/run_scenario.sh baseline`
