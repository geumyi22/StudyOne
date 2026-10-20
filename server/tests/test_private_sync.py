"""Private HTTPS account isolation and token revocation tests (synthetic only)."""
from __future__ import annotations

import json
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from server.private_sync import Registry, handler_for, serve, token_digest
from server.studyone_sync import ThreadingHTTPServer


def task(identifier, title):
    return {"id": int(identifier), "type": "숙제 항목", "subject": "과학",
            "title": title, "dueDate": "2026-11-03",
            "importance": 2, "completed": False}


def mutation(identifier, title):
    return {"kind": "task", "id": identifier, "baseRevision": 0,
            "deleted": False, "payload": task(identifier, title)}


def request(identifier, title, key=None):
    return {"requestId": key or str(uuid4()), "mutations": [mutation(identifier, title)]}


class RegistryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="studyone-private-")
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.registry = Registry(root / "auth.sqlite3", root / "records")
        self.a = self.registry.account("A test")
        self.b = self.registry.account("B test")
        self.a_rw = self.registry.device(self.a, "a-windows")
        self.b_rw = self.registry.device(self.b, "b-android")
        self.a_read = self.registry.device(self.a, "a-read", "read")

    def test_no_raw_tokens_on_disk_and_scope(self):
        token = self.a_rw["token"]
        dbbytes = Path(self.registry.path).read_bytes()
        self.assertNotIn(token.encode(), dbbytes)
        self.assertIn(token_digest(token).encode(), dbbytes)
        self.assertEqual(self.registry.authorize(token, write=True)[0], self.a)
        self.assertEqual(self.registry.authorize(self.a_read["token"])[0], self.a)
        with self.assertRaises(Exception):
            self.registry.authorize(self.a_read["token"], write=True)
        with self.assertRaises(Exception):
            self.registry.authorize("wrong")

    def test_device_revoke_and_disable_account(self):
        self.registry.revoke(self.a_rw["deviceId"])
        with self.assertRaises(Exception):
            self.registry.authorize(self.a_rw["token"])
        self.assertEqual(self.registry.authorize(self.a_read["token"])[0], self.a)
        self.registry.disable(self.a)
        with self.assertRaises(Exception):
            self.registry.authorize(self.a_read["token"])
        self.assertEqual(self.registry.authorize(self.b_rw["token"])[0], self.b)

    def test_expired_device_is_rejected(self):
        with self.registry.connect() as db:
            db.execute("UPDATE devices SET expires_at=0 WHERE id=?",
                       (self.a_rw["deviceId"],))
        with self.assertRaises(Exception):
            self.registry.authorize(self.a_rw["token"])

    def test_cross_account_database_isolation(self):
        a = self.registry.store(self.a)
        b = self.registry.store(self.b)
        a.apply(request("111", "A-data", "e1ed246b-3412-4e6b-81b0-110e3f1769a2"))
        self.assertEqual(a.snapshot()["records"][0]["payload"]["title"], "A-data")
        self.assertEqual(b.snapshot()["records"], [])
        # Same client requestId and numeric task ID are permitted in distinct owners.
        b.apply(request("111", "B-data", "e1ed246b-3412-4e6b-81b0-110e3f1769a2"))
        self.assertEqual(b.snapshot()["records"][0]["payload"]["title"], "B-data")
        self.assertNotEqual(a.snapshot()["records"][0]["payload"]["title"],
                            b.snapshot()["records"][0]["payload"]["title"])
        self.assertNotEqual(a.db_path, b.db_path)

    def test_accounts_cannot_escape_file_root(self):
        with self.assertRaises(ValueError):
            self.registry.store("../../victim.sqlite3")
        with self.assertRaises(ValueError):
            self.registry.device("../../bad", "bad")

    def test_secure_server_requires_certificate(self):
        with self.assertRaises(RuntimeError):
            serve(self.registry, "127.0.0.1", 0, None, None)
        with self.assertRaises(RuntimeError):
            serve(self.registry, "0.0.0.0", 0, "missing.pem", "missing.pem")

    def test_audit_does_not_record_payloads(self):
        with self.registry.connect() as db:
            db.execute("SELECT COUNT(*) FROM audit").fetchone()
        self.registry.audit_write(self.a, self.a_rw["deviceId"], 1)
        with self.registry.connect() as db:
            rows = db.execute("SELECT action,detail FROM audit ORDER BY id").fetchall()
        self.assertEqual(rows[-1]["action"], "SYNC_MUTATIONS")
        self.assertEqual(rows[-1]["detail"], "1")


