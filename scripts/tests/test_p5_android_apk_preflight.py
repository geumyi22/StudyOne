"""Synthetic inspector-output tests; no real APKs, keys or device interaction."""
from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import unittest
from zipfile import ZipFile

from scripts.p5_android_apk_preflight import (
    OFFICIAL_CERT_SHA256, PreflightError, inspect_apk,
)


class AndroidV4P5PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix="studyone-apk-preflight-synthetic-")
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "candidate.apk"
        with ZipFile(self.apk, "w") as artifact:
            artifact.writestr("fake-synthetic-test-only", "not a real Android app")
        self.badging = (
            "package: name='com.studyone.app' versionCode='40001' "
            "versionName='4.0.0-beta.1'\n"
        )
        self.signature = (
            "Verifies\nVerified using v1 scheme (JAR signing): false\n"
            "Verified using v2 scheme (APK Signature Scheme v2): true\n"
            "Verified using v3 scheme (APK Signature Scheme v3): true\n"
            "Number of signers: 1\nSigner #1 certificate SHA-256 digest: "
            + OFFICIAL_CERT_SHA256 + "\n"
        )

    def inspect(self, *, badging=None, signature=None, **kwargs):
        def mock_output(command):
            self.assertNotIn("adb", command)
            self.assertNotIn("install", command)
            if command[0] == "aapt2":
                return self.badging if badging is None else badging
            if command[0] == "apksigner":
                return self.signature if signature is None else signature
            raise AssertionError("unexpected command")

        with patch("scripts.p5_android_apk_preflight._output", side_effect=mock_output):
            return inspect_apk(self.apk, **kwargs)

    def test_metadata_pass_does_not_claim_device_or_install_verified(self):
        report = self.inspect(installed_version_code=30107)
        self.assertEqual(report["versionName"], "4.0.0-beta.1")
        self.assertFalse(report["deviceVerified"])
        self.assertFalse(report["safeToInstallAutomatically"])
        self.assertEqual(report["status"], "CANDIDATE_METADATA_VERIFIED_ONLY")

    def test_stable_version_pair_is_separate_from_beta_and_still_not_device_verified(self):
        stable = self.badging.replace("40001", "40002").replace(
            "4.0.0-beta.1", "4.0.0")
        result = self.inspect(badging=stable, installed_version_code=40001)
        self.assertEqual(result["versionCode"], 40002)
        self.assertFalse(result["deviceVerified"])

    def test_wrong_package_and_canary_debug_rejected(self):
        with self.assertRaisesRegex(PreflightError, "OFFICIAL_PACKAGE_ID"):
            self.inspect(badging=self.badging.replace(
                "com.studyone.app'", "com.studyone.app.canary7'"))

    def test_wrong_signer_and_multiple_signers_rejected(self):
        for value in (self.signature.replace(OFFICIAL_CERT_SHA256, "f" * 64),
                      self.signature.replace("Number of signers: 1", "Number of signers: 2")):
            with self.subTest(signature=value), self.assertRaises(PreflightError):
                self.inspect(signature=value)

    def test_unsigned_v2_or_mismatched_stable_code_rejected(self):
        with self.assertRaisesRegex(PreflightError, "APK_V2_SIGNATURE"):
            self.inspect(signature=self.signature.replace(
                "Scheme v2): true", "Scheme v2): false"))
        with self.assertRaisesRegex(PreflightError, "V4_VERSION_PAIR"):
            self.inspect(badging=self.badging.replace("40001", "40002"))

    def test_upgrade_code_must_increase_and_checksum_must_match(self):
        with self.assertRaisesRegex(PreflightError, "UPGRADE_VERSION"):
            self.inspect(installed_version_code=40001)
        with self.assertRaisesRegex(PreflightError, "APK_CHECKSUM"):
            self.inspect(expected_sha256="0" * 64)

    def test_corrupt_archive_and_symlink_rejected_before_sdk_commands(self):
        self.apk.write_bytes(b"not a zip")
        with self.assertRaisesRegex(PreflightError, "APK_ARCHIVE"):
            self.inspect()
        self.apk.unlink()
        self.apk.symlink_to("outside.apk")
        with self.assertRaisesRegex(PreflightError, "SYMLINK"):
            self.inspect()


if __name__ == "__main__":
    unittest.main()
