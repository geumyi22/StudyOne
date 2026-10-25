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
                    audience TEXT NOT NULL DEFAULT 'device' CHECK(audience IN ('device','mcp')),
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
            # Existing registry databases predate audience segregation. Preserve
            # all existing tokens as device-scoped, never implicitly MCP-scoped.
            columns = {row["name"] for row in db.execute("PRAGMA table_info(devices)")}
            if "audience" not in columns:
                db.execute("""
                    ALTER TABLE devices ADD COLUMN audience TEXT NOT NULL DEFAULT 'device'
                    CHECK(audience IN ('device','mcp'))
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

    def device(self, account_id, label, scope="read-write", days=30,
               audience="device"):
        if not uuid_valid(account_id):
            raise ValueError("Invalid account ID")
        label = self._label(label)
        if scope not in ("read", "read-write") or type(days) is not int or not 1 <= days <= 365:
            raise ValueError("Invalid device scope or expiry")
        # Assistant-only bearer grants ONLY MCP tool access, never raw /v2
        # sync or approval endpoints. It cannot approve its own proposed plans.
        if audience not in ("device", "mcp") or (audience == "mcp" and scope != "read"):
            raise ValueError("MCP tokens must be read-scoped and MCP-only")
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
                INSERT INTO devices(id,account_id,label,digest,scope,audience,issued_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?)
            """, (device_id, account_id, label, digest, scope, audience,
                  now, now + days * 86400))
            self._audit(db, account_id, device_id, "DEVICE_ISSUED",
                        scope + ":" + audience)
        return {"accountId": account_id, "deviceId": device_id,
                "scope": scope, "audience": audience,
                "expiresAt": now + days * 86400, "token": raw_token}

    def list_devices(self, account_id):
        """Operator-only inventory; labels/ids permitted, NEVER bearer/digest."""
        if not uuid_valid(account_id):
            raise ValueError("Invalid account ID")
        with self.connect() as db:
            account = db.execute("SELECT id FROM accounts WHERE id=?",
                                 (account_id,)).fetchone()
            if account is None:
                raise ValueError("Unknown account")
            rows = db.execute("""
                SELECT id,label,scope,audience,issued_at,expires_at,revoked
                FROM devices WHERE account_id=? ORDER BY issued_at,id
            """, (account_id,)).fetchall()
        return [{"deviceId": row["id"], "label": row["label"],
                 "scope": row["scope"], "audience": row["audience"],
                 "issuedAt": row["issued_at"], "expiresAt": row["expires_at"],
                 "revoked": bool(row["revoked"])} for row in rows]

    def rotate(self, device_id, days=30):
        """Operator action. New random bearer replaces old digest atomically.

        Revoked, disabled and expired devices cannot be silently revived.
        The replacement raw token is returned only once and never persisted.
        """
        if not uuid_valid(device_id) or type(days) is not int or not 1 <= days <= 365:
            raise ValueError("Invalid rotation parameters")
        token = secrets.token_urlsafe(48)
        digest = token_digest(token)
        now = int(time.time())
        with self.connect() as db:
            row = db.execute("""
                SELECT d.account_id,d.scope,d.audience,d.revoked,d.expires_at,
                       a.disabled FROM devices d
                JOIN accounts a ON a.id=d.account_id WHERE d.id=?
            """, (device_id,)).fetchone()
            if (row is None or row["revoked"] or row["disabled"] or
                    row["expires_at"] <= now):
                raise ValueError("Unknown, disabled, expired or revoked device")
            db.execute("""
                UPDATE devices SET digest=?,issued_at=?,expires_at=?
                WHERE id=?
            """, (digest, now, now + days * 86400, device_id))
            self._audit(db, row["account_id"], device_id, "DEVICE_ROTATED",
                        row["audience"])
        return {"accountId": row["account_id"], "deviceId": device_id,
                "scope": row["scope"], "audience": row["audience"],
                "expiresAt": now + days * 86400, "token": token}

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

    def authorize(self, raw_token, write=False, allowed_audiences=("device",)):
        digest = token_digest(raw_token)
        if digest is None:
            raise ApiProblem(401, "UNAUTHORIZED")
        with self.connect() as db:
            record = db.execute("""
                SELECT d.id AS device_id, d.account_id, d.scope, d.audience,
                       d.expires_at, d.revoked, a.disabled FROM devices d
                JOIN accounts a ON a.id=d.account_id
                WHERE d.digest=?
            """, (digest,)).fetchone()
        if (record is None or record["revoked"] or record["disabled"] or
                record["expires_at"] <= int(time.time())):
            raise ApiProblem(401, "UNAUTHORIZED")
        if record["audience"] not in allowed_audiences:
            raise ApiProblem(403, "TOKEN_AUDIENCE_FORBIDDEN")
        if write and record["scope"] != "read-write":
            raise ApiProblem(403, "WRITE_FORBIDDEN")
        return record["account_id"], record["device_id"]

    def session(self, raw_token):
        """Read-only, device-audience-only authenticated session diagnostics.

        No raw token/digest or private account label is included. Used by
        client connection checks instead of the unauthenticated /v2/health.
        """
        account, device = self.authorize(raw_token, allowed_audiences=("device",))
        with self.connect() as db:
            row = db.execute("""
                SELECT d.scope,d.audience,d.expires_at,d.revoked,a.disabled
                FROM devices d JOIN accounts a ON a.id=d.account_id
                WHERE d.id=? AND a.id=?
            """, (device, account)).fetchone()
        if row is None or row["revoked"] or row["disabled"] or row["expires_at"] <= int(time.time()):
            raise ApiProblem(401, "UNAUTHORIZED")
        return {"schema": 1, "accountId": account, "deviceId": device,
                "scope": row["scope"], "audience": row["audience"],
                "expiresAt": row["expires_at"], "serverTime": int(time.time())}

    def store(self, account_id):
        # Account IDs must originate from the registry, not a client supplied path.
        if not uuid_valid(account_id):
            raise ValueError("Invalid account ID")
        return SyncStore(self.root / (account_id + ".sqlite3"))

    def audit_write(self, account_id, device_id, count):
        with self.connect() as db:
            self._audit(db, account_id, device_id, "SYNC_MUTATIONS",
                        str(count))


