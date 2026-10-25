#!/usr/bin/env python3
"""Non-publishing, fail-closed StudyOne 4.0.0 release metadata contract.

This does not replace real platform CI, device acceptance or signed-release checks.
Legacy 3.1 canary sources remain valid until the final v4 candidate is prepared.
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def metadata(path):
    return json.loads(read(path))


def gradle_field(name):
    hits = re.findall(r"^\s*" + re.escape(name) + r"\s*=\s*(?:\"([^\"]+)\"|(\d+))\s*$",
                      read("app/build.gradle.kts"), flags=re.MULTILINE)
    if len(hits) != 1:
        raise AssertionError(f"Android Gradle {name} must appear exactly once")
    return hits[0][0] or int(hits[0][1])


def project_field(key):
    hits = re.findall(r"^\s*" + re.escape(key) + r":\s*'?([^'\n]+)'?\s*$",
                      read("ios/project.yml"), flags=re.MULTILINE)
    if len(hits) != 1:
        raise AssertionError(f"iOS project {key} must appear exactly once")
    return hits[0].rstrip()


class V4ReleaseContract(unittest.TestCase):
    def test_android_version_and_single_installed_app(self):
        root = metadata("version.json")
        self.assertEqual(root["versionName"], gradle_field("versionName"))
        self.assertEqual(root["versionCode"], gradle_field("versionCode"))
        self.assertEqual("com.studyone.app", gradle_field("applicationId"))
        self.assertGreater(root["versionCode"], 30001)
        self.assertIn(root["channel"], ("canary", "beta", "stable"))
        self.assertIn('signingConfig = signingConfigs.getByName("studyoneProduction")',
                      read("app/build.gradle.kts"))

    def test_ios_version_and_identity(self):
        ios = metadata("ios/version.json")
        self.assertEqual(str(ios["versionCode"]), project_field("CURRENT_PROJECT_VERSION"))
        self.assertEqual(ios["versionName"].split("-")[0], project_field("MARKETING_VERSION"))
        self.assertEqual("com.studyone.ios", project_field("PRODUCT_BUNDLE_IDENTIFIER"))
        self.assertGreater(ios["versionCode"], 30001)
        self.assertIn(ios["channel"], ("canary", "beta", "stable"))

    def test_v4_candidate_is_atomic_and_only_one_beta(self):
        android = metadata("version.json")
        ios = metadata("ios/version.json")
        v4 = any(x["versionName"].startswith("4.") for x in (android, ios))
        if not v4:
            # Old canary builds are allowed while source functionality is developed.
            return
        self.assertEqual(android["versionName"], ios["versionName"],
                         "Release candidate versions must match across mobile")
        self.assertEqual(android["versionCode"], ios["versionCode"])
        self.assertEqual(android["versionName"] in ("4.0.0-beta.1", "4.0.0"), True,
                         "Only one v4 beta is permitted before v4 Stable")
        expected = ("beta", 40001) if android["versionName"] == "4.0.0-beta.1" else ("stable", 40002)
        for document in (android, ios):
            self.assertEqual(document["channel"], expected[0])
            self.assertEqual(document["versionCode"], expected[1])
        desktop = read("windows/StudyOne.Desktop/StudyOne.Desktop.csproj")
        self.assertIn("<Version>" + android["versionName"] + "</Version>", desktop,
                      "All three platform version labels must be intentionally aligned")
        if android["channel"] == "stable":
            # Unsigned iOS previews are NOT a signed, installable Stable release.
            self.assertNotIn("CODE_SIGNING_ALLOWED: NO", read("ios/project.yml"),
                             "Cannot label unsigned-only iOS output a signed Stable candidate")

    def test_release_policy_remains_explicitly_nonpublishing(self):
        policy = read("docs/RELEASE-4.0.0-ACCEPTANCE.md")
        for marker in ("v4.0.0-beta.1", "v4.0.0", "실기기", "OAuth",
                       "tasks/progress/details/wrongAnswers", "NOT READY"):
            self.assertIn(marker, policy)
        flow = read(".github/workflows/v4-release-contract.yml")
        self.assertIn("permissions:\n  contents: read", flow)
        self.assertIn("python3 scripts/tests/check_v4_release_contract.py", flow)
        for forbidden in ("gh release create", "gh release upload", "git push",
                          "contents: write"):
            self.assertNotIn(forbidden, flow)


if __name__ == "__main__":
    unittest.main()
