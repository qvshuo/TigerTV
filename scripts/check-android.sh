#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../AndroidTV"

./gradlew test assembleDebug

echo "Android build OK"
