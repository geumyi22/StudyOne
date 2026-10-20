#!/usr/bin/env python3
"""StudyOne private account-scoped HTTPS sync prototype (Canary).

No users, tokens or server are created merely by importing this module.
Provisioning and remote exposure require an explicit local operator command.
Not an OpenAI/ChatGPT integration. Never run the legacy HTTP server publicly.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import ssl
import threading
import time
from urllib.parse import parse_qs, urlsplit
from uuid import UUID, uuid4

try:
    from .studyone_sync import (
        ApiProblem, MAX_BODY, MAX_PAGE, SyncStore, decode_json, json_bytes
    )
except ImportError:
    from studyone_sync import (
        ApiProblem, MAX_BODY, MAX_PAGE, SyncStore, decode_json, json_bytes
    )


def uuid_valid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(UUID(value)) == value.lower()
    except ValueError:
        return False


def token_digest(token):
    if not isinstance(token, str) or not 40 <= len(token) <= 256:
        return None
    if not all(char in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
               "0123456789-_" for char in token):
        return None
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class Registry:
    """Separate credential database; raw bearer tokens are never persisted."""

    def __init__(self, registry_path, data_root):
        self.path = Path(registry_path).resolve()
        self.root = Path(data_root).resolve()
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY,
                    label TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    disabled INTEGER NOT NULL DEFAULT 0 CHECK(disabled IN (0,1))
                );
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(id),
                    label TEXT NOT NULL,
                    digest TEXT NOT NULL UNIQUE,
                    scope TEXT NOT NULL CHECK(scope IN ('read','read-write')),
                    issued_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1))
                );
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    account_id TEXT NOT NULL,
                    device_id TEXT,
                    action TEXT NOT NULL,
                    detail TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_devices_account
                    ON devices(account_id, revoked);
            """)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    @contextmanager
    def connect(self):
        db = sqlite3.connect(str(self.path), timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=5000")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _label(value):
        if not isinstance(value, str) or not 1 <= len(value.strip()) <= 60:
            raise ValueError("Provide a label between 1 and 60 characters")
        if any(ord(c) < 32 for c in value):
            raise ValueError("No control characters in labels")
        return value.strip()

    def account(self, label):
        label = self._label(label)
        account_id = str(uuid4())
        with self.connect() as db:
            db.execute("INSERT INTO accounts(id,label,created_at) VALUES(?,?,?)",
                       (account_id, label, int(time.time())))
        return account_id

    def device(self, account_id, label, scope="read-write", days=30):
        if not uuid_valid(account_id):
            raise ValueError("Invalid account ID")
        label = self._label(label)
        if scope not in ("read", "read-write") or type(days) is not int or not 1 <= days <= 365:
            raise ValueError("Invalid device scope or expiry")
        device_id = str(uuid4())
        raw_token = secrets.token_urlsafe(48)
        digest = token_digest(raw_token)
        now = int(time.time())
        with self.connect() as db:
            current = db.execute(
                "SELECT disabled FROM accounts WHERE id=?", (account_id,)
            ).fetchone()
            if current is None or current["disabled"]:
                raise ValueError("Unknown or disabled account")
            db.execute("""
                INSERT INTO devices(id,account_id,label,digest,scope,issued_at,expires_at)
                VALUES(?,?,?,?,?,?,?)
            """, (device_id, account_id, label, digest, scope,
                  now, now + days * 86400))
            self._audit(db, account_id, device_id, "DEVICE_ISSUED", scope)
        return {"accountId": account_id, "deviceId": device_id,
                "scope": scope, "expiresAt": now + days * 86400,
                "token": raw_token}

    @staticmethod
    def _audit(db, account_id, device_id, action, detail=""):
        db.execute("""
            INSERT INTO audit(timestamp,account_id,device_id,action,detail)
            VALUES(?,?,?,?,?)
        """, (int(time.time()), account_id, device_id, action, detail))

    def revoke(self, device_id):
        if not uuid_valid(device_id):
            raise ValueError("Invalid device ID")
        with self.connect() as db:
            device = db.execute("SELECT account_id,revoked FROM devices WHERE id=?",
                                (device_id,)).fetchone()
            if device is None:
                raise ValueError("Unknown device")
            if not device["revoked"]:
                db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device_id,))
                self._audit(db, device["account_id"], device_id, "DEVICE_REVOKED")

    def disable(self, account_id):
        if not uuid_valid(account_id):
            raise ValueError("Invalid account ID")
        with self.connect() as db:
            row = db.execute("SELECT disabled FROM accounts WHERE id=?",
                             (account_id,)).fetchone()
            if row is None:
                raise ValueError("Unknown account")
            if not row["disabled"]:
                db.execute("UPDATE accounts SET disabled=1 WHERE id=?", (account_id,))
                self._audit(db, account_id, None, "ACCOUNT_DISABLED")

    def authorize(self, raw_token, write=False):
        digest = token_digest(raw_token)
        if digest is None:
            raise ApiProblem(401, "UNAUTHORIZED")
        with self.connect() as db:
            record = db.execute("""
                SELECT d.id AS device_id, d.account_id, d.scope, d.expires_at,
                       d.revoked, a.disabled FROM devices d
                JOIN accounts a ON a.id=d.account_id
                WHERE d.digest=?
            """, (digest,)).fetchone()
        if (record is None or record["revoked"] or record["disabled"] or
                record["expires_at"] <= int(time.time())):
            raise ApiProblem(401, "UNAUTHORIZED")
        if write and record["scope"] != "read-write":
            raise ApiProblem(403, "WRITE_FORBIDDEN")
        return record["account_id"], record["device_id"]

    def store(self, account_id):
        # Account IDs must originate from the registry, not a client supplied path.
        if not uuid_valid(account_id):
            raise ValueError("Invalid account ID")
        return SyncStore(self.root / (account_id + ".sqlite3"))

    def audit_write(self, account_id, device_id, count):
        with self.connect() as db:
            self._audit(db, account_id, device_id, "SYNC_MUTATIONS",
                        str(count))


