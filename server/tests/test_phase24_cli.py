"""Phase24 operator CLI E2E with temporary SQLite, no live device or accounts."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

from server.private_sync import Registry


class OperatorCliTests(unittest.TestCase):
    def setUp(self):
        self.root = TemporaryDirectory(prefix="studyone-cli-auth-")
        self.addCleanup(self.root.cleanup)
        base = Path(self.root.name)
        self.database = base / "auth.sqlite3"
        self.records = base / "records"
        self.entry = Path(__file__).resolve().parents[1] / "private_sync.py"

    def command(self, *args):
        result = subprocess.run([
            sys.executable, str(self.entry),
            "--registry", str(self.database),
            "--data-root", str(self.records), *args
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
           check=True, timeout=10)
        return result.stdout.strip()

    def test_create_list_rotate_and_revoke_mcp_device_without_secrets_in_listing(self):
        account = json.loads(self.command(
            "create-account", "--label", "fixture-account"))["accountId"]
        issued = json.loads(self.command(
            "issue-assistant", "--account", account,
            "--label", "fixture-assistant", "--days", "7"))
        self.assertEqual(issued["audience"], "mcp")
        self.assertEqual(issued["scope"], "read")
        listing = self.command("list-devices", "--account", account)
        self.assertEqual(len(json.loads(listing)["devices"]), 1)
        self.assertNotIn(issued["token"], listing)
        self.assertNotIn("digest", listing)
        old = issued["token"]
        rotated = json.loads(self.command(
            "rotate-device", "--device", issued["deviceId"], "--days", "2"))
        self.assertNotEqual(rotated["token"], old)
        registry = Registry(self.database, self.records)
        with self.assertRaises(Exception):
            registry.authorize(old, allowed_audiences=("mcp",))
        self.assertEqual(registry.authorize(
            rotated["token"], allowed_audiences=("mcp",))[0], account)
        self.assertNotIn(rotated["token"].encode(), self.database.read_bytes())
        self.command("revoke-device", "--device", issued["deviceId"])
        with self.assertRaises(Exception):
            registry.authorize(rotated["token"], allowed_audiences=("mcp",))


if __name__ == "__main__":
    unittest.main()