def handler_for(registry: Registry, *, tls_enabled=True, oauth=None):
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
            if status == 401 and oauth is not None and urlsplit(self.path).path == "/mcp":
                self.send_header("WWW-Authenticate", oauth.challenge())
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
                if (oauth is not None and method == "GET" and
                        endpoint.path in ("/.well-known/oauth-protected-resource/mcp",
                                          "/.well-known/oauth-protected-resource")):
                    if endpoint.query:
                        raise ApiProblem(400, "INVALID_QUERY")
                    return self.send_json(200, oauth.metadata())
                if endpoint.path == "/mcp" and method == "POST":
                    if endpoint.query:
                        raise ApiProblem(400, "INVALID_QUERY")
                    auth = self.headers.get("Authorization", "")
                    if not auth.startswith("Bearer ") or auth.count(" ") != 1:
                        raise ApiProblem(401, "UNAUTHORIZED")
                    oauth_scopes = None
                    # OAuth JWTs NEVER fall back to an old device token if
                    # issuer, signature, audience, expiry or account link fails.
                    if auth[7:].count(".") == 2:
                        if oauth is None:
                            raise ApiProblem(401, "UNAUTHORIZED")
                        account, oauth_scopes = oauth.authorize(auth[7:])
                    else:
                        account, _ = registry.authorize(
                            auth[7:], write=False, allowed_audiences=("device", "mcp"))
                    if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                        raise ApiProblem(415, "JSON_REQUIRED")
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                    except ValueError:
                        raise ApiProblem(400, "INVALID_LENGTH") from None
                    if not 0 < length <= MAX_BODY:
                        raise ApiProblem(413, "REQUEST_TOO_LARGE")
                    request = decode_json(self.rfile.read(length))
                    try:
                        from .studyone_mcp import dispatch
                    except ImportError:
                        from studyone_mcp import dispatch
                    if oauth_scopes is not None:
                        # A read-only OAuth grant may inspect active progress,
                        # but must never create a proposal without extra scope.
                        method_name = request.get("method") if isinstance(request, dict) else None
                        params = request.get("params") if isinstance(request, dict) else None
                        if (method_name == "tools/call" and isinstance(params, dict)
                                and params.get("name") == "studyone_propose_study_plan"
                                and "studyone.propose" not in oauth_scopes):
                            raise ApiProblem(403, "INSUFFICIENT_SCOPE")
                    response = dispatch(registry, account, request)
                    if (oauth_scopes is not None and
                            "studyone.propose" not in oauth_scopes and
                            isinstance(response, dict) and
                            request.get("method") == "tools/list" and
                            isinstance(response.get("result"), dict)):
                        response["result"]["tools"] = [
                            item for item in response["result"]["tools"]
                            if item["name"] != "studyone_propose_study_plan"]
                    if response is None:
                        self.send_response(202)
                        self.send_header("Content-Length", "0")
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        return
                    return self.send_json(200, response)
                if endpoint.path == "/v2/health" and method == "GET":
                    return self.send_json(200, {"status": "ok", "schema": 2})
                parts = endpoint.path.split("/")
                proposal_action = None
                proposal_id = None
                if (len(parts) == 5 and parts[1:3] == ["v2", "proposals"]
                        and parts[4] in ("approve", "reject")):
                    proposal_id = parts[3]
                    proposal_action = parts[4]
                if (endpoint.path not in
                        ("/v2/snapshot", "/v2/changes", "/v2/proposals",
                         "/v2/session")
                        and proposal_action is None):
                    raise ApiProblem(404, "NOT_FOUND")
                if method == "GET" and proposal_action is not None:
                    raise ApiProblem(405, "METHOD_NOT_ALLOWED")
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
                if (method == "POST" and endpoint.path not in
                        ("/v2/changes", "/v2/proposals") and proposal_action is None):
                    raise ApiProblem(405, "METHOD_NOT_ALLOWED")
                if method not in ("GET", "POST"):
                    raise ApiProblem(405, "METHOD_NOT_ALLOWED")
                auth = self.headers.get("Authorization", "")
                if not auth.startswith("Bearer ") or auth.count(" ") != 1:
                    raise ApiProblem(401, "UNAUTHORIZED")
                requires_write = method == "POST" and endpoint.path != "/v2/proposals"
                account, device = registry.authorize(auth[7:], write=requires_write)
                if method == "GET" and endpoint.path == "/v2/session":
                    return self.send_json(200, registry.session(auth[7:]))
                store = registry.store(account)
                if method == "GET":
                    if endpoint.path == "/v2/snapshot":
                        return self.send_json(200, store.snapshot())
                    if endpoint.path == "/v2/proposals":
                        try:
                            from .studyone_proposals import ProposalStore
                        except ImportError:
                            from studyone_proposals import ProposalStore
                        return self.send_json(200, ProposalStore(store).pending())
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
                if endpoint.path == "/v2/proposals" or proposal_action is not None:
                    try:
                        from .studyone_proposals import ProposalStore
                    except ImportError:
                        from studyone_proposals import ProposalStore
                    proposals = ProposalStore(store)
                    if endpoint.path == "/v2/proposals":
                        return self.send_json(200, proposals.create(body))
                    if body != {}:
                        raise ApiProblem(400, "INVALID_ACTION_BODY")
                    if proposal_action == "approve":
                        return self.send_json(200, proposals.approve(proposal_id))
                    return self.send_json(200, proposals.reject(proposal_id))
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


