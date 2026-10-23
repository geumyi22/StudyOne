"""StudyOne account-local study-plan proposals requiring app-side approval.

Never directly commits AI-generated plans. A writable device token must approve
the draft using /v2/proposals/{id}/approve; read-only assistant tokens can
propose drafts but cannot call the regular mutation endpoint.
"""
from __future__ import annotations

from datetime import date
import hashlib
import json
import secrets
import sqlite3
import time
from uuid import uuid4

try:
    from .studyone_sync import ApiProblem, MAX_TASK_ID, SyncStore, json_bytes, validate_record
except ImportError:
    from studyone_sync import ApiProblem, MAX_TASK_ID, SyncStore, json_bytes, validate_record

MAX_PENDING = 20
MAX_PLAN_TASKS = 30
EXPIRES_AFTER = 7 * 24 * 3600
TASK_KEYS = frozenset(("type", "subject", "title", "dueDate", "importance"))


def canonical_uuid(value):
    from uuid import UUID
    if not isinstance(value, str):
        return False
    try:
        return str(UUID(value)).lower() == value.lower()
    except ValueError:
        return False


class ProposalStore:
    def __init__(self, store: SyncStore):
        self.store = store
        with store.connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS study_plan_proposals (
                    id TEXT PRIMARY KEY,
                    request_id TEXT NOT NULL UNIQUE,
                    request_hash TEXT NOT NULL,
                    title TEXT NOT NULL,
                    mutations_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    state TEXT NOT NULL
                      CHECK(state IN ('pending','approved','rejected')),
                    cursor INTEGER,
                    base_cursor INTEGER
                )
            """)
            # Non-destructive migration: older account DBs lack base_cursor.
            columns = {row["name"] for row in db.execute(
                "PRAGMA table_info(study_plan_proposals)")}
            if "base_cursor" not in columns:
                db.execute("ALTER TABLE study_plan_proposals ADD COLUMN base_cursor INTEGER")
            db.execute("""
                CREATE INDEX IF NOT EXISTS idx_proposals_state
                ON study_plan_proposals(state,expires_at)
            """)

    @staticmethod
    def _item(row, include_tasks=True):
        muts = json.loads(row["mutations_json"]) if include_tasks else []
        return {
            "id": row["id"],
            "title": row["title"],
            "state": row["state"],
            "createdAt": row["created_at"],
            "expiresAt": row["expires_at"],
            "taskCount": len(muts) if include_tasks else None,
            "tasks": [m["payload"] for m in muts] if include_tasks else [],
            "cursor": row["cursor"]
        }

    @staticmethod
    def _checked_draft(request):
        if (not isinstance(request, dict) or
                set(request) not in ({"requestId", "title", "tasks"},
                                     {"requestId", "title", "tasks", "baseCursor"})):
            raise ApiProblem(400, "INVALID_PROPOSAL")
        key, title, tasks = request["requestId"], request["title"], request["tasks"]
        expected_cursor = request.get("baseCursor")
        if "baseCursor" in request and (
                type(expected_cursor) is not int or
                expected_cursor < 0 or expected_cursor > MAX_TASK_ID):
            raise ApiProblem(400, "INVALID_PLAN_CURSOR")
        if not canonical_uuid(key) or not isinstance(title, str) or not 1 <= len(title.strip()) <= 100:
            raise ApiProblem(400, "INVALID_PROPOSAL")
        if not isinstance(tasks, list) or not 1 <= len(tasks) <= MAX_PLAN_TASKS:
            raise ApiProblem(400, "INVALID_PROPOSAL_COUNT")
        for task in tasks:
            if not isinstance(task, dict) or set(task) != TASK_KEYS:
                raise ApiProblem(400, "INVALID_PROPOSAL_TASK")
            sample = dict(task)
            sample.update(id=1, completed=False)
            validate_record("task", "1", sample)
        return key, title.strip(), tasks, expected_cursor

    def create(self, request):
        key, title, tasks, expected_cursor = self._checked_draft(request)
        request_hash = hashlib.sha256(json_bytes(request)).hexdigest()
        now = int(time.time())
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute(
                "SELECT * FROM study_plan_proposals WHERE request_id=?", (key,)
            ).fetchone()
            if existing:
                if existing["request_hash"] != request_hash:
                    raise ApiProblem(409, "PROPOSAL_KEY_REUSED")
                return self._item(existing)
            if expected_cursor is not None:
                current_cursor = db.execute(
                    "SELECT COALESCE(MAX(seq),0) FROM change_log"
                ).fetchone()[0]
                if current_cursor != expected_cursor:
                    raise ApiProblem(409, "STALE_PLAN_CURSOR")
            count = db.execute("""
                SELECT COUNT(*) FROM study_plan_proposals
                WHERE state='pending' AND expires_at>?
            """, (now,)).fetchone()[0]
            if count >= MAX_PENDING:
                raise ApiProblem(429, "TOO_MANY_PENDING_PROPOSALS")
            proposal_id = str(uuid4())
            generated = set()
            mutations = []
            for task in tasks:
                task_id = secrets.randbelow(MAX_TASK_ID - 1) + 1
                while task_id in generated:
                    task_id = secrets.randbelow(MAX_TASK_ID - 1) + 1
                generated.add(task_id)
                payload = dict(task, id=task_id, completed=False)
                mutations.append({
                    "kind": "task", "id": str(task_id), "baseRevision": 0,
                    "deleted": False, "payload": payload
                })
            db.execute("""
                INSERT INTO study_plan_proposals
                    (id,request_id,request_hash,title,mutations_json,
                     created_at,expires_at,state,cursor,base_cursor)
                VALUES(?,?,?,?,?,?,?,?,NULL,?)
            """, (proposal_id, key, request_hash, title,
                  json_bytes(mutations).decode("utf-8"),
                  now, now + EXPIRES_AFTER, "pending", expected_cursor))
            row = db.execute(
                "SELECT * FROM study_plan_proposals WHERE id=?", (proposal_id,)
            ).fetchone()
            return self._item(row)

    def pending(self):
        now = int(time.time())
        with self.store.connect() as db:
            rows = db.execute("""
                SELECT * FROM study_plan_proposals
                WHERE state='pending' AND expires_at>?
                ORDER BY created_at DESC,id LIMIT ?
            """, (now, MAX_PENDING)).fetchall()
        return {"schema": 1, "proposals": [self._item(row) for row in rows]}

    def approve(self, proposal_id):
        if not canonical_uuid(proposal_id):
            raise ApiProblem(400, "INVALID_PROPOSAL_ID")
        # The proposal's approval marker and all newly created tasks must
        # commit in ONE transaction. Otherwise a concurrent rejection could
        # race with active task creation, or a crash could orphan approved tasks.
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT * FROM study_plan_proposals WHERE id=?", (proposal_id,)
            ).fetchone()
            if row is None:
                raise ApiProblem(404, "PROPOSAL_NOT_FOUND")
            if row["state"] == "approved":
                return {"id": proposal_id, "state": "approved", "cursor": row["cursor"]}
            if row["state"] != "pending" or row["expires_at"] <= int(time.time()):
                raise ApiProblem(409, "PROPOSAL_UNAVAILABLE")
            # Fail closed: never approve a plan grounded in a superseded snapshot.
            # Shared SQLite write transaction keeps this check and task insert atomic.
            if row["base_cursor"] is not None:
                head = db.execute("SELECT COALESCE(MAX(seq),0) FROM change_log").fetchone()[0]
                if head != row["base_cursor"]:
                    raise ApiProblem(409, "STALE_PLAN_CURSOR")
            body = {
                "requestId": proposal_id,
                "mutations": json.loads(row["mutations_json"])
            }
            result = self.store.apply(body, connection=db)
            changed = db.execute("""
                UPDATE study_plan_proposals SET state='approved',cursor=?
                WHERE id=? AND state='pending'
            """, (result["cursor"], proposal_id))
            if changed.rowcount != 1:
                raise ApiProblem(409, "PROPOSAL_UNAVAILABLE")
            return {"id": proposal_id, "state": "approved", "cursor": result["cursor"]}

    def reject(self, proposal_id):
        if not canonical_uuid(proposal_id):
            raise ApiProblem(400, "INVALID_PROPOSAL_ID")
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT state FROM study_plan_proposals WHERE id=?", (proposal_id,)
            ).fetchone()
            if row is None:
                raise ApiProblem(404, "PROPOSAL_NOT_FOUND")
            if row["state"] == "approved":
                raise ApiProblem(409, "PROPOSAL_ALREADY_APPROVED")
            if row["state"] == "pending":
                db.execute(
                    "UPDATE study_plan_proposals SET state='rejected' WHERE id=?",
                    (proposal_id,)
                )
        return {"id": proposal_id, "state": "rejected"}
