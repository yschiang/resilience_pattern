#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

docker build -t app-a:dev -f "$REPO_ROOT/apps/app-a/Dockerfile" "$REPO_ROOT"
docker build -t app-b:dev -f "$REPO_ROOT/apps/app-b/Dockerfile" "$REPO_ROOT"

echo "Built: app-a:dev, app-b:dev"
