"""Synthetic ADB outputs only. Never touches a device or its storage."""
from __future__ import annotations

from subprocess import CompletedProcess
from unittest.mock import patch
import unittest

from scripts.p5_android_device_preflight import (
    DevicePreflightError, _adb, _installed_packages, _select_serial, inspect_device,
)


class AndroidDeviceP5Tests(unittest.TestCase):
    def setUp(self):
        self.serial = "TEST_DEVICE_DO_NOT_USE"
        self.devices = "List of devices attached\nTEST_DEVICE_DO_NOT_USE device product:x model:SM-S928N\n"
        self.properties = {
            "ro.product.manufacturer": "samsung",
            "ro.product.model": "SM-S928N",
            "ro.build.version.release": "17",
            "ro.build.version.sdk": "37",
            "ro.build.version.oneui": "90000",
        }
        self.packages = "package:com.studyone.app versionCode:30001\n"

    def check(self, *, serial=None):
        seen = []

        def mock_adb(argv, executable):
            self.assertEqual(executable, "adb")
            seen.append(argv)
            if argv == ["devices", "-l"]:
                return self.devices
            self.assertEqual(argv[:4], ["-s", self.serial, "shell", argv[3]])
            if argv[3] == "getprop":
                return self.properties[argv[4]]
            self.assertEqual(argv[3:], ["pm", "list", "packages", "--user", "0",
                                        "--show-versioncode", "com.studyone.app"])
            return self.packages

        with patch("scripts.p5_android_device_preflight._adb", side_effect=mock_adb):
            result = inspect_device(serial=serial)
        self.assertTrue(all("install" not in " ".join(x) for x in seen))
        self.assertFalse(any("pull" in x or "push" in x for x in seen))
        return result

    def test_android17_s24u_read_only_and_redacted(self):
        report = self.check()
        self.assertEqual(report["androidApi"], 37)
        self.assertEqual(report["officialInstalledVersionCode"], 30001)
        self.assertEqual(report["packageState"], "PRODUCTION_PRESENT")
        self.assertNotIn(self.serial, str(report))
        for key in ("installedSignerVerified", "backupPerformed", "upgradePerformed",
                    "personalDataRead", "realDeviceRegressionPassed"):
            self.assertFalse(report[key])

    def test_canary_only_never_marked_production(self):
        self.packages = "package:com.studyone.app.canary7 versionCode:30107"
        report = self.check()
        self.assertEqual(report["packageState"], "CANARY_ONLY")
        self.assertIsNone(report["officialInstalledVersionCode"])

    def test_both_installations_remain_distinct(self):
        self.packages += "package:com.studyone.app.canary7 versionCode:30107\n"
        report = self.check()
        self.assertEqual(report["packageState"], "PRODUCTION_AND_CANARY_PRESENT")
        self.assertTrue(report["isolatedCanaryDetected"])

    def test_missing_package_is_not_an_upgrade_pass(self):
        self.packages = ""
        report = self.check()
        self.assertEqual(report["packageState"], "NO_STUDYONE_PACKAGE_FOUND")
        self.assertFalse(report["upgradePerformed"])

    def test_wrong_model_or_os_fails_closed(self):
        self.properties["ro.product.model"] = "SM-S921N"
        with self.assertRaisesRegex(DevicePreflightError, "S24_ULTRA"):
            self.check()
        self.properties["ro.product.model"] = "SM-S928N"
        self.properties["ro.build.version.sdk"] = "36"
        with self.assertRaisesRegex(DevicePreflightError, "ANDROID_17"):
            self.check()

    def test_one_ui_property_unavailable_is_manual_not_verified(self):
        self.properties["ro.build.version.oneui"] = ""
        result = self.check()
        self.assertIsNone(result["oneUiRawProperty"])
        self.assertFalse(result["oneUiVerifiedFromSettings"])

    def test_requires_one_authorized_device_or_selector(self):
        with self.assertRaisesRegex(DevicePreflightError, "EXACTLY_ONE"):
            _select_serial("List of devices attached\n", None)
        with self.assertRaisesRegex(DevicePreflightError, "EXACTLY_ONE"):
            _select_serial(self.devices + "SYNTHETIC_2 device\n", None)
        with self.assertRaisesRegex(DevicePreflightError, "NOT_AUTHORIZED"):
            _select_serial(self.devices.replace(" device ", " unauthorized "), None)
        result = self.check(serial=self.serial)
        self.assertEqual(result["model"], "SM-S928N")

    def test_invalid_selector_and_malformed_package_blocked(self):
        with self.assertRaisesRegex(DevicePreflightError, "SELECTOR_INVALID"):
            _select_serial(self.devices, "--bad value")
        for data in ("package:com.studyone.app", "package:com.studyone.app versionCode:0",
                     "package:com.other.app versionCode:42"):
            with self.subTest(data=data), self.assertRaises(DevicePreflightError):
                _installed_packages(data)

    def test_pm_version_duplicates_fails_closed(self):
        with self.assertRaisesRegex(DevicePreflightError, "UNEXPECTED"):
            _installed_packages("package:com.studyone.app versionCode:1\n"
                                "package:com.studyone.app versionCode:2\n")

    def test_partial_success_with_profile_security_exception_is_blocked(self):
        # Mirrors Samsung's protected-profile output; never treat a partial
        # package record as a full successful inventory.
        partial = CompletedProcess(
            args=["adb"], returncode=0,
            stdout="package:com.studyone.app versionCode:30106\n",
            stderr=("Error: java.lang.SecurityException: Shell does not have "
                    "permission to access user 150\n"),
        )
        with patch("scripts.p5_android_device_preflight.subprocess.run", return_value=partial):
            with self.assertRaisesRegex(DevicePreflightError, "ADB_READ_FAILED"):
                _adb(["shell", "pm", "list", "packages"], "adb")


if __name__ == "__main__":
    unittest.main()