def handler_for(registry: Registry, *, tls_enabled=True):
    class Handler(BaseHTTPRequestHandler):
        server_version = "StudyOnePrivateSync/0.2"
        sys_version = ""
        protocol_version = "HTTP/1.1"

        def log_message(self, message, *args):
            # Do not log endpoints, tokens, learner names or task content.
            pass

        def send_json(self, status, payload):
            blob = json_bytes(payload)
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(blob)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Frame-Options", "DENY")
            if tls_enabled:
                self.send_header("Strict-Transport-Security", "max-age=31536000")
            self.end_headers()
            self.wfile.write(blob)

        def handle_api(self, method):
            try:
                if self.headers.get("Transfer-Encoding"):
                    raise ApiProblem(400, "TRANSFER_ENCODING_UNSUPPORTED")
                if self.headers.get("Origin"):
                    raise ApiProblem(403, "BROWSER_ORIGIN_UNSUPPORTED")
                endpoint = urlsplit(self.path)
                if endpoint.path == "/v2/health" and method == "GET":
                    return self.send_json(200, {"status": "ok", "schema": 2})
                if endpoint.path not in ("/v2/snapshot", "/v2/changes"):
                    raise ApiProblem(404, "NOT_FOUND")
                if method == "GET" and endpoint.path == "/v2/changes":
                    opts = parse_qs(endpoint.query, keep_blank_values=True)
                    if (set(opts) - {"after", "limit"} or
                            any(len(values) != 1 for values in opts.values())):
                        raise ApiProblem(400, "INVALID_QUERY")
                    try:
                        after = int(opts.get("after", ["0"])[0])
                        limit = int(opts.get("limit", ["200"])[0])
                    except ValueError:
                        raise ApiProblem(400, "INVALID_QUERY") from None
                elif endpoint.query:
                    raise ApiProblem(400, "INVALID_QUERY")
                if method == "POST" and endpoint.path != "/v2/changes":
                    raise ApiProblem(405, "METHOD_NOT_ALLOWED")
                if method not in ("GET", "POST"):
                    raise ApiProblem(405, "METHOD_NOT_ALLOWED")
                auth = self.headers.get("Authorization", "")
                if not auth.startswith("Bearer ") or auth.count(" ") != 1:
                    raise ApiProblem(401, "UNAUTHORIZED")
                account, device = registry.authorize(auth[7:], write=(method == "POST"))
                store = registry.store(account)
                if method == "GET":
                    if endpoint.path == "/v2/snapshot":
                        return self.send_json(200, store.snapshot())
                    return self.send_json(200, store.changes(after, limit))
                content_type = self.headers.get("Content-Type", "").split(";")[0].strip()
                if content_type != "application/json":
                    raise ApiProblem(415, "JSON_REQUIRED")
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise ApiProblem(400, "INVALID_LENGTH") from None
                if not 0 < length <= MAX_BODY:
                    raise ApiProblem(413, "REQUEST_TOO_LARGE")
                body = decode_json(self.rfile.read(length))
                result = store.apply(body)
                # The mutation is committed before the bounded audit write.
                # The response stays valid if audit persistence fails.
                try:
                    registry.audit_write(account, device, len(body["mutations"]))
                except sqlite3.Error:
                    pass
                return self.send_json(200, result)
            except ApiProblem as err:
                return self.send_json(err.status, {"error": err.code})
            except (sqlite3.Error, OSError, OverflowError):
                return self.send_json(500, {"error": "STORAGE_ERROR"})

        def do_GET(self):
            self.handle_api("GET")

        def do_POST(self):
            self.handle_api("POST")
    return Handler


