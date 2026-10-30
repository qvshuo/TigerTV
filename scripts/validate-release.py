#!/usr/bin/env python3
"""Validate dispatch inputs before either platform starts a release build."""
import os
from pathlib import Path
import re
import subprocess

version = os.environ["RELEASE_VERSION"]
if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
    raise SystemExit("Release version must be MAJOR.MINOR.PATCH")

if os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
    code = os.environ["ANDROID_VERSION_CODE"]
    gradle = Path("AndroidTV/build.gradle.kts").read_text()
    current = int(re.search(r'androidVersionCode.*orElse\("([0-9]+)"\)', gradle)[1])
    if not re.fullmatch(r"[0-9]+", code) or not current < int(code) <= 2_100_000_000:
        raise SystemExit(f"Android versionCode must be greater than {current} and at most 2100000000")
    tag = f"refs/tags/v{version}"
    remote = subprocess.run(["git", "ls-remote", "--exit-code", "--tags", "origin", tag],
                            capture_output=True, text=True, timeout=30)
    if remote.returncode == 0:
        raise SystemExit(f"Release tag already exists: v{version}")
    if remote.returncode != 2:
        raise SystemExit("Unable to verify release tag: " + remote.stderr.strip())
