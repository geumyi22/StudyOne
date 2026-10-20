"""Proposed ChatGPT plans are drafts until a writable user device approves."""
from __future__ import annotations
import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import time
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

from server.private_sync import Registry, handler_for
from server.studyone_proposals import ProposalStore
from server.studyone_sync import ApiProblem


def draft(request_id=None, title="기말고사 공부 계획"):
    return {
        "requestId": request_id or str(uuid4()),
        "title": title,
        "tasks": [
            {"type": "숙제 항목", "subject": "과학",
             "title": "오투 7-2 풀이", "dueDate": "2026-10-21",
             "importance": 2},
            {"type": "정리 및 복습 항목", "subject": "국어",
             "title": "실전 모의고사 2단원 복습", "dueDate": "2026-10-21",
             "importance": 2}
        ]
    }


class ProposalCoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="studyone-proposal-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.registry = Registry(root / "auth.sqlite3", root / "users")
        self.a = self.registry.account("Test account A")
        self.b = self.registry.account("Test account B")
        self.writer = self.registry.device(self.a, "windows", "read-write")
        self.assistant = self.registry.device(self.a, "assistant", "read")
        self.other = self.registry.device(self.b, "another", "read-write")
        self.proposals = ProposalStore(self.registry.store(self.a))

    def test_proposed_plan_not_applied_until_approved(self):
        saved = self.proposals.create(draft())
        self.assertEqual(saved["state"], "pending")
        self.assertEqual(saved["taskCount"], 2)
        self.assertEqual(self.registry.store(self.a).snapshot()["records"], [])
        self.assertEqual(len(self.proposals.pending()["proposals"]), 1)
        approved = self.proposals.approve(saved["id"])
        self.assertEqual(approved["state"], "approved")
        records = self.registry.store(self.a).snapshot()["records"]
        self.assertEqual(len(records), 2)
        self.assertTrue(all(r["payload"]["completed"] is False for r in records))
        self.assertEqual(self.proposals.approve(saved["id"]), approved)
        self.assertEqual(len(self.registry.store(self.a).changes(0, 20)["changes"]), 2)
        self.assertEqual(self.proposals.pending()["proposals"], [])

    def test_different_account_cannot_access_proposal(self):
        saved = self.proposals.create(draft())
        unrelated = ProposalStore(self.registry.store(self.b))
        self.assertEqual(unrelated.pending()["proposals"], [])
        with self.assertRaises(ApiProblem) as ctx:
            unrelated.approve(saved["id"])
        self.assertEqual(ctx.exception.status, 404)

    def test_proposal_rejected_without_any_live_change(self):
        saved = self.proposals.create(draft())
        self.assertEqual(self.proposals.reject(saved["id"])["state"], "rejected")
        with self.assertRaises(ApiProblem):
            self.proposals.approve(saved["id"])
        self.assertEqual(self.registry.store(self.a).snapshot()["records"], [])

    def test_same_request_id_idempotent_and_modified_request_rejected(self):
        command = draft()
        first = self.proposals.create(command)
        self.assertEqual(first, self.proposals.create(command))
        command["title"] = "Changed content"
        with self.assertRaises(ApiProblem) as ctx:
            self.proposals.create(command)
        self.assertEqual((ctx.exception.status, ctx.exception.code),
                         (409, "PROPOSAL_KEY_REUSED"))

    def test_bad_data_and_fields_blocked(self):
        problems = [
            {"requestId": str(uuid4()), "title": "hello", "tasks": []},
            dict(draft(), unexpected="api-secret"),
            dict(draft(), tasks=[dict(draft()["tasks"][0], apiKey="secret")]),
            dict(draft(), tasks=[dict(draft()["tasks"][0], dueDate="2026-02-30")]),
            dict(draft(), tasks=[dict(draft()["tasks"][0], importance=8)]),
            dict(draft(), tasks=[dict(draft()["tasks"][0], completed=True)])
        ]
        for invalid in problems:
            with self.subTest(invalid=invalid), self.assertRaises(ApiProblem):
                self.proposals.create(invalid)
        self.assertEqual(self.registry.store(self.a).snapshot()["cursor"], 0)

    def test_expired_draft_cannot_be_approved(self):
        saved = self.proposals.create(draft())
        with self.registry.store(self.a).connect() as db:
            db.execute("UPDATE study_plan_proposals SET expires_at=0 WHERE id=?",
                       (saved["id"],))
        self.assertEqual(self.proposals.pending()["proposals"], [])
        with self.assertRaises(ApiProblem):
            self.proposals.approve(saved["id"])
        self.assertEqual(self.registry.store(self.a).snapshot()["records"], [])

    def test_pending_limit_fails_closed(self):
        for i in range(20):
            self.proposals.create(draft(title=f"Plan {i}"))
        with self.assertRaises(ApiProblem) as ctx:
            self.proposals.create(draft(title="Plan 21"))
        self.assertEqual((ctx.exception.status, ctx.exception.code),
                         (429, "TOO_MANY_PENDING_PROPOSALS"))


class ProposalHttpTests(ProposalCoreTests):
    def setUp(self):
        super().setUp()
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, tls_enabled=False))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.host = f"http://127.0.0.1:{self.server.server_port}"

    def call(self, token, method, path, body=None):
        headers = {"Authorization": "Bearer " + token}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = Request(self.host + path, headers=headers, data=data, method=method)
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as ex:
            return ex.code, json.loads(ex.read())

    def test_read_token_can_create_draft_but_cannot_apply(self):
        token = self.assistant["token"]
        response, saved = self.call(token, "POST", "/v2/proposals", draft())
        self.assertEqual(response, 200)
        self.assertEqual(self.registry.store(self.a).snapshot()["records"], [])
        status, err = self.call(token, "POST",
                                "/v2/proposals/" + saved["id"] + "/approve", {})
        self.assertEqual((status, err["error"]), (403, "WRITE_FORBIDDEN"))
        status, pending = self.call(token, "GET", "/v2/proposals")
        self.assertEqual(status, 200)
        self.assertEqual(len(pending["proposals"]), 1)
        status, done = self.call(self.writer["token"], "POST",
                                 "/v2/proposals/" + saved["id"] + "/approve", {})
        self.assertEqual((status, done["state"]), (200, "approved"))
        self.assertEqual(len(self.registry.store(self.a).snapshot()["records"]), 2)

    def test_other_account_cannot_approve_or_even_see_draft(self):
        _, saved = self.call(self.assistant["token"], "POST", "/v2/proposals", draft())
        status, pending = self.call(self.other["token"], "GET", "/v2/proposals")
        self.assertEqual((status, pending["proposals"]), (200, []))
        status, err = self.call(self.other["token"], "POST",
                                "/v2/proposals/" + saved["id"] + "/approve", {})
        self.assertEqual((status, err["error"]), (404, "PROPOSAL_NOT_FOUND"))

    def test_assistant_read_token_cannot_mutate_directly(self):
        _, saved = self.call(self.assistant["token"], "POST", "/v2/proposals", draft())
        status, error = self.call(self.assistant["token"], "POST",
                                  "/v2/changes", {"requestId": str(uuid4()), "mutations": []})
        self.assertEqual((status, error["error"]), (403, "WRITE_FORBIDDEN"))
        self.assertEqual(self.registry.store(self.a).snapshot()["records"], [])


if __name__ == "__main__":
    unittest.main()
