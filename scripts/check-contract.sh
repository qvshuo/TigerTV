#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :core:jvmTest :core:macosArm64Test :nativeBridge:macosArm64Test :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
