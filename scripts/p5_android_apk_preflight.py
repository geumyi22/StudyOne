#!/usr/bin/env python3
"""Read-only StudyOne v4 Android signed-APK candidate preflight.

Never installs, uninstalls, pulls from a device, connects to the network or
opens user records. Running it is not proof of device/backup/restore QA.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import BadZipFile, ZipFile


OFFICIAL_APP_ID = "com.studyone.app"
# SHA-256 digest of the existing official Android *certificate*, not a key.
OFFICIAL_CERT_SHA256 = "3f95e9aaecb32ae66c86dbd6795b0a49118f8dacf515f7fbe31795970b1681ed"
V4_VERSIONS = {"4.0.0-beta.1": 40001, "4.0.0": 40002}
MAX_APK_BYTES = 200 * 1024 * 1024


class PreflightError(ValueError):
    """Fail closed on an incompatible or unverified candidate."""


def _output(command: list[str]) -> str:
    """Only read-only Android SDK inspection commands, never adb install."""
    try:
        result = subprocess.run(command, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=30,
                                check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PreflightError("ANDROID_SDK_INSPECTOR_UNAVAILABLE") from exc
    if result.returncode != 0 or len(result.stdout) > 256_000:
        raise PreflightError("ANDROID_SDK_INSPECTION_FAILED")
    return result.stdout


def _badging_metadata(text: str) -> tuple[str, int, str]:
    """Read the single aapt2 package record, including exact version labels."""
    lines = [line for line in text.splitlines() if line.startswith("package: ")]
    if len(lines) != 1:
        raise PreflightError("INVALID_PACKAGE_METADATA")
    line = lines[0]
    values = {}
    for name in ("name", "versionCode", "versionName"):
        hits = re.findall(r"(?:^|\s)" + name + r"='([^']*)'", line)
        if len(hits) != 1:
            raise PreflightError("INVALID_PACKAGE_METADATA")
        values[name] = hits[0]
    if not values["versionCode"].isdigit():
        raise PreflightError("INVALID_VERSION_CODE")
    return values["name"], int(values["versionCode"]), values["versionName"]


def _signer_digest(text: str) -> str:
    count = re.findall(r"(?m)^Number of signers:\s*(\d+)\s*$", text)
    if count != ["1"]:
        raise PreflightError("SINGLE_SIGNER_REQUIRED")
    schemes = re.findall(r"(?m)^Verified using v([23]) scheme[^:]*:\s*(true|false)\s*$",
                         text, flags=re.IGNORECASE)
    if not any(version == "2" and verified.lower() == "true"
               for version, verified in schemes):
        raise PreflightError("APK_V2_SIGNATURE_REQUIRED")
    digests = re.findall(r"(?m)^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
                         text)
    if len(digests) != 1 or digests[0].lower() != OFFICIAL_CERT_SHA256:
        raise PreflightError("OFFICIAL_SIGNER_MISMATCH")
    return digests[0].lower()


def inspect_apk(apk: Path, *, installed_version_code: int | None = None,
                expected_sha256: str | None = None,
                aapt2: str = "aapt2", apksigner: str = "apksigner") -> dict:
    """Verify candidate metadata only. No device or installation is accessed."""
    if apk.is_symlink() or not apk.is_file():
        raise PreflightError("APK_FILE_MISSING_OR_SYMLINK")
    size = apk.stat().st_size
    if not 0 < size <= MAX_APK_BYTES:
        raise PreflightError("APK_SIZE_INVALID")
    try:
        with ZipFile(apk) as bundle:
            entries = bundle.infolist()
            if (not entries or len(entries) > 50_000 or
                    sum(item.file_size for item in entries) > 2 * MAX_APK_BYTES or
                    bundle.testzip() is not None):
                raise PreflightError("APK_ARCHIVE_CORRUPTED")
    except BadZipFile as exc:
        raise PreflightError("APK_ARCHIVE_CORRUPTED") from exc

    with apk.open("rb") as reader:
        digest = hashlib.file_digest(reader, "sha256").hexdigest()
    if expected_sha256 is not None:
        if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256):
            raise PreflightError("EXPECTED_CHECKSUM_INVALID")
        if digest != expected_sha256.lower():
            raise PreflightError("APK_CHECKSUM_MISMATCH")

    name, version_code, version_name = _badging_metadata(
        _output([aapt2, "dump", "badging", str(apk)]))
    if name != OFFICIAL_APP_ID:
        raise PreflightError("OFFICIAL_PACKAGE_ID_REQUIRED")
    if V4_VERSIONS.get(version_name) != version_code:
        raise PreflightError("V4_VERSION_PAIR_REQUIRED")
    if installed_version_code is not None:
        if (type(installed_version_code) is not int or
                installed_version_code <= 0 or
                installed_version_code >= version_code):
            raise PreflightError("UPGRADE_VERSION_NOT_INCREASING")

    signer = _signer_digest(_output(
        [apksigner, "verify", "--verbose", "--print-certs", str(apk)]))
    return {
        "status": "CANDIDATE_METADATA_VERIFIED_ONLY",
        "apkSha256": digest,
        "independentlyCheckedSha256": expected_sha256 is not None,
        "apkBytes": size,
        "packageId": name,
        "versionName": version_name,
        "versionCode": version_code,
        "trustedCertificateSha256": signer,
        "installedVersionCodeSupplied": installed_version_code is not None,
        "deviceVerified": False,
        "safeToInstallAutomatically": False,
        "pending": [
            "Verify the S24 Ultra installed app's signing identity and version",
            "Export and independently verify four personal data stores before testing",
            "Perform a separate signed in-place upgrade on an approved test device",
            "Check actual app/data rollback and manual P5 regression on the device"
        ]
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", required=True, type=Path)
    p.add_argument("--installed-version-code", type=int,
                   help="Operator-verified current code; not read from a device")
    p.add_argument("--expected-sha256",
                   help="Digest from an independently trusted release manifest")
    p.add_argument("--aapt2", default="aapt2")
    p.add_argument("--apksigner", default="apksigner")
    args = p.parse_args()
    try:
        result = inspect_apk(args.apk,
                             installed_version_code=args.installed_version_code,
                             expected_sha256=args.expected_sha256,
                             aapt2=args.aapt2, apksigner=args.apksigner)
    except PreflightError as exc:
        print(json.dumps({"status": "BLOCKED", "reason": str(exc)}), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
