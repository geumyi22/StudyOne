"""Non-destructive SQLite backup + checksum + independent restore rehearsal."""
from __future__ import annotations

import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from server.private_sync import Registry
from server.recovery import create_backup, verify_backup


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix="studyone-backup-fixture-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.registry_path = root / "live" / "registry.sqlite3"
        self.records_path = root / "live" / "accounts"
        self.backups = root / "backup_dest"
        self.backups.mkdir()
        self.registry = Registry(self.registry_path, self.records_path)
        self.first = self.registry.account("synth-1")
        self.second = self.registry.account("synth-2")
        self.registry.store(self.second)  # Initialize second account's DB before offline backup.
        self.token = self.registry.device(self.first, "test-device")
        self.dataset = self.registry.store(self.first)
        self.dataset.apply({
            "requestId": str(uuid4()),
            "mutations": [{
                "kind": "task", "id": "31", "baseRevision": 0,
                "deleted": False, "payload": {
                    "id": 31, "type": "숙제 항목", "subject": "국어",
                    "title": "합성 임시 테스트", "dueDate": "2026-10-22",
                    "importance": 2, "completed": False
                }
            }]
        })

    def backup(self):
        return self.backups / "snapshot1"

    def test_successful_backup_rehearsal_and_original_unchanged(self):
        before = self.dataset.snapshot()
        result = create_backup(
            self.registry_path, self.records_path, self.backup(), stopped=True
        )
        self.assertEqual(result["databases"], 3)
        self.assertTrue(result["verified"])
        report = verify_backup(self.backup())
        self.assertEqual(report["integrity"], "PASS")
        self.assertEqual(report["restoreRehearsal"], "PASS")
        self.assertFalse(report["encrypted"])
        self.assertEqual(self.dataset.snapshot(), before)
        manifest = json.loads((self.backup() / "manifest.json").read_text())
        self.assertEqual(len(manifest["files"]), 3)
        self.assertFalse(manifest["storageEncrypted"])
        self.assertNotIn(self.token["token"], (self.backup() / "manifest.json").read_text())

    def test_backup_is_never_automatic_and_never_overwrites(self):
        with self.assertRaises(ValueError):
            create_backup(self.registry_path, self.records_path, self.backup())
        self.assertFalse(self.backup().exists())
        create_backup(self.registry_path, self.records_path, self.backup(), stopped=True)
        with self.assertRaises(ValueError):
            create_backup(self.registry_path, self.records_path, self.backup(), stopped=True)
        self.assertTrue(self.backup().exists())

    def test_tampered_account_db_or_manifest_is_detected(self):
        create_backup(self.registry_path, self.records_path, self.backup(), stopped=True)
        backup_db = self.backup() / "accounts" / (self.first + ".sqlite3")
        with backup_db.open("ab") as writer:
            writer.write(b"tampered")
        with self.assertRaises(ValueError):
            verify_backup(self.backup())

    def test_account_inventory_is_complete_or_aborts(self):
        missing = self.records_path / (self.second + ".sqlite3")
        self.assertTrue(missing.exists())
        missing.unlink()
        with self.assertRaises(ValueError):
            create_backup(self.registry_path, self.records_path, self.backup(), stopped=True)
        self.assertFalse(self.backup().exists())

    def test_reject_account_path_injection(self):
        create_backup(self.registry_path, self.records_path, self.backup(), stopped=True)
        manifest_path = self.backup() / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"]["../unexpected.sqlite3"] = {
            "sha256": "x" * 64, "bytes": 55
        }
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(ValueError):
            verify_backup(self.backup())

    def test_no_destination_inside_live_source(self):
        target = self.records_path / "unexpected-backup"
        with self.assertRaises(ValueError):
            create_backup(self.registry_path, self.records_path, target, stopped=True)
        self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
