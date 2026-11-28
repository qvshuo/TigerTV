#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory

APK = "TigerTV-AndroidTV-universal.apk"
MAX_CODE = 2_100_000_000


def latest_android_release(repository):
    pages = json.loads(subprocess.check_output(
        ["gh", "api", "--paginate", "--slurp", f"repos/{repository}/releases"],
        text=True, timeout=60,
    ))
    return max(
        (release for page in pages for release in page
         if not release["draft"] and release["published_at"]
         and any(asset["name"] == APK and asset["state"] == "uploaded" for asset in release["assets"])),
        key=lambda release: release["published_at"], default=None,
    )


def published_version_code(repository):
    release = latest_android_release(repository)
    if release is None:
        return 0
    with TemporaryDirectory() as directory:
        apk = Path(directory) / APK
        subprocess.run(
            ["gh", "release", "download", release["tag_name"], "--repo", repository, "--pattern", APK, "--dir", directory],
            check=True, timeout=120,
        )
        badging = subprocess.check_output(["aapt2", "dump", "badging", str(apk)], text=True, timeout=30)
    match = re.search(r"^package: .*\bversionCode='([0-9]+)'", badging, re.MULTILINE)
    if not match or not 0 < int(match[1]) <= MAX_CODE:
        raise SystemExit(f"Unable to read Android versionCode from {release['tag_name']}")
    return int(match[1])


def validate_version_code(code, published):
    if not re.fullmatch(r"[0-9]+", code) or not published < int(code) <= MAX_CODE:
        raise SystemExit(f"Android versionCode must be greater than {published} and at most {MAX_CODE}")


def main():
    version = os.environ["RELEASE_VERSION"]
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise SystemExit("Release version must be MAJOR.MINOR.PATCH")
    if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
        return

    code = os.environ["ANDROID_VERSION_CODE"]
    validate_version_code(code, 0)
    tag = f"refs/tags/v{version}"
    remote = subprocess.run(["git", "ls-remote", "--exit-code", "--tags", "origin", tag],
                            capture_output=True, text=True, timeout=30)
    if remote.returncode == 0:
        raise SystemExit(f"Release tag already exists: v{version}")
    if remote.returncode != 2:
        raise SystemExit("Unable to verify release tag: " + remote.stderr.strip())

    published = published_version_code(os.environ["GITHUB_REPOSITORY"])
    validate_version_code(code, published)
    print(f"Android versionCode {code} exceeds published baseline {published}")


if __name__ == "__main__":
    main()
