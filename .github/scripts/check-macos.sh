#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

xcodebuild \
  -project "macOS/TigerTV.xcodeproj" \
  -scheme "TigerTV" \
  -configuration Release \
  -derivedDataPath DerivedData \
  CODE_SIGN_IDENTITY="-" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  build

echo "macOS build OK"
