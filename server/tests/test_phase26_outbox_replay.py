"""Phase26 synthetic outbox retry semantics (server transaction contract).

Client UI is tested in the Windows portable QA. This test verifies SQLite
requestId idempotency and all-or-nothing conflict preflight without HTTP costs.
"""
from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from server.studyone_sync import ApiProblem, SyncStore


def task(identifier, title):
    return {"id": identifier, "type": "숙제 항목", "subject": "수학",
            "title": title, "dueDate": "2026-11-01",
            "importance": 2, "completed": False}


def new_task(identifier, title):
    return {"kind": "task", "id": str(identifier), "baseRevision": 0,
            "deleted": False, "payload": task(identifier, title)}


class Phase26DurableReplayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory(prefix="studyone-outbox-replay-")
        self.addCleanup(self.tmp.cleanup)
        self.store = SyncStore(Path(self.tmp.name) / "account.sqlite3")

    def test_response_lost_then_retry_exact_request_id_is_idempotent(self):
        body = {"requestId": str(uuid4()),
                "mutations": [new_task(1700000000001, "오프라인 합성 문제")]}
        first = self.store.apply(body)
        # Simulate client restart with the queue's same immutable requestId/body.
        second = self.store.apply(body)
        self.assertEqual(first, second)
        self.assertEqual(len(self.store.snapshot()["records"]), 1)
        self.assertEqual(len(self.store.changes(0, 100)["changes"]), 1)

    def test_remote_collision_rejects_entire_batch_before_any_new_write(self):
        existing = {"requestId": str(uuid4()),
                    "mutations": [new_task(1700000000001, "다른 기기 생성")]}
        self.store.apply(existing)
        attempted = {"requestId": str(uuid4()), "mutations": [
            new_task(1700000000002, "새 항목"),
            new_task(1700000000001, "동일 ID 다른 내용")]}
        with self.assertRaises(ApiProblem) as caught:
            self.store.apply(attempted)
        self.assertEqual(caught.exception.status, 409)
        self.assertEqual(len(self.store.snapshot()["records"]), 1)
        self.assertEqual(self.store.snapshot()["records"][0]["payload"]["title"],
                         "다른 기기 생성")

    def test_request_key_cannot_be_reused_for_new_payload(self):
        req = str(uuid4())
        body = {"requestId": req, "mutations": [new_task(1700000000001, "원본")]}
        self.store.apply(body)
        with self.assertRaises(ApiProblem) as caught:
            self.store.apply({"requestId": req, "mutations": [
                new_task(1700000000002, "바뀐 요청")]})
        self.assertEqual((caught.exception.status, caught.exception.code),
                         (409, "IDEMPOTENCY_KEY_REUSED"))
        self.assertEqual(len(self.store.snapshot()["records"]), 1)

    def test_deleted_record_cannot_be_silently_recreated(self):
        identifier = 1700000000001
        created = self.store.apply({"requestId": str(uuid4()),
            "mutations": [new_task(identifier, "삭제될 합성 과제")]})
        self.store.apply({"requestId": str(uuid4()),
            "mutations": [{"kind": "task", "id": str(identifier),
                           "baseRevision": 1, "deleted": True, "payload": None}]})
        with self.assertRaises(ApiProblem) as caught:
            self.store.apply({"requestId": str(uuid4()),
                "mutations": [new_task(identifier, "몰래 복원")]})
        self.assertEqual(caught.exception.status, 409)
        self.assertEqual(self.store.snapshot()["records"], [])
        self.assertEqual(len(self.store.changes(0, 100)["changes"]), 2)


if __name__ == "__main__":
    unittest.main()