def serve(registry, address, port, certificate, key, allow_remote=False):
    if not certificate or not key:
        raise RuntimeError("TLS certificate and private key are required even on loopback")
    if address not in ("127.0.0.1", "::1", "localhost") and not allow_remote:
        raise RuntimeError("Remote binding requires explicit --allow-remote")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=certificate, keyfile=key)
    server = ThreadingHTTPServer((address, port), handler_for(registry))
    server.daemon_threads = True
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print("StudyOne private HTTPS server ready; per-device bearer authentication enabled",
          flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description="StudyOne private HTTPS sync Canary")
    parser.add_argument("--registry", default=str(Path.home() / ".studyone" / "accounts.sqlite3"))
    parser.add_argument("--data-root", default=str(Path.home() / ".studyone" / "accounts"))
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create-account")
    create.add_argument("--label", required=True)
    issue = commands.add_parser("issue-device")
    issue.add_argument("--account", required=True)
    issue.add_argument("--label", required=True)
    issue.add_argument("--scope", choices=("read", "read-write"), default="read-write")
    issue.add_argument("--days", type=int, default=30)
    revoke = commands.add_parser("revoke-device")
    revoke.add_argument("--device", required=True)
    disable = commands.add_parser("disable-account")
    disable.add_argument("--account", required=True)
    run = commands.add_parser("serve")
    run.add_argument("--bind", default="127.0.0.1")
    run.add_argument("--port", type=int, default=9443)
    run.add_argument("--cert", required=True)
    run.add_argument("--key", required=True)
    run.add_argument("--allow-remote", action="store_true")
    args = parser.parse_args()
    registry = Registry(args.registry, args.data_root)
    if args.command == "create-account":
        print(json.dumps({"accountId": registry.account(args.label)}, ensure_ascii=False))
    elif args.command == "issue-device":
        # Secret shown only once on the local operator terminal.
        print(json.dumps(registry.device(args.account, args.label, args.scope, args.days),
                         ensure_ascii=False))
    elif args.command == "revoke-device":
        registry.revoke(args.device)
        print("Device revoked")
    elif args.command == "disable-account":
        registry.disable(args.account)
        print("Account disabled")
    elif args.command == "serve":
        serve(registry, args.bind, args.port, args.cert, args.key, args.allow_remote)


if __name__ == "__main__":
    main()
