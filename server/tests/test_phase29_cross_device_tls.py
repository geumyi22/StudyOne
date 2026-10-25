"""Phase29: real TLS end-to-end cycle with distinct simulated phone/desktop clients.

This tests HTTP wire interoperability, not operation of real Android/iOS/Windows
binaries or a public deployment. All users, records, certs and tokens are synthetic.
No OPENAI_API_KEY, live learner accounts, private data or paid services.
"""
from __future__ import annotations

from copy import deepcopy
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from server.private_sync import Registry, handler_for


@unittest.skipUnless(shutil.which("openssl"), "Local OpenSSL required for TLS E2E")
class CrossDeviceEndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.certs = tempfile.TemporaryDirectory(prefix="studyone-phase29-certs-")
        folder = Path(cls.certs.name)
        cls.cert = folder / "selfsigned.pem"
        cls.key = folder / "private.key"
        subprocess.run([
            "openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
            "-sha256", "-days", "1", "-keyout", str(cls.key),
            "-out", str(cls.cert), "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
           timeout=25)
        cls.trusted = ssl.create_default_context(cafile=str(cls.cert))

    @classmethod
    def tearDownClass(cls):
        cls.certs.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="studyone-phase29-db-")
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.path, self.accounts = root / "registry.sqlite3", root / "study-data"
        self.registry = Registry(self.path, self.accounts)
        self.owner = self.registry.account("synthetic-owner")
        self.other = self.registry.account("synthetic-other")
        self.android = self.registry.device(self.owner, "android-sim", "read-write")
        self.ios = self.registry.device(self.owner, "ios-sim", "read-write")
        self.windows = self.registry.device(self.owner, "windows-sim", "read-write")
        self.readonly = self.registry.device(self.owner, "readonly-sim", "read")
        self.stranger = self.registry.device(self.other, "other-account-sim", "read-write")
        self.start_server()
        self.addCleanup(self.stop_server)

    def start_server(self):
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, tls_enabled=True)
        )
        self.server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(self.cert), str(self.key))
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.base = f"https://127.0.0.1:{self.server.server_port}"

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=4)

    def send(self, device, method, path, body=None):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        request = Request(self.base + path, method=method, data=raw,
                          headers={"Authorization": "Bearer " + device["token"],
                                   **({"Content-Type": "application/json"}
                                      if raw is not None else {})})
        try:
            with urlopen(request, context=self.trusted, timeout=6) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    @staticmethod
    def task(identifier, title):
        return {"id": identifier, "type": "숙제 항목", "subject": "과학",
                "title": title, "dueDate": "2026-11-03",
                "importance": 2, "completed": False}

    @staticmethod
    def progress(identifier, status="미완료", revision=1):
        return {"id": identifier, "subject": "사회",
                "material": "지학사", "unit": "4~5단원",
                "status": status, "inExamScope": True,
                "revision": revision, "updatedAt": 1790000000000}

    @staticmethod
    def mutation(kind, identifier, payload, base=0, deleted=False):
        return {"kind": kind, "id": str(identifier), "baseRevision": base,
                "deleted": deleted, "payload": payload}

    @staticmethod
    def batch(*mutations, request_id=None):
        return {"requestId": request_id or str(uuid4()),
                "mutations": list(mutations)}

    def test_3_devices_1_account_update_conflict_retry_reboot_and_isolation(self):
        # All three independent client identities authenticate for one account.
        for identity in (self.android, self.ios, self.windows):
            status, session = self.send(identity, "GET", "/v2/session")
            self.assertEqual(status, 200)
            self.assertEqual(session["accountId"], self.owner)
            self.assertEqual(session["deviceId"], identity["deviceId"])
            self.assertEqual((session["scope"], session["audience"]),
                             ("read-write", "device"))

        task_id = 1790010000001
        progress_id = str(uuid4())
        offline = self.batch(
            self.mutation("task", task_id, self.task(task_id, "합성 과학 수행평가")),
            self.mutation("progress", progress_id, self.progress(progress_id)))
        # Android simulator sends a 2-record offline batch via actual HTTPS.
        status, upload = self.send(self.android, "POST", "/v2/changes", offline)
        self.assertEqual(status, 200)
        self.assertEqual(upload["cursor"], 2)
        self.assertEqual([x["revision"] for x in upload["changes"]], [1, 1])

        # Windows sees the same exact records in a snapshot. Another account
        # cannot view any of them, even when the record IDs are known.
        status, snapshot = self.send(self.windows, "GET", "/v2/snapshot")
        self.assertEqual(status, 200)
        self.assertEqual((snapshot["schema"], snapshot["cursor"]), (1, 2))
        by_id = {x["id"]: x for x in snapshot["records"]}
        self.assertEqual(set(by_id), {str(task_id), progress_id})
        self.assertEqual(by_id[progress_id]["payload"]["status"], "미완료")
        self.assertEqual(by_id[str(task_id)]["payload"]["title"], "합성 과학 수행평가")
        self.assertEqual(self.send(self.stranger, "GET", "/v2/snapshot")[1]["records"], [])

        # iOS simulates bounded paginated delta sync, respecting nextCursor.
        status, first = self.send(self.ios, "GET", "/v2/changes?after=0&limit=1")
        self.assertEqual((status, first["hasMore"]), (200, True))
        self.assertEqual(len(first["changes"]), 1)
        self.assertEqual(first["nextCursor"], 1)
        _, second = self.send(self.ios, "GET",
                              f"/v2/changes?after={first['nextCursor']}&limit=1")
        self.assertEqual((len(second["changes"]), second["hasMore"]), (1, False))
        self.assertEqual(second["nextCursor"], 2)
        self.assertEqual({r["id"] for r in first["changes"] + second["changes"]},
                         {str(task_id), progress_id})
        self.assertEqual(self.send(self.stranger, "GET",
                                   "/v2/changes?after=0")[1]["changes"], [])

        # Windows edits that existing progress with explicit server revision 1.
        edit = self.batch(self.mutation(
            "progress", progress_id, self.progress(progress_id, "진행 중"), base=1))
        status, change = self.send(self.windows, "POST", "/v2/changes", edit)
        self.assertEqual((status, change["changes"][0]["revision"]), (200, 2))

        # Android still holding the first snapshot cannot clobber Windows edit.
        stale = self.batch(self.mutation(
            "progress", progress_id, self.progress(progress_id, "완료"), base=1))
        status, conflict = self.send(self.android, "POST", "/v2/changes", stale)
        self.assertEqual((status, conflict["error"]), (409, "REVISION_CONFLICT"))
        _, unchanged = self.send(self.android, "GET", "/v2/snapshot")
        self.assertEqual({r["id"]: r for r in unchanged["records"]}
                         [progress_id]["payload"]["status"], "진행 중")

        # The user can resolve the conflict AFTER reloading revision 2.
        resolved = self.batch(self.mutation(
            "progress", progress_id, self.progress(progress_id, "완료"), base=2))
        status, applied = self.send(self.android, "POST", "/v2/changes", resolved)
        self.assertEqual((status, applied["changes"][0]["revision"]), (200, 3))
        # Lost HTTP response: repeat the exact requestId/body; no duplicate event.
        status, replay = self.send(self.android, "POST", "/v2/changes", resolved)
        self.assertEqual((status, replay), (200, applied))
        self.assertEqual(self.send(self.ios, "GET",
                                  "/v2/changes?after=0&limit=100")[1]["head"], 4)
        # A changed body with the SAME requestId must never be accepted.
        replaced = deepcopy(resolved)
        replaced["mutations"][0]["payload"]["status"] = "미완료"
        status, rejected = self.send(self.android, "POST", "/v2/changes", replaced)
        self.assertEqual((status, rejected["error"]), (409, "IDEMPOTENCY_KEY_REUSED"))

        # A separate account may use the same logical ID but keeps distinct data.
        outsider = self.batch(self.mutation("task", task_id,
                                            self.task(task_id, "다른 계정 자료")))
        self.assertEqual(self.send(self.stranger, "POST",
                                   "/v2/changes", outsider)[0], 200)
        owner_records = self.send(self.windows, "GET",
                                  "/v2/snapshot")[1]["records"]
        self.assertEqual({r["id"]: r for r in owner_records}
                         [str(task_id)]["payload"]["title"], "합성 과학 수행평가")

        # Restart the actual HTTPS server process listener and reopen the
        # persisted on-disk account registry, simulating an offline/restart.
        self.stop_server()
        self.registry = Registry(self.path, self.accounts)
        self.start_server()
        status, final = self.send(self.ios, "GET", "/v2/snapshot")
        self.assertEqual(status, 200)
        self.assertEqual(final["cursor"], 4)
        self.assertEqual({r["id"]: r for r in final["records"]}
                         [progress_id]["payload"]["status"], "완료")
        status, again = self.send(self.android, "POST", "/v2/changes", resolved)
        self.assertEqual((status, again), (200, applied))
        self.assertEqual(self.send(self.ios, "GET",
                                  "/v2/changes?after=0")[1]["head"], 4)

        # A read-only device cannot mutate or approve in place of the owner.
        status, denied = self.send(self.readonly, "POST", "/v2/changes",
                                   self.batch(self.mutation(
                                       "task", task_id + 1,
                                       self.task(task_id + 1, "권한 없는 쓰기"))))
        self.assertEqual((status, denied["error"]), (403, "WRITE_FORBIDDEN"))
        self.registry.revoke(self.android["deviceId"])
        status, denied = self.send(self.android, "GET", "/v2/snapshot")
        self.assertEqual((status, denied["error"]), (401, "UNAUTHORIZED"))
        self.assertEqual(self.send(self.windows, "GET",
                                   "/v2/snapshot")[1]["cursor"], 4)

    def test_pending_ai_plan_does_not_change_tasks_until_owner_approves(self):
        assistant = self.registry.device(self.owner, "ai-sim", "read", audience="mcp")
        proposal_body = {
            "requestId": str(uuid4()),
            "baseCursor": 0,
            "title": "합성 내일 공부",
            "tasks": [{"type": "숙제 항목", "subject": "국어",
                       "title": "합성 시험 대비 자료", "dueDate": "2026-11-03",
                       "importance": 2}]}
        # MCP tool call, not a raw device API mutation.
        rpc = {"jsonrpc": "2.0", "id": 42, "method": "tools/call",
               "params": {"name": "studyone_propose_study_plan",
                          "arguments": proposal_body}}
        status, result = self.send(assistant, "POST", "/mcp", rpc)
        self.assertEqual(status, 200)
        self.assertFalse(result["result"]["isError"])
        pending = json.loads(result["result"]["content"][0]["text"])
        self.assertEqual(pending["state"], "pending")
        self.assertEqual(self.send(self.android, "GET",
                                   "/v2/snapshot")[1]["records"], [])
        path = f"/v2/proposals/{pending['id']}/approve"
        self.assertEqual(self.send(assistant, "POST", path, {})[0], 403)
        self.assertEqual(self.send(self.stranger, "POST", path, {})[0], 404)
        status, accepted = self.send(self.ios, "POST", path, {})
        self.assertEqual((status, accepted["state"]), (200, "approved"))
        self.assertEqual(len(self.send(self.windows, "GET",
                                       "/v2/snapshot")[1]["records"]), 1)


if __name__ == "__main__":
    unittest.main()
