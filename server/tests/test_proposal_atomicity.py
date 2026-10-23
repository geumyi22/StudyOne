"""Prove study-plan approvals are all-or-nothing under failures and races."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Barrier
from uuid import uuid4
import sqlite3
import unittest

from server.studyone_proposals import ProposalStore
from server.studyone_sync import ApiProblem, SyncStore


def proposal():
    return {
        "requestId": str(uuid4()),
        "title": "일정 원자성 시험 (합성 데이터)",
        "tasks": [{
            "type": "숙제 항목", "subject": "과학", "title": "진도 시험",
            "dueDate": "2026-10-21", "importance": 2
        }, {
            "type": "정리 및 복습 항목", "subject": "국어",
            "title": "시험범위 점검", "dueDate": "2026-10-22",
            "importance": 2
        }]
    }


class AtomicApprovalTests(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory(prefix="studyone-approval-race-")
        self.addCleanup(self.directory.cleanup)
        self.store = SyncStore(Path(self.directory.name) / "personal.sqlite3")
        self.plans = ProposalStore(self.store)

    def test_failed_approval_marker_rolls_back_active_tasks_and_idempotency(self):
        pending = self.plans.create(proposal())
        with self.store.connect() as db:
            db.execute("""
                CREATE TRIGGER approval_write_fails
                BEFORE UPDATE OF state ON study_plan_proposals
                WHEN NEW.state = 'approved'
                BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END
            """)
        with self.assertRaises(sqlite3.Error):
            self.plans.approve(pending["id"])
        with self.store.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM records").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM change_log").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM requests").fetchone()[0], 0)
            state = db.execute(
                "SELECT state FROM study_plan_proposals WHERE id=?",
                (pending["id"],)
            ).fetchone()[0]
            self.assertEqual(state, "pending")
            db.execute("DROP TRIGGER approval_write_fails")
        completed = self.plans.approve(pending["id"])
        self.assertEqual(completed["state"], "approved")
        self.assertEqual(len(self.store.snapshot()["records"]), 2)
        self.assertEqual(self.plans.approve(pending["id"]), completed)
        self.assertEqual(self.store.snapshot()["cursor"], 2)

    def test_approve_and_reject_cannot_both_win(self):
        for _ in range(14):
            pending = self.plans.create(proposal())
            barrier = Barrier(2)
            def act(which):
                barrier.wait(timeout=5)
                instance = ProposalStore(self.store)
                try:
                    return ("success", instance.approve(pending["id"]) if which == "approve"
                            else instance.reject(pending["id"]))
                except ApiProblem as error:
                    return ("blocked", error.code)
            baseline = self.store.snapshot()["cursor"]
            with ThreadPoolExecutor(max_workers=2) as pool:
                fut_approve = pool.submit(act, "approve")
                fut_reject = pool.submit(act, "reject")
                approve = fut_approve.result(timeout=12)
                reject = fut_reject.result(timeout=12)
            self.assertNotEqual(approve[0], reject[0])
            with self.store.connect() as db:
                state = db.execute(
                    "SELECT state FROM study_plan_proposals WHERE id=?",
                    (pending["id"],)
                ).fetchone()[0]
            if approve[0] == "success":
                self.assertEqual(state, "approved")
                self.assertEqual(self.store.snapshot()["cursor"], baseline + 2)
                self.assertEqual(reject, ("blocked", "PROPOSAL_ALREADY_APPROVED"))
            else:
                self.assertEqual(state, "rejected")
                self.assertEqual(self.store.snapshot()["cursor"], baseline)
                self.assertEqual(approve, ("blocked", "PROPOSAL_UNAVAILABLE"))

    def test_explicit_rollback_for_failed_direct_shared_transaction(self):
        req = {
            "requestId": str(uuid4()),
            "mutations": [{
                "kind": "task", "id": "42", "baseRevision": 0,
                "deleted": False, "payload": {
                    "id": 42, "type": "숙제 항목", "subject": "역사",
                    "title": "직접 트랜잭션 확인", "dueDate": "2026-10-22",
                    "importance": 1, "completed": False
                }
            }]
        }
        with self.assertRaises(RuntimeError):
            with self.store.connect() as db:
                db.execute("BEGIN IMMEDIATE")
                self.store.apply(req, connection=db)
                raise RuntimeError("Simulated power loss before commit")
        self.assertEqual(self.store.snapshot()["records"], [])
        self.assertEqual(self.store.snapshot()["cursor"], 0)
        with self.store.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM requests").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