class HttpTests(RegistryTests):
    def setUp(self):
        super().setUp()
        # Isolated loopback HTTP listener only in unit tests; production serve()
        # requires valid TLS keys, and cannot bind remote without approval.
        self.http = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, tls_enabled=False))
        self.worker = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.http.server_close)
        self.addCleanup(self.http.shutdown)
        self.url = "http://127.0.0.1:" + str(self.http.server_port)

    def send(self, token, method, path, payload=None, extra_headers=None):
        raw = json.dumps(payload).encode() if payload is not None else None
        headers = {"Authorization": "Bearer " + token}
        if raw is not None:
            headers["Content-Type"] = "application/json"
        if extra_headers:
            headers.update(extra_headers)
        req = Request(self.url + path, data=raw, headers=headers, method=method)
        try:
            with urlopen(req, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as err:
            return err.code, json.loads(err.read())

    def test_http_isolation_and_conflict(self):
        status, accepted = self.send(self.a_rw["token"], "POST",
                                     "/v2/changes", request("11", "A"))
        self.assertEqual(status, 200)
        self.assertEqual(len(accepted["changes"]), 1)
        _, a = self.send(self.a_rw["token"], "GET", "/v2/snapshot")
        self.assertEqual(len(a["records"]), 1)
        _, b = self.send(self.b_rw["token"], "GET", "/v2/snapshot")
        self.assertEqual(b["records"], [])
        # Other owner's device cannot update owner's record.
        duplicate_id = request("11", "B")
        status, _ = self.send(self.b_rw["token"], "POST",
                              "/v2/changes", duplicate_id)
        self.assertEqual(status, 200)
        _, a_after = self.send(self.a_rw["token"], "GET", "/v2/snapshot")
        self.assertEqual(a_after["records"][0]["payload"]["title"], "A")
        status, error = self.send(self.a_rw["token"], "POST",
                                  "/v2/changes", request("11", "overwrite"))
        self.assertEqual((status, error["error"]), (409, "REVISION_CONFLICT"))

    def test_read_only_rejected_on_write(self):
        status, _ = self.send(self.a_read["token"], "GET", "/v2/snapshot")
        self.assertEqual(status, 200)
        status, err = self.send(self.a_read["token"], "POST",
                                "/v2/changes", request("12", "test"))
        self.assertEqual((status, err["error"]), (403, "WRITE_FORBIDDEN"))

    def test_token_revoke_effective_immediately(self):
        status, _ = self.send(self.a_rw["token"], "GET", "/v2/snapshot")
        self.assertEqual(status, 200)
        self.registry.revoke(self.a_rw["deviceId"])
        status, err = self.send(self.a_rw["token"], "GET", "/v2/snapshot")
        self.assertEqual((status, err["error"]), (401, "UNAUTHORIZED"))

    def test_invalid_origin_and_transfer_encoding(self):
        status, err = self.send(self.a_rw["token"], "GET", "/v2/snapshot",
                                extra_headers={"Origin": "https://example.com"})
        self.assertEqual((status, err["error"]), (403, "BROWSER_ORIGIN_UNSUPPORTED"))
        status, err = self.send(self.a_rw["token"], "GET", "/v2/snapshot",
                                extra_headers={"Transfer-Encoding": "chunked"})
        self.assertEqual((status, err["error"]), (400, "TRANSFER_ENCODING_UNSUPPORTED"))

    def test_unknown_endpoints_do_not_leak_account(self):
        status, err = self.send("unauthorized", "GET", "/v2/unknown")
        self.assertEqual((status, err["error"]), (404, "NOT_FOUND"))

    def test_no_browser_cors_headers(self):
        headers = {"Authorization": "Bearer " + self.a_rw["token"]}
        with urlopen(Request(self.url + "/v2/snapshot", headers=headers), timeout=4) as res:
            self.assertEqual(res.status, 200)
            self.assertEqual(res.headers["Cache-Control"], "no-store")
            self.assertIsNone(res.headers.get("Access-Control-Allow-Origin"))
            self.assertIsNone(res.headers.get("Strict-Transport-Security"))


if __name__ == "__main__":
    unittest.main()
