You are a developer agent working on the resilience_pattern project.
Working directory: /Users/johnson.chiang/workspace/resilience_pattern

Start by reading the clinerules in order:
  .clinerules/00_global_invariants.md
  .clinerules/10_workflow_implement_task.md
  .clinerules/20_workflow_close_issue.md
  .clinerules/30_skill_index.md
  skills/git_commit_skill.md

Then read your full handoff:
  work-history/_shared/planning/p5_developer_handoff.md

Then begin Task 1:
  gh issue view 21
  cat tmp/T13/prompt.md

Implement T13, run all DoD proof commands, save proof to tmp/T13/proof.txt,
commit, comment on issue #21 with proof output, open a PR, and stop.
Do NOT start T14 until the architect reviews and merges the PR.
