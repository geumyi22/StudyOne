#!/usr/bin/env python3
"""StudyOne single-owner, loopback-only sync reference server (Canary).

Uses only Python's standard library and SQLite; does not access OpenAI, NEIS,
GitHub, or any network service itself. Never expose directly to the Internet.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import date
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import os
from pathlib import Path
import sqlite3
import threading
import time
from urllib.parse import parse_qs, urlsplit
from uuid import UUID

STATUSES = frozenset(("확인 필요", "미완료", "진행 중", "완료"))
MAX_TASK_ID = 9223372036854775807
MAX_BODY = 262_144
MAX_MUTATIONS = 50
MAX_PAGE = 500
TASK_FIELDS = frozenset(("id", "type", "subject", "title", "dueDate", "importance", "completed"))
PROGRESS_FIELDS = frozenset(("id", "subject", "material", "unit", "status",
                             "inExamScope", "revision", "updatedAt"))


class ApiProblem(Exception):
    def __init__(self, status: int, code: str):
        super().__init__(code)
        self.status = status
        self.code = code


def _text(value, maxlen):
    return isinstance(value, str) and 1 <= len(value.strip()) <= maxlen


def _uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(UUID(value)).lower() == value.lower()
    except (ValueError, AttributeError):
        return False


def _integer(value, min_value, max_value):
    return type(value) is int and min_value <= value <= max_value


def validate_record(kind: str, identifier: str, payload: dict) -> dict:
    """Strict portable shape; no API keys, school information or extra fields."""
    if not isinstance(payload, dict):
        raise ApiProblem(400, "INVALID_RECORD")
    if kind == "task":
        if set(payload) != TASK_FIELDS:
            raise ApiProblem(400, "INVALID_TASK_FIELDS")
        if (not _integer(payload["id"], 1, MAX_TASK_ID)
                or str(payload["id"]) != identifier
                or not _text(payload["type"], 100)
                or not isinstance(payload["subject"], str)
                or len(payload["subject"]) > 60
                or not _text(payload["title"], 500)
                or not _integer(payload["importance"], 1, 3)
                or type(payload["completed"]) is not bool):
            raise ApiProblem(400, "INVALID_TASK")
        try:
            if not isinstance(payload["dueDate"], str) or len(payload["dueDate"]) != 10:
                raise ValueError
            parsed_date = date.fromisoformat(payload["dueDate"])
            if parsed_date.isoformat() != payload["dueDate"]:
                raise ValueError
        except ValueError:
            raise ApiProblem(400, "INVALID_TASK_DATE") from None
        return dict(payload)
    if kind == "progress":
        if set(payload) != PROGRESS_FIELDS:
            raise ApiProblem(400, "INVALID_PROGRESS_FIELDS")
        if (not _uuid(identifier)
                or payload["id"] != identifier
                or not _text(payload["subject"], 60)
                or not _text(payload["material"], 120)
                or not _text(payload["unit"], 200)
                or payload["status"] not in STATUSES
                or type(payload["inExamScope"]) is not bool
                or not _integer(payload["revision"], 1, MAX_TASK_ID)
                or not _integer(payload["updatedAt"], 0, MAX_TASK_ID)):
            raise ApiProblem(400, "INVALID_PROGRESS")
        return dict(payload)
    raise ApiProblem(400, "UNKNOWN_KIND")


def _strict_pairs(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("duplicate JSON key")
        obj[key] = value
    return obj


def decode_json(raw: bytes):
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-finite")))
    except (ValueError, UnicodeDecodeError):
        raise ApiProblem(400, "INVALID_JSON") from None


def json_bytes(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


class SyncStore:
    """Transactionally maintained last-write-protected server record database."""

    def __init__(self, db_path: str | Path):
        self.db_path = str(db_path)
        parent = Path(self.db_path).resolve().parent
        parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS records (
                    kind TEXT NOT NULL,
                    id TEXT NOT NULL,
                    payload TEXT,
                    revision INTEGER NOT NULL CHECK(revision >= 1),
                    deleted INTEGER NOT NULL CHECK(deleted IN (0,1)),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(kind,id)
                );
                CREATE TABLE IF NOT EXISTS change_log (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    id TEXT NOT NULL,
                    payload TEXT,
                    revision INTEGER NOT NULL,
                    deleted INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS requests (
                    request_id TEXT PRIMARY KEY,
                    body_hash TEXT NOT NULL,
                    response TEXT NOT NULL
                );
            """)
        try:
            os.chmod(self.db_path, 0o600)
        except OSError:
            pass

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.db_path, timeout=5.0)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=5000")
        db.execute("PRAGMA journal_mode=WAL")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _item(row):
        return {"kind": row["kind"], "id": row["id"],
                "revision": row["revision"], "deleted": bool(row["deleted"]),
                "updatedAt": row["updated_at"],
                "payload": json.loads(row["payload"]) if row["payload"] is not None else None}

    def snapshot(self):
        # One pinned read snapshot: cursor never outruns records returned here.
        with self.connect() as db:
            db.execute("BEGIN")
            cursor = db.execute("SELECT COALESCE(MAX(seq),0) FROM change_log").fetchone()[0]
            items = db.execute("""
                SELECT kind,id,payload,revision,deleted,updated_at
                FROM records WHERE deleted=0 ORDER BY kind,id
            """).fetchall()
            return {"schema": 1, "cursor": cursor,
                    "records": [self._item(row) for row in items]}

    def changes(self, after: int, limit: int):
        if not _integer(after, 0, MAX_TASK_ID) or not _integer(limit, 1, MAX_PAGE):
            raise ApiProblem(400, "INVALID_CURSOR")
        with self.connect() as db:
            db.execute("BEGIN")
            head = db.execute("SELECT COALESCE(MAX(seq),0) FROM change_log").fetchone()[0]
            if after > head:
                raise ApiProblem(400, "FUTURE_CURSOR")
            rows = db.execute("""
                SELECT seq,kind,id,payload,revision,deleted,updated_at
                FROM change_log WHERE seq > ? ORDER BY seq LIMIT ?
            """, (after, limit + 1)).fetchall()
            page = rows[:limit]
            results = [dict({"seq": row["seq"]}, **self._item(row)) for row in page]
            return {"schema": 1, "head": head, "after": after,
                    "nextCursor": page[-1]["seq"] if page else after,
                    "hasMore": len(rows) > limit, "changes": results}

    def apply(self, body):
        if not isinstance(body, dict) or set(body) != {"requestId", "mutations"}:
            raise ApiProblem(400, "INVALID_REQUEST")
        request_id = body["requestId"]
        mutations = body["mutations"]
        if (not _uuid(request_id) or not isinstance(mutations, list)
                or not 1 <= len(mutations) <= MAX_MUTATIONS):
            raise ApiProblem(400, "INVALID_REQUEST")
        seen = set()
        validated = []
        for mutation in mutations:
            if not isinstance(mutation, dict) or set(mutation) != {
                "kind", "id", "baseRevision", "deleted", "payload"
            }:
                raise ApiProblem(400, "INVALID_MUTATION")
            kind, identifier = mutation["kind"], mutation["id"]
            deleted = mutation["deleted"]
            base = mutation["baseRevision"]
            if (kind not in ("task", "progress")
                    or not isinstance(identifier, str)
                    or (kind == "task" and (
                        not identifier.isdigit() or
                        not _integer(int(identifier), 1, MAX_TASK_ID) or
                        str(int(identifier)) != identifier))
                    or (kind == "progress" and not _uuid(identifier))
                    or type(deleted) is not bool
                    or not _integer(base, 0, MAX_TASK_ID - 1)):
                raise ApiProblem(400, "INVALID_MUTATION")
            if (kind, identifier) in seen:
                raise ApiProblem(400, "DUPLICATE_MUTATION")
            seen.add((kind, identifier))
            if deleted:
                if mutation["payload"] is not None or base == 0:
                    raise ApiProblem(400, "INVALID_DELETE")
                validated.append((kind, identifier, base, True, None))
            else:
                payload = validate_record(kind, identifier, mutation["payload"])
                validated.append((kind, identifier, base, False, payload))
        fingerprint = hashlib.sha256(json_bytes(body)).hexdigest()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute(
                "SELECT body_hash,response FROM requests WHERE request_id=?", (request_id,)
            ).fetchone()
            if previous:
                if previous["body_hash"] != fingerprint:
                    raise ApiProblem(409, "IDEMPOTENCY_KEY_REUSED")
                return json.loads(previous["response"])
            # Preflight entire batch; no partial commits on conflict.
            for kind, identifier, base, deleted, _ in validated:
                stored = db.execute(
                    "SELECT revision,deleted FROM records WHERE kind=? AND id=?",
                    (kind, identifier)
                ).fetchone()
                if (stored is None and base != 0) or (
                    stored is not None and (stored["revision"] != base or
                                            (stored["deleted"] == 1 and not deleted))
                ):
                    raise ApiProblem(409, "REVISION_CONFLICT")
                if stored is not None and stored["deleted"] and deleted:
                    raise ApiProblem(409, "ALREADY_DELETED")
            now = int(time.time() * 1000)
            changes = []
            for kind, identifier, base, deleted, payload in validated:
                revision = base + 1
                if payload is not None and kind == "progress":
                    payload["revision"] = revision
                    payload["updatedAt"] = now
                encoded = json_bytes(payload).decode("utf-8") if payload is not None else None
                db.execute("""
                    INSERT INTO records(kind,id,payload,revision,deleted,updated_at)
                    VALUES(?,?,?,?,?,?)
                    ON CONFLICT(kind,id) DO UPDATE SET
                    payload=excluded.payload,revision=excluded.revision,
                    deleted=excluded.deleted,updated_at=excluded.updated_at
                """, (kind, identifier, encoded, revision, int(deleted), now))
                cursor = db.execute("""
                    INSERT INTO change_log(kind,id,payload,revision,deleted,updated_at)
                    VALUES(?,?,?,?,?,?)
                """, (kind, identifier, encoded, revision, int(deleted), now)).lastrowid
                changes.append({"seq": cursor, "kind": kind, "id": identifier,
                                "revision": revision, "deleted": deleted, "updatedAt": now})
            response = {"schema": 1, "cursor": changes[-1]["seq"], "changes": changes}
            db.execute("INSERT INTO requests(request_id,body_hash,response) VALUES(?,?,?)",
                       (request_id, fingerprint, json_bytes(response).decode("utf-8")))
            return response


