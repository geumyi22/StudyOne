"""Synthetic real-HTTPS OAuth MCP resource E2E; no real users or OAuth IdP.

Tests use ephemeral RS256 signing key, pinned PUBLIC JWKS and local self-signed
certificate; private key and synthetic OAuth claims never leave this test.
"""
from __future__ import annotations

import base64
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4
from http.server import ThreadingHTTPServer

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

from server.private_sync import Registry, handler_for
from server.oauth_resource import OAuthResourceGateway, RESOURCE_METADATA_PATH
from server.studyone_sync import ApiProblem


def b64int(value):
    return base64.urlsafe_b64encode(value.to_bytes(
        (value.bit_length() + 7) // 8, "big")).rstrip(b"=").decode("ascii")


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required for local TLS E2E")
class OAuthResourceTLSTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.certdir = tempfile.TemporaryDirectory(prefix="studyone-oauth-tls-")
        folder = Path(cls.certdir.name)
        cls.cert = folder / "tls.crt"
        cls.private_tls = folder / "tls.key"
        subprocess.run([
            "openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
            "-sha256", "-days", "1",
            "-keyout", str(cls.private_tls), "-out", str(cls.cert),
            "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=25)
        cls.context = ssl.create_default_context(cafile=str(cls.cert))
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.key2 = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.issuer = "https://login.example.org/tenant"
        cls.resource = "https://studyone.example.org/mcp"

    @classmethod
    def tearDownClass(cls):
        cls.certdir.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="studyone-oauth-accounts-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.registry = Registry(root / "auth.sqlite3", root / "records")
        self.alice = self.registry.account("synthetic-alice")
        self.bob = self.registry.account("synthetic-bob")
        self.alice_device = self.registry.device(self.alice, "synthetic-phone")
        self.bob_device = self.registry.device(self.bob, "synthetic-other")
        self.jwks = {"keys": [self.jwk(self.key, "test-rsa")]}
        self.gateway = OAuthResourceGateway(
            self.registry, self.issuer, self.resource, self.jwks)
        self.gateway.link(self.alice, "external-user-a")
        self.gateway.link(self.bob, "external-user-b")
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, oauth=self.gateway))
        self.server.daemon_threads = True
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(str(self.cert), str(self.private_tls))
        self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"https://127.0.0.1:{self.server.server_port}"

    @staticmethod
    def jwk(key, kid):
        nums = key.public_key().public_numbers()
        return {"kty": "RSA", "alg": "RS256", "use": "sig", "kid": kid,
                "n": b64int(nums.n), "e": b64int(nums.e)}

    def token(self, *, sub="external-user-a",
              scope="studyone.read studyone.propose", key=None,
              aud=None, iss=None, lifetime=900, **extra):
        now = int(time.time())
        claims = {"iss": iss or self.issuer, "sub": sub,
                  "aud": aud or self.resource, "iat": now,
                  "nbf": now, "exp": now + lifetime, "scope": scope}
        claims.update(extra)
        return jwt.encode(claims, key or self.key, algorithm="RS256",
                          headers={"kid": "test-rsa"})

    def request(self, path, token=None, body=None, method=None):
        raw = json.dumps(body).encode("utf8") if body is not None else None
        headers = {"Authorization": "Bearer " + token} if token else {}
        if raw is not None:
            headers["Content-Type"] = "application/json"
        url = self.base + path
        request = Request(url, method=method or ("POST" if raw is not None else "GET"),
                          headers=headers, data=raw)
        try:
            with urlopen(request, context=self.context, timeout=6) as response:
                blob = response.read()
                return response.status, json.loads(blob) if blob else None, response.headers
        except HTTPError as exc:
            blob = exc.read()
            return exc.code, json.loads(blob) if blob else None, exc.headers

    @staticmethod
    def rpc(method, name=None, args=None):
        doc = {"jsonrpc": "2.0", "id": 7, "method": method}
        if name:
            doc["params"] = {"name": name, "arguments": args or {}}
        return doc

    def test_rfc9728_resource_metadata_and_bearer_challenge(self):
        for path in (RESOURCE_METADATA_PATH,
                     "/.well-known/oauth-protected-resource"):
            status, body, headers = self.request(path)
            self.assertEqual(status, 200)
            self.assertEqual(body["resource"], self.resource)
            self.assertEqual(body["authorization_servers"], [self.issuer])
            self.assertIn("studyone.read", body["scopes_supported"])
            self.assertEqual(headers.get("Cache-Control"), "no-store")
        status, body, headers = self.request(
            "/mcp", body=self.rpc("initialize"))
        self.assertEqual((status, body["error"]), (401, "UNAUTHORIZED"))
        self.assertIn('resource_metadata="https://studyone.example.org'
                      '/.well-known/oauth-protected-resource/mcp"',
                      headers.get("WWW-Authenticate", ""))
        self.assertEqual(headers.get("Strict-Transport-Security"), "max-age=31536000")

    def test_valid_signed_oauth_reads_only_linked_learner_records(self):
        # Two private tenants must not leak each other's tasks or proposals.
        token_a = self.token()
        token_b = self.token(sub="external-user-b")
        payload = {
            "requestId": str(uuid4()), "title": "Synthetic approved draft",
            "baseCursor": 0,
            "tasks": [{"type": "숙제 항목", "subject": "과학",
                       "title": "Synthetic item", "dueDate": "2026-11-02",
                       "importance": 2}]}
        code, proposal, _ = self.request(
            "/mcp", token_a, self.rpc("tools/call",
                  "studyone_propose_study_plan", payload))
        self.assertEqual(code, 200)
        self.assertFalse(proposal["result"]["isError"])
        pending = json.loads(proposal["result"]["content"][0]["text"])
        self.assertEqual(pending["state"], "pending")
        self.assertEqual(self.registry.store(self.alice).snapshot()["records"], [])
        self.assertEqual(self.registry.store(self.bob).snapshot()["records"], [])
        status, other, _ = self.request("/mcp", token_b, self.rpc(
            "tools/call", "studyone_pending_study_plans"))
        self.assertEqual(status, 200)
        visible = json.loads(other["result"]["content"][0]["text"])
        self.assertEqual(visible["proposals"], [])
        # OAuth access tokens may not use the /v2 mutation or approval routes.
        path = "/v2/proposals/" + pending["id"] + "/approve"
        self.assertEqual(self.request(path, token_a, {}, "POST")[0], 403)
        self.assertEqual(self.request("/v2/snapshot", token_a)[0], 403)
        # Actual account owner approves with the separate WRITE-capable device.
        status, approved, _ = self.request(
            path, self.alice_device["token"], {}, "POST")
        self.assertEqual(status, 200)
        self.assertEqual(approved["state"], "approved")
        self.assertEqual(len(self.registry.store(self.alice).snapshot()["records"]), 1)
        self.assertEqual(self.registry.store(self.bob).snapshot()["records"], [])

    def test_read_only_oauth_cannot_propose_or_see_tool_definition(self):
        read = self.token(scope="studyone.read")
        status, listing, _ = self.request(
            "/mcp", read, self.rpc("tools/list"))
        self.assertEqual(status, 200)
        names = [tool["name"] for tool in listing["result"]["tools"]]
        self.assertNotIn("studyone_propose_study_plan", names)
        self.assertIn("studyone_read_snapshot", names)
        status, response, _ = self.request(
            "/mcp", read, self.rpc("tools/call", "studyone_propose_study_plan"))
        self.assertEqual((status, response["error"]), (403, "INSUFFICIENT_SCOPE"))
        status, _, _ = self.request(
            "/mcp", self.token(scope="studyone.propose"), self.rpc("tools/list"))
        self.assertEqual(status, 403)

    def test_reject_forged_expired_wrong_issuer_audience_and_unlinked(self):
        samples = [
            self.token(key=self.key2),
            self.token(iss="https://unknown.example.org"),
            self.token(aud="https://other.example.org/mcp"),
            self.token(aud=[self.resource, "https://other.example.org"]),
            self.token(lifetime=-100),
            self.token(sub="never-linked"),
            self.token(scope="studyone.propose"),
            self.token(sub=""),
        ]
        for token in samples:
            with self.subTest(token=token[:18]):
                code, _, _ = self.request(
                    "/mcp", token, self.rpc("tools/list"))
                self.assertIn(code, (401, 403))
        self.gateway.unlink("external-user-a")
        status, denied, _ = self.request(
            "/mcp", self.token(), self.rpc("tools/list"))
        self.assertEqual((status, denied["error"]), (403, "ACCOUNT_LINK_REQUIRED"))
        self.gateway.link(self.alice, "external-user-a")
        self.registry.disable(self.alice)
        status, _, _ = self.request(
            "/mcp", self.token(), self.rpc("tools/list"))
        self.assertEqual(status, 403)

    def test_binding_cannot_be_silently_reassigned(self):
        with self.assertRaises(ValueError):
            self.gateway.link(self.bob, "external-user-a")
        self.gateway.unlink("external-user-a")
        with self.assertRaises(ValueError):
            self.gateway.link(self.bob, "external-user-a")

    def test_jwks_rejects_private_material_or_symmetric_keys(self):
        invalid_sets = [
            {"keys": []},
            {"keys": [dict(self.jwk(self.key, "test-rsa"), d="malicious")]},
            {"keys": [dict(self.jwk(self.key, "test-rsa"), alg="HS256")]},
            {"keys": [self.jwk(self.key, "test-rsa"),
                      self.jwk(self.key2, "test-rsa")]},
            {"keys": [{"kty": "oct", "k": "secret", "kid": "unsafe"}]},
        ]
        for payload in invalid_sets:
            with self.assertRaises(ValueError):
                OAuthResourceGateway(self.registry, self.issuer, self.resource, payload)


if __name__ == "__main__":
    unittest.main()