def serve(registry, address, port, certificate, key, allow_remote=False, oauth=None):
    if not certificate or not key:
        raise RuntimeError("TLS certificate and private key are required even on loopback")
    if address not in ("127.0.0.1", "::1", "localhost") and not allow_remote:
        raise RuntimeError("Remote binding requires explicit --allow-remote")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=certificate, keyfile=key)
    server = ThreadingHTTPServer((address, port), handler_for(registry, oauth=oauth))
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
    assistant = commands.add_parser("issue-assistant")
    assistant.add_argument("--account", required=True)
    assistant.add_argument("--label", required=True)
    assistant.add_argument("--days", type=int, default=7)
    devices = commands.add_parser("list-devices")
    devices.add_argument("--account", required=True)
    rotation = commands.add_parser("rotate-device")
    rotation.add_argument("--device", required=True)
    rotation.add_argument("--days", type=int, default=30)
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
    run.add_argument("--oauth-config",
                     help="Pinned public RS256 JWKS + OAuth issuer resource; optional")
    link = commands.add_parser("link-oauth")
    link.add_argument("--oauth-config", required=True)
    link.add_argument("--account", required=True)
    link.add_argument("--subject", required=True)
    unlink = commands.add_parser("unlink-oauth")
    unlink.add_argument("--oauth-config", required=True)
    unlink.add_argument("--subject", required=True)
    args = parser.parse_args()
    registry = Registry(args.registry, args.data_root)
    if args.command == "create-account":
        print(json.dumps({"accountId": registry.account(args.label)}, ensure_ascii=False))
    elif args.command == "issue-device":
        # Secret shown only once on the local operator terminal.
        print(json.dumps(registry.device(args.account, args.label, args.scope, args.days),
                         ensure_ascii=False))
    elif args.command == "issue-assistant":
        # The assistant cannot approve drafts or use raw account data APIs.
        print(json.dumps(registry.device(args.account, args.label, "read",
                                         args.days, audience="mcp"),
                         ensure_ascii=False))
    elif args.command == "list-devices":
        print(json.dumps({"devices": registry.list_devices(args.account)},
                         ensure_ascii=False))
    elif args.command == "rotate-device":
        # Invalidate the previous secret immediately within one DB transaction.
        print(json.dumps(registry.rotate(args.device, args.days), ensure_ascii=False))
    elif args.command == "revoke-device":
        registry.revoke(args.device)
        print("Device revoked")
    elif args.command == "disable-account":
        registry.disable(args.account)
        print("Account disabled")
    elif args.command in ("serve", "link-oauth", "unlink-oauth"):
        oauth = None
        if args.oauth_config:
            try:
                from .oauth_resource import OAuthResourceGateway
            except ImportError:
                from oauth_resource import OAuthResourceGateway
            oauth = OAuthResourceGateway.from_config(registry, args.oauth_config)
        if args.command == "link-oauth":
            oauth.link(args.account, args.subject)
            print("OAuth issuer/subject linked to the selected account")
        elif args.command == "unlink-oauth":
            oauth.unlink(args.subject)
            print("OAuth subject link revoked")
        else:
            serve(registry, args.bind, args.port, args.cert, args.key,
                  args.allow_remote, oauth=oauth)


if __name__ == "__main__":
    main()
