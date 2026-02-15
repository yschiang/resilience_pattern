# skills/image_build_load_skill.md

## Purpose
Standardize **docker build + kind load** for app-a and app-b so baseline E2E is repeatable.

## Inputs
- Dockerfiles:
  - `app-a/Dockerfile`
  - `app-b/Dockerfile`
- Image tags (defaults):
  - `resilience-pattern/app-a:dev`
  - `resilience-pattern/app-b:dev`
- kind cluster name: `resilience-pattern`

## Outputs
- `scripts/build-images.sh`
- `scripts/load-images-kind.sh`
- (optional) Makefile targets:
  - `build-images`, `load-images`, `rebuild`

## Steps
1) Build app-a image.
2) Build app-b image.
3) Load both into kind.
4) Verify local images exist.

## DoD (Proof commands + Expected)

### Proof commands
```bash
docker --version

bash scripts/build-images.sh
bash scripts/load-images-kind.sh

# verify images exist locally
docker images | grep -E "resilience-pattern/app-a|resilience-pattern/app-b"
```

### Expected
- Both images build successfully
- Both images are loadable into kind (no pull needed when deploying)
- Tag names match what Helm chart expects

## Guardrails
- Keep tags stable; chart values should reference these tags.
- Prefer multi-stage Dockerfiles; keep images reasonably small.

## Commit policy
- Title: `Add image build/load scripts`
- Body: include proof commands
- No “Co-authored-by:”
