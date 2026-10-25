"""Ephemeral real TLS / account isolation / approval E2E; no production data."""
from __future__ import annotations

import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import shutil
import ssl
import subprocess
from tempfile import TemporaryDirectory
import threading
import time
import unittest
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

from server.private_sync import Registry, handler_for


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL required for real TLS E2E")
class HttpsIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.certdir = TemporaryDirectory(prefix="studyone-https-fixture-")
        folder = Path(cls.certdir.name)
        cls.cert = folder / "tls.crt"
        cls.key = folder / "tls.key"
        subprocess.run([
            "openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
            "-sha256", "-days", "1", "-keyout", str(cls.key),
            "-out", str(cls.cert), "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
           timeout=25)
        cls.trusted = ssl.create_default_context(cafile=str(cls.cert))
        cls.trusted.minimum_version = ssl.TLSVersion.TLSv1_2

    @classmethod
    def tearDownClass(cls):
        cls.certdir.cleanup()

    def setUp(self):
        self.tmp = TemporaryDirectory(prefix="studyone-real-tls-")
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.registry = Registry(root / "registry.sqlite3", root / "users")
        self.alice = self.registry.account("alice-test")
        self.bob = self.registry.account("bob-test")
        self.alice_device = self.registry.device(self.alice, "alice-device", "read-write")
        self.alice_ai = self.registry.device(self.alice, "alice-ai", "read")
        self.bob_device = self.registry.device(self.bob, "bob-device", "read-write")
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, tls_enabled=True)
        )
        self.server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(self.cert), str(self.key))
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = "https://127.0.0.1:" + str(self.server.server_port)

    def request(self, token, method, path, body=None, context=None):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        headers = {"Authorization": "Bearer " + token}
        if data is not None:
            headers["Content-Type"] = "application/json"
        message = Request(self.url + path, method=method, data=data, headers=headers)
        try:
            with urlopen(message, context=context or self.trusted, timeout=7) as reply:
                return reply.status, json.loads(reply.read()), reply.headers
        except HTTPError as err:
            return err.code, json.loads(err.read()), err.headers

    def test_https_certificate_enforced_and_hsts(self):
        with self.assertRaises(URLError):
            self.request(self.alice_device["token"], "GET", "/v2/snapshot",
                         context=ssl.create_default_context())
        status, payload, headers = self.request(self.alice_device["token"], "GET",
                                                "/v2/snapshot")
        self.assertEqual(status, 200)
        self.assertEqual(payload["records"], [])
        self.assertEqual(headers.get("Strict-Transport-Security"), "max-age=31536000")
        self.assertEqual(headers.get("Cache-Control"), "no-store")

    def test_real_https_isolated_accounts_and_read_only(self):
        mutation = {
            "requestId": str(uuid4()),
            "mutations": [{
                "kind": "task", "id": "534789", "baseRevision": 0,
                "deleted": False, "payload": {
                    "id": 534789, "type": "숙제 항목", "subject": "과학",
                    "title": "합성 진도 테스트", "dueDate": "2026-10-22",
                    "importance": 2, "completed": False
                }
            }]
        }
        status, ack, _ = self.request(self.alice_device["token"], "POST",
                                      "/v2/changes", mutation)
        self.assertEqual(status, 200)
        self.assertEqual(ack["cursor"], 1)
        status, bob_snapshot, _ = self.request(self.bob_device["token"], "GET",
                                               "/v2/snapshot")
        self.assertEqual((status, bob_snapshot["records"]), (200, []))
        status, denied, _ = self.request(self.alice_ai["token"], "POST",
                                         "/v2/changes", mutation)
        self.assertEqual((status, denied["error"]), (403, "WRITE_FORBIDDEN"))

    def test_ai_draft_never_applied_before_device_approval_via_https(self):
        body = {
            "requestId": str(uuid4()), "title": "시험 공부 합성 데이터",
            "tasks": [{
                "type": "숙제 항목", "subject": "사회",
                "title": "시험 단원 복습", "dueDate": "2026-10-22",
                "importance": 2
            }]
        }
        status, proposed, _ = self.request(self.alice_ai["token"], "POST",
                                           "/v2/proposals", body)
        self.assertEqual((status, proposed["state"]), (200, "pending"))
        status, snapshot, _ = self.request(self.alice_device["token"], "GET",
                                           "/v2/snapshot")
        self.assertEqual(snapshot["records"], [])
        status, denied, _ = self.request(
            self.alice_ai["token"], "POST",
            f"/v2/proposals/{proposed['id']}/approve", {}
        )
        self.assertEqual((status, denied["error"]), (403, "WRITE_FORBIDDEN"))
        status, denied, _ = self.request(
            self.bob_device["token"], "POST",
            f"/v2/proposals/{proposed['id']}/approve", {}
        )
        self.assertEqual((status, denied["error"]), (404, "PROPOSAL_NOT_FOUND"))
        status, approved, _ = self.request(
            self.alice_device["token"], "POST",
            f"/v2/proposals/{proposed['id']}/approve", {}
        )
        self.assertEqual((status, approved["state"]), (200, "approved"))
        _, snapshot, _ = self.request(self.alice_ai["token"], "GET",
                                      "/v2/snapshot")
        self.assertEqual(len(snapshot["records"]), 1)
        _, again, _ = self.request(
            self.alice_device["token"], "POST",
            f"/v2/proposals/{proposed['id']}/approve", {}
        )
        self.assertEqual(again, approved)
        _, last, _ = self.request(self.alice_device["token"], "GET",
                                  "/v2/snapshot")
        self.assertEqual(last["cursor"], snapshot["cursor"])

    def test_mcp_only_token_isolated_through_real_tls_and_rotatable(self):
        assistant = self.registry.device(self.alice, "oauth-preflight-assistant",
                                         "read", days=7, audience="mcp")
        token = assistant["token"]
        rpc = {"jsonrpc": "2.0", "id": 101, "method": "tools/list"}
        status, returned, headers = self.request(token, "POST", "/mcp", rpc)
        self.assertEqual(status, 200)
        self.assertEqual(len(returned["result"]["tools"]), 4)
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        status, denied, _ = self.request(token, "GET", "/v2/snapshot")
        self.assertEqual((status, denied["error"]),
                         (403, "TOKEN_AUDIENCE_FORBIDDEN"))
        status, denied, _ = self.request(token, "POST",
            "/v2/proposals/" + str(uuid4()) + "/approve", {})
        self.assertEqual((status, denied["error"]),
                         (403, "TOKEN_AUDIENCE_FORBIDDEN"))
        rotated = self.registry.rotate(assistant["deviceId"])
        status, denied, _ = self.request(token, "POST", "/mcp", rpc)
        self.assertEqual((status, denied["error"]), (401, "UNAUTHORIZED"))
        status, returned, _ = self.request(rotated["token"], "POST", "/mcp", rpc)
        self.assertEqual(status, 200)
        self.registry.revoke(assistant["deviceId"])
        status, denied, _ = self.request(rotated["token"], "POST", "/mcp", rpc)
        self.assertEqual((status, denied["error"]), (401, "UNAUTHORIZED"))

    def test_device_revocation_cancels_subsequent_https_access(self):
        status, _, _ = self.request(self.alice_device["token"], "GET",
                                    "/v2/snapshot")
        self.assertEqual(status, 200)
        self.registry.revoke(self.alice_device["deviceId"])
        status, denied, _ = self.request(self.alice_device["token"], "GET",
                                         "/v2/snapshot")
        self.assertEqual((status, denied["error"]), (401, "UNAUTHORIZED"))
        status, _, _ = self.request(self.bob_device["token"], "GET",
                                    "/v2/snapshot")
        self.assertEqual(status, 200)


if __name__ == "__main__":
    unittest.main()