def make_handler(store: SyncStore, token: str):
    class Handler(BaseHTTPRequestHandler):
        server_version = "StudyOneSyncCanary/0.1"
        sys_version = ""

        def log_message(self, format_, *args):
            # Suppress paths, query strings and any accidental personal study info.
            pass

        def send_json(self, status: int, value):
            raw = json_bytes(value)
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            supplied = self.headers.get("Authorization", "")
            if not supplied.startswith("Bearer "):
                return False
            return hmac.compare_digest(supplied[7:].encode("utf-8"),
                                       token.encode("utf-8"))

        def run_api(self, verb):
            try:
                if not self.authorized():
                    raise ApiProblem(401, "UNAUTHORIZED")
                path = urlsplit(self.path)
                if verb == "GET":
                    if path.path == "/v1/snapshot":
                        return self.send_json(200, store.snapshot())
                    if path.path == "/v1/changes":
                        values = parse_qs(path.query)
                        if set(values) - {"after", "limit"}:
                            raise ApiProblem(400, "INVALID_QUERY")
                        try:
                            after = int(values.get("after", ["0"])[0])
                            limit = int(values.get("limit", ["200"])[0])
                        except ValueError:
                            raise ApiProblem(400, "INVALID_QUERY") from None
                        return self.send_json(200, store.changes(after, limit))
                elif verb == "POST" and path.path == "/v1/changes":
                    if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                        raise ApiProblem(415, "JSON_REQUIRED")
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                    except ValueError:
                        raise ApiProblem(400, "INVALID_LENGTH") from None
                    if not 0 < length <= MAX_BODY:
                        raise ApiProblem(413, "REQUEST_TOO_LARGE")
                    payload = decode_json(self.rfile.read(length))
                    return self.send_json(200, store.apply(payload))
                raise ApiProblem(404, "NOT_FOUND")
            except ApiProblem as exc:
                return self.send_json(exc.status, {"error": exc.code})
            except (sqlite3.Error, OverflowError):
                return self.send_json(500, {"error": "DATABASE_ERROR"})

        def do_GET(self):
            self.run_api("GET")

        def do_POST(self):
            self.run_api("POST")
    return Handler


def run_server(db_path, host, port, token):
    # Plain HTTP must not be used across a network. Future remote deployment
    # requires explicit TLS termination, account auth, secret rotation and audit.
    if host not in ("127.0.0.1", "::1", "localhost"):
        raise RuntimeError("Loopback binding only; remote sync is not enabled.")
    if not isinstance(token, str) or len(token) < 32:
        raise RuntimeError("Set STUDYONE_SYNC_TOKEN to a random secret of at least 32 characters.")
    store = SyncStore(db_path)
    server = ThreadingHTTPServer((host, port), make_handler(store, token))
    print(f"StudyOne sync Canary listening on {host}:{server.server_port} (local only)")
    try:
        server.serve_forever()
    finally:
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description="StudyOne loopback sync API (Canary)")
    parser.add_argument("--db", default=str(Path.home() / ".studyone" / "sync.sqlite3"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    run_server(args.db, args.host, args.port, os.environ.get("STUDYONE_SYNC_TOKEN"))


if __name__ == "__main__":
    main()
