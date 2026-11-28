import json
import runpy
from pathlib import Path
import unittest
from unittest.mock import patch

script = runpy.run_path(Path(__file__).with_name("validate-release.py"))
apk = script["APK"]
repository = "owner/repo"


def release(tag, published, *, draft=False, has_apk=True):
    return {
        "tag_name": tag, "draft": draft, "published_at": published,
        "assets": [{"name": apk, "state": "uploaded"}] if has_apk else [],
    }


class VersionCodeTest(unittest.TestCase):
    @patch("subprocess.check_output")
    def test_first_android_release(self, output):
        output.return_value = json.dumps([[release("v1.0.0", "2026-01-01", has_apk=False)]])
        baseline = script["published_version_code"](repository)
        self.assertEqual(baseline, 0)
        script["validate_version_code"]("1", baseline)
        with self.assertRaises(SystemExit):
            script["validate_version_code"]("0", baseline)

    @patch("subprocess.run")
    @patch("subprocess.check_output")
    def test_only_newer_codes_than_latest_published_apk_pass(self, output, download):
        output.side_effect = [
            json.dumps([
                [release("v3.0.0", "2026-04-01", draft=True), release("v1.0.0", "2026-01-01")],
                [release("v2.1.0", "2026-03-01", has_apk=False), release("v2.0.0", "2026-02-01")],
            ]),
            "package: name='art.anjing.tigertv' versionCode='31' versionName='2.0.0'\n",
        ]
        baseline = script["published_version_code"](repository)
        self.assertEqual(baseline, 31)
        self.assertEqual(download.call_args.args[0][:4], ["gh", "release", "download", "v2.0.0"])
        for invalid in ("30", "31", "2100000001", "abc"):
            with self.subTest(invalid=invalid), self.assertRaises(SystemExit):
                script["validate_version_code"](invalid, baseline)
        script["validate_version_code"]("32", baseline)
        script["validate_version_code"]("2100000000", baseline)

    @patch("subprocess.run")
    @patch("subprocess.check_output")
    def test_unreadable_published_apk_fails(self, output, _download):
        output.side_effect = [json.dumps([[release("v1.0.0", "2026-01-01")]]), ""]
        with self.assertRaisesRegex(SystemExit, "Unable to read Android versionCode"):
            script["published_version_code"](repository)
