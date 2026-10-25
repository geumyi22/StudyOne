"""Phase24 account and MCP audience lifecycle tests (synthetic identities only).

No production accounts, server ports, credentials or learned student data.
"""
from __future__ import annotations

import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from server.private_sync import Registry, handler_for, token_digest


class AuthenticationLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory(prefix="studyone-phase24-fixture-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.auth = self.root / "auth.sqlite3"
        self.store = self.root / "accounts"
        self.registry = Registry(self.auth, self.store)
        self.alice = self.registry.account("alice-fixture")
        self.bob = self.registry.account("bob-fixture")
        self.device = self.registry.device(self.alice, "phone", "read-write", days=30)
        self.assistant = self.registry.device(
            self.alice, "chatgpt-development", "read", days=7, audience="mcp"
        )

    def test_read_only_assistant_cannot_acquire_write_or_device_audience(self):
        self.assertEqual(self.registry.authorize(
            self.assistant["token"], allowed_audiences=("mcp",))[0], self.alice)
        with self.assertRaises(Exception):
            self.registry.authorize(self.assistant["token"])
        with self.assertRaises(Exception):
            self.registry.authorize(self.assistant["token"], write=True,
                                    allowed_audiences=("mcp",))
        with self.assertRaises(ValueError):
            self.registry.device(self.alice, "unrestricted-ai", "read-write",
                                 audience="mcp")

    def test_atomic_rotation_invalidates_old_secret_and_retains_restrictions(self):
        before = self.assistant["token"]
        rotated = self.registry.rotate(self.assistant["deviceId"], days=12)
        self.assertNotEqual(before, rotated["token"])
        self.assertEqual(rotated["audience"], "mcp")
        self.assertEqual(rotated["scope"], "read")
        with self.assertRaises(Exception):
            self.registry.authorize(before, allowed_audiences=("mcp",))
        self.assertEqual(self.registry.authorize(
            rotated["token"], allowed_audiences=("mcp",))[0], self.alice)
        with self.assertRaises(Exception):
            self.registry.authorize(rotated["token"])
        image = self.auth.read_bytes()
        self.assertNotIn(before.encode(), image)
        self.assertNotIn(rotated["token"].encode(), image)
        self.assertNotIn(token_digest(before).encode(), image)
        self.assertIn(token_digest(rotated["token"]).encode(), image)
        self.registry.revoke(self.assistant["deviceId"])
        with self.assertRaises(Exception):
            self.registry.authorize(rotated["token"], allowed_audiences=("mcp",))
        with self.assertRaises(ValueError):
            self.registry.rotate(self.assistant["deviceId"])

    def test_rotation_refuses_disabled_expired_or_unknown_credentials(self):
        with self.assertRaises(ValueError):
            self.registry.rotate(str(uuid4()), days=4)
        with self.assertRaises(ValueError):
            self.registry.rotate(self.device["deviceId"], days=0)
        with self.registry.connect() as db:
            db.execute("UPDATE devices SET expires_at=0 WHERE id=?",
                       (self.device["deviceId"],))
        with self.assertRaises(ValueError):
            self.registry.rotate(self.device["deviceId"])
        self.registry.disable(self.alice)
        with self.assertRaises(ValueError):
            self.registry.rotate(self.assistant["deviceId"])

    def test_operator_inventory_never_reveals_secret_or_digest(self):
        items = self.registry.list_devices(self.alice)
        self.assertEqual(len(items), 2)
        self.assertEqual({i["audience"] for i in items}, {"device", "mcp"})
        raw = json.dumps(items)
        self.assertNotIn(self.device["token"], raw)
        self.assertNotIn(self.assistant["token"], raw)
        self.assertNotIn(token_digest(self.device["token"]), raw)
        self.assertNotIn(token_digest(self.assistant["token"]), raw)
        self.assertEqual(self.registry.list_devices(self.bob), [])
        with self.assertRaises(ValueError):
            self.registry.list_devices(str(uuid4()))

    def test_legacy_registry_auto_migration_preserves_device_access(self):
        legacy = self.root / "legacy.sqlite3"
        with sqlite3.connect(legacy) as db:
            db.execute("CREATE TABLE accounts(id TEXT PRIMARY KEY, label TEXT NOT NULL,"
                       " created_at INTEGER NOT NULL, disabled INTEGER NOT NULL DEFAULT 0)")
            db.execute("CREATE TABLE devices(id TEXT PRIMARY KEY, account_id TEXT NOT NULL,"
                       " label TEXT NOT NULL, digest TEXT NOT NULL UNIQUE,"
                       " scope TEXT NOT NULL, issued_at INTEGER NOT NULL,"
                       " expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)")
            db.execute("CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                       " timestamp INTEGER NOT NULL, account_id TEXT NOT NULL,"
                       " device_id TEXT, action TEXT NOT NULL, detail TEXT NOT NULL)")
        upgraded = Registry(legacy, self.root / "legacy-records")
        a = upgraded.account("legacy-owner")
        token = upgraded.device(a, "device-old")["token"]
        # Mimic a pre-Phase24 columnless registry with a previously issued token.
        with upgraded.connect() as db:
            device_id = db.execute("SELECT id FROM devices WHERE digest=?",
                                   (token_digest(token),)).fetchone()["id"]
            db.execute("PRAGMA foreign_keys=OFF")
        # Historical schemas were exactly what we created; migration is exercised
        # above by Registry(legacy) adding the missing audience column.
        self.assertEqual(upgraded.authorize(token)[0], a)
        self.assertEqual(upgraded.list_devices(a)[0]["audience"], "device")
        self.assertTrue(device_id)


class HttpAudienceTests(AuthenticationLifecycleTests):
    def setUp(self):
        super().setUp()
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, tls_enabled=False))
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def fetch(self, token, path, method="GET", body=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Authorization": "Bearer " + token}
        if data is not None:
            headers["Content-Type"] = "application/json"
        query = Request(self.url + path, method=method, data=data, headers=headers)
        try:
            with urlopen(query, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as failure:
            return failure.code, json.loads(failure.read())

    def test_mcp_token_rejected_from_regular_sync_and_approval(self):
        ai = self.assistant["token"]
        self.assertEqual(self.fetch(ai, "/v2/snapshot")[0], 403)
        self.assertEqual(self.fetch(ai, "/v2/changes?after=0")[0], 403)
        self.assertEqual(self.fetch(ai, "/v2/proposals")[0], 403)
        self.assertEqual(self.fetch(ai, "/v2/changes", "POST",
                                    {"requestId": str(uuid4()), "mutations": []})[0], 403)
        self.assertEqual(self.fetch(ai, "/v2/proposals/" + str(uuid4()) + "/approve",
                                    "POST", {})[0], 403)
        code, started = self.fetch(ai, "/mcp", "POST",
            {"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        self.assertEqual(code, 200)
        self.assertEqual(started["result"]["serverInfo"]["name"], "StudyOne")
        code, tools = self.fetch(ai, "/mcp", "POST",
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual(code, 200)
        self.assertEqual(len(tools["result"]["tools"]), 4)
        self.assertEqual(self.fetch(self.device["token"], "/v2/snapshot")[0], 200)

    def test_mcp_token_cross_account_no_raw_api_and_revoke(self):
        ai = self.assistant["token"]
        own = self.fetch(ai, "/mcp", "POST", {"jsonrpc": "2.0",
            "id": 3, "method": "tools/call",
            "params": {"name": "studyone_read_snapshot", "arguments": {}}})
        self.assertEqual(own[0], 200)
        self.assertEqual(own[1]["result"]["isError"], False)
        self.assertEqual(self.registry.store(self.bob).snapshot()["records"], [])
        self.registry.revoke(self.assistant["deviceId"])
        self.assertEqual(self.fetch(ai, "/mcp", "POST",
             {"jsonrpc": "2.0", "id": 4, "method": "tools/list"})[0], 401)


if __name__ == "__main__":
    unittest.main()
