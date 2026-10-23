#!/usr/bin/env python3
"""Fail-closed signed one-app preview policy inspection (no signing secrets required)."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
workflow=(ROOT/".github/workflows/signed-canary-preview.yml").read_text("utf-8")
gradle=(ROOT/"app/build.gradle.kts").read_text("utf-8")
updater=(ROOT/"app/src/main/java/com/studyone/app/AppUpdates.java").read_text("utf-8")
channels=(ROOT/"app/src/main/java/com/studyone/app/StudyUpdateChannels.java").read_text("utf-8")
debug_name=(ROOT/"app/src/debug/res/values/strings.xml").read_text("utf-8")
doc=(ROOT/"docs/SIGNED-SINGLE-APP-CANARY.md").read_text("utf-8")

class SignedSingleAppPolicy(unittest.TestCase):
    def test_manual_only_on_approved_main(self):
        self.assertIn("  workflow_dispatch:",workflow)
        self.assertNotIn("  pull_request:",workflow)
        self.assertNotIn("  push:",workflow)
        self.assertNotIn("  workflow_run:",workflow)
        self.assertIn("github.ref == 'refs/heads/main'",workflow)
        self.assertIn("inputs.confirmation == 'SIGNED_PREVIEW'",workflow)
        self.assertIn('test "$MAIN_SHA" = "$GITHUB_SHA"',workflow)
        self.assertIn("contents: read",workflow)
        self.assertNotIn("gh release create",workflow)
        self.assertNotIn("git push",workflow)
    def test_existing_key_only_and_pin(self):
        for token in ("STUDYONE_KEYSTORE_BASE64", "STUDYONE_STORE_PASSWORD",
                      "STUDYONE_KEY_PASSWORD", "STUDYONE_KEY_ALIAS",
                      "3f95e9aaecb32ae66c86dbd6795b0a49118f8dacf515f7fbe31795970b1681ed",
                      "apksigner", "assembleRelease", "lintRelease",
                      "versionCode='$CODE'", "versionName='$VERSION'",
                      "name='com.studyone.app'"):
            self.assertIn(token,workflow)
        self.assertIn('applicationId = "com.studyone.app"',gradle)
        self.assertIn("applicationIdSuffix",gradle)
        self.assertIn('signingConfig = signingConfigs.getByName("studyoneProduction")',gradle)
    def test_upgrade_is_real_and_artifact_is_nonrelease(self):
        for token in ("adb install -r candidate/*.apk", "firstInstallTime",
                      "SIGNED_SINGLE_APP_IN_PLACE_UPGRADE_PASSED",
                      "gh release download v3.0.0-beta.2",
                      "actions/upload-artifact@v4"):
            self.assertIn(token,workflow)
        self.assertIn("별도 설치",doc)
        self.assertIn("앱 외부",doc)
        self.assertIn("StudyOne 개발판",debug_name)
    def test_canary_updater_can_see_prereleases(self):
        self.assertIn("StudyUpdateChannels.permits(installedVersionName",updater)
        self.assertIn('release.optString("tag_name")',updater)
        self.assertIn('meta.optString("channel", "")',updater)
        self.assertIn("ctx.getPackageName().equals(candidate.packageName)",updater)
        self.assertIn("Signature[] current = signatures(installed)",updater)
        self.assertIn('if (BETA.equals(installed)) return !CANARY.equals(candidate);',channels)
        self.assertIn('if (CANARY.equals(installed)) return true;',channels)
        self.assertIn('return STABLE.equals(candidate);',channels)

if __name__=="__main__":
    unittest.main()
