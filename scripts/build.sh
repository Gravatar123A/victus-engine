#!/usr/bin/env bash
# Build Victus Engine: pull + patch Paper, then build the server jar.
# Requires internet (paperweight fetches Paper source). Run from the repo root.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=scripts/dev-env.sh
source scripts/dev-env.sh

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "[victus] gradle wrapper jar missing — generating it (needs internet)..."
  gradle wrapper --gradle-version 8.12
fi

echo "[victus] applyPatches (fetch + decompile + patch Paper)..."
./gradlew applyPatches

echo "[victus] build server jar..."
./gradlew build

echo "[victus] done. jar(s):"
ls -1 build/libs/ 2>/dev/null || echo "  (check the *-server subproject build/libs/)"
