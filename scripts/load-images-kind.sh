#!/usr/bin/env bash
set -euo pipefail

kind load docker-image app-a:dev
kind load docker-image app-b:dev

echo "Loaded: app-a:dev, app-b:dev into kind cluster"
