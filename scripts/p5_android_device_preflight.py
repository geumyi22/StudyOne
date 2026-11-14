#!/usr/bin/env python3
"""Read-only StudyOne P5 inventory for a user-authorized Galaxy S24 Ultra.

Requires Android SDK ``adb`` and USB-debugging authorization. This tool only
reads device build properties and StudyOne package *metadata*. It never installs,
uninstalls, pulls files, opens app data, takes a backup, or uploads a report.
Its output intentionally excludes the device serial number and personal data.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys


OFFICIAL_APP_ID = "com.studyone.app"
CANARY_APP_ID = "com.studyone.app.canary7"
EXPECTED_ANDROID_RELEASE = "17"
EXPECTED_ANDROID_API = "37"
SERIAL_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.:\-]*\Z")
PACKAGE_PATTERN = re.compile(r"package:([A-Za-z][A-Za-z0-9_.]*)\s+versionCode:(\d+)\Z")


class DevicePreflightError(ValueError):
    """A blocked or ambiguous read-only device check."""


def _adb(argv: list[str], executable: str) -> str:
    try:
        result = subprocess.run([executable, *argv], capture_output=True,
                                text=True, encoding="utf-8", errors="replace",
                                timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DevicePreflightError("ADB_NOT_AVAILABLE_OR_TIMED_OUT") from exc
    # Some vendor package managers print a user-profile SecurityException after
    # partial package output. Reject that output even if adb exits with code 0.
    combined = result.stdout + "\n" + result.stderr
    reported_error = re.search(r"(?im)(securityexception|permission denied|^error:)", combined)
    if result.returncode != 0 or len(result.stdout) > 12_000 or reported_error:
        # Do not reveal stderr: it can include the device serial number.
        raise DevicePreflightError("ADB_READ_FAILED")
    return result.stdout.strip()


def _select_serial(devices: str, requested: str | None) -> str:
    if requested is not None and not SERIAL_PATTERN.fullmatch(requested):
        raise DevicePreflightError("DEVICE_SELECTOR_INVALID")
    entries: list[tuple[str, str]] = []
    for line in devices.splitlines():
        line = line.strip()
        if not line or line == "List of devices attached":
            continue
        columns = line.split()
        if len(columns) < 2 or not SERIAL_PATTERN.fullmatch(columns[0]):
            raise DevicePreflightError("AMBIGUOUS_DEVICE_LIST")
        entries.append((columns[0], columns[1]))
    if requested is None:
        if len(entries) != 1:
            raise DevicePreflightError("EXACTLY_ONE_DEVICE_OR_EXPLICIT_SERIAL_REQUIRED")
        serial, state = entries[0]
    else:
        matches = [(s, state) for s, state in entries if s == requested]
        if len(matches) != 1:
            raise DevicePreflightError("SELECTED_DEVICE_NOT_FOUND")
        serial, state = matches[0]
    if state != "device":
        raise DevicePreflightError("DEVICE_NOT_AUTHORIZED_OR_OFFLINE")
    return serial


def _value(text: str, label: str, *, optional: bool = False) -> str:
    if "\n" in text or "\r" in text or len(text) > 100:
        raise DevicePreflightError("INVALID_" + label)
    if not text and not optional:
        raise DevicePreflightError("MISSING_" + label)
    return text


def _installed_packages(text: str) -> dict[str, int]:
    found: dict[str, int] = {}
    for line in text.splitlines():
        match = PACKAGE_PATTERN.fullmatch(line.strip())
        if match is None:
            raise DevicePreflightError("PACKAGE_VERSION_METADATA_UNREADABLE")
        package, version = match.group(1), int(match.group(2))
        if package not in (OFFICIAL_APP_ID, CANARY_APP_ID) or package in found or version <= 0:
            raise DevicePreflightError("PACKAGE_VERSION_METADATA_UNEXPECTED")
        found[package] = version
    return found


def inspect_device(*, adb: str = "adb", serial: str | None = None) -> dict:
    """Inspect a device selected via adb, without reading app or user records."""
    selected = _select_serial(_adb(["devices", "-l"], adb), serial)

    def prop(name: str, label: str, *, optional: bool = False) -> str:
        return _value(_adb(["-s", selected, "shell", "getprop", name], adb),
                      label, optional=optional)

    manufacturer = prop("ro.product.manufacturer", "MANUFACTURER")
    model = prop("ro.product.model", "MODEL")
    release = prop("ro.build.version.release", "ANDROID_RELEASE")
    api = prop("ro.build.version.sdk", "ANDROID_API")
    one_ui_raw = prop("ro.build.version.oneui", "ONE_UI", optional=True)
    if manufacturer.lower() != "samsung" or not (
        model.upper().startswith("SM-S928") or "GALAXY S24 ULTRA" in model.upper()
    ):
        raise DevicePreflightError("GALAXY_S24_ULTRA_DEVICE_REQUIRED")
    if release != EXPECTED_ANDROID_RELEASE or api != EXPECTED_ANDROID_API:
        raise DevicePreflightError("ANDROID_17_API_37_ENVIRONMENT_MISMATCH")

    packages = _installed_packages(_adb([
        # Restrict the query to the owner's Android user 0. An unscoped query
        # may traverse a protected profile (e.g. user 150) and raise an error.
        "-s", selected, "shell", "pm", "list", "packages", "--user", "0",
        "--show-versioncode", OFFICIAL_APP_ID
    ], adb))
    official = packages.get(OFFICIAL_APP_ID)
    canary = packages.get(CANARY_APP_ID)
    status = (
        "PRODUCTION_AND_CANARY_PRESENT" if official is not None and canary is not None
        else "PRODUCTION_PRESENT" if official is not None
        else "CANARY_ONLY" if canary is not None else "NO_STUDYONE_PACKAGE_FOUND"
    )
    return {
        "status": "READ_ONLY_DEVICE_METADATA_INSPECTED",
        "manufacturer": manufacturer,
        "model": model,
        "androidRelease": release,
        "androidApi": int(api),
        "oneUiRawProperty": one_ui_raw or None,
        "oneUiVerifiedFromSettings": False,
        "deviceSerialIncluded": False,
        "packageState": status,
        "officialAppId": OFFICIAL_APP_ID,
        "officialInstalledVersionCode": official,
        "isolatedCanaryDetected": canary is not None,
        "installedSignerVerified": False,
        "personalDataRead": False,
        "backupPerformed": False,
        "upgradePerformed": False,
        "realDeviceRegressionPassed": False,
        "next": [
            "Confirm One UI 9 manually in Settings if needed",
            "Independently verify existing installation signature and protected four-store backup",
            "Inspect a separate candidate APK with scripts/p5_android_apk_preflight.py",
            "Only after explicit authorization: perform in-place signed update and four-store regression"
        ]
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="Path to official Android SDK adb executable")
    parser.add_argument("--serial", help="Explicitly select a device (never included in output)")
    args = parser.parse_args()
    try:
        result = inspect_device(adb=args.adb, serial=args.serial)
    except DevicePreflightError as exc:
        print(json.dumps({"status": "BLOCKED", "reason": str(exc)}), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
