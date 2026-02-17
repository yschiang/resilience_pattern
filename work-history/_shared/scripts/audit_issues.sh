#!/bin/bash
echo "| Issue | Title | Refs | DoD | Proofs≥2 | Skills | Status |"
echo "|-------|-------|------|-----|----------|--------|--------|"

for i in {1..14}; do
  body=$(gh issue view $i --json body --jq '.body')
  title=$(gh issue view $i --json title --jq '.title' | cut -c1-40)
  
  # Check for required sections
  has_refs=$(echo "$body" | grep -c '^\*\*Refs\*\*' || echo 0)
  has_dod=$(echo "$body" | grep -c '^\*\*DoD\*\*' || echo 0)
  has_skills=$(echo "$body" | grep -c '^\*\*Skill references\*\*' || echo 0)
  
  # Count proof commands (bash code blocks)
  proof_count=$(echo "$body" | grep -c '^```bash' || echo 0)
  
  # Determine status
  if [[ $has_refs -ge 1 && $has_dod -ge 1 && $proof_count -ge 2 && $has_skills -ge 1 ]]; then
    status="PASS"
  elif [[ $has_refs -ge 1 && $has_dod -ge 1 && $proof_count -ge 2 ]]; then
    status="UPDATED"
  else
    status="NEEDS_HUMAN"
  fi
  
  printf "| #%-2d | %-40s | %-4s | %-3s | %-8s | %-6s | %-11s |\n" \
    $i "$title" "$has_refs" "$has_dod" "$proof_count" "$has_skills" "$status"
done
