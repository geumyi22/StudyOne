#!/usr/bin/env python3
"""StudyOne private SQLite offline backup/recovery REHEARSAL, no live restore.

Only use after STOPPING the application server for a consistent multi-DB image.
Backups are plaintext learning records; encrypt the target filesystem/media.
No cloud uploads or live data replacements occur.
"""
from __future__ import annotations

import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
from datetime import datetime, timezone
from uuid import UUID

FORMAT = 1
MAX_DATABASES = 500


def _uuid(value):
    try:
        return str(UUID(value)) == value
    except (TypeError, ValueError):
        return False


def _digest(path: Path):
    digest = sha256()
    with path.open("rb") as reader:
        for block in iter(lambda: reader.read(128 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _integrity(path: Path):
    if not path.is_file() or path.is_symlink():
        raise ValueError("Database file missing or unsafe symlink")
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=8)
    try:
        verdict = connection.execute("PRAGMA integrity_check").fetchone()
        if verdict is None or verdict[0] != "ok":
            raise ValueError("SQLite integrity check failed")
    finally:
        connection.close()


def _db_copy(source: Path, target: Path):
    if not source.is_file() or source.is_symlink():
        raise ValueError("Invalid source database path")
    _integrity(source)
    src = sqlite3.connect(f"file:{source}?mode=ro", uri=True, timeout=8)
    dst = sqlite3.connect(str(target), timeout=8)
    try:
        src.backup(dst)
        dst.commit()
    finally:
        dst.close()
        src.close()
    os.chmod(target, 0o600)
    _integrity(target)


def _account_ids(registry: Path):
    connection = sqlite3.connect(f"file:{registry}?mode=ro", uri=True, timeout=8)
    try:
        rows = connection.execute("SELECT id FROM accounts ORDER BY id").fetchall()
    finally:
        connection.close()
    ids = [r[0] for r in rows]
    if len(ids) > MAX_DATABASES or any(not _uuid(item) for item in ids):
        raise ValueError("Invalid account ID count or format")
    return ids


def create_backup(registry_path, root_path, destination, *, stopped=False):
    if not stopped:
        raise ValueError("Shut down StudyOne server and pass the stop acknowledgment")
    registry = Path(registry_path).resolve()
    root = Path(root_path).resolve()
    final = Path(destination).resolve()
    if final.exists() or not final.parent.is_dir():
        raise ValueError("Backup destination must not exist; its parent must exist")
    if final.parent.is_symlink():
        raise ValueError("Backup parent must not be a symlink")
    if not root.is_dir() or registry.is_symlink():
        raise ValueError("Source databases are not accessible")
    # Never write to or replace the source databases. Prevent a destination
    # inside the data root, which might otherwise be confusing on recovery.
    if final == root or root in final.parents or final == registry:
        raise ValueError("Cannot place backups inside live application data")
    _integrity(registry)
    accounts = _account_ids(registry)
    entries = {"registry.sqlite3": registry}
    for account in accounts:
        source = root / (account + ".sqlite3")
        if not source.exists():
            raise ValueError("Account database is missing; backup aborted")
        entries["accounts/" + account + ".sqlite3"] = source
    stage = Path(tempfile.mkdtemp(prefix=".studyone-backup-incomplete-", dir=final.parent))
    os.chmod(stage, 0o700)
    try:
        (stage / "accounts").mkdir(mode=0o700)
        manifest = {
            "schema": FORMAT, "kind": "STUDYONE_PRIVATE_OFFLINE_BACKUP",
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "storageEncrypted": False,
            "files": {}
        }
        for relative, source in entries.items():
            target = stage / relative
            _db_copy(source, target)
            manifest["files"][relative] = {
                "sha256": _digest(target),
                "bytes": target.stat().st_size
            }
        path = stage / "manifest.json"
        path.write_text(json.dumps(manifest, sort_keys=True, indent=2), encoding="utf-8")
        os.chmod(path, 0o600)
        verify_backup(stage)
        # Caller chose a previously nonexistent path. Never overwrite backups.
        stage.rename(final)
        return {"databases": len(entries), "target": str(final), "verified": True}
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def verify_backup(folder):
    source = Path(folder).resolve()
    if not source.is_dir() or source.is_symlink():
        raise ValueError("Backup directory missing or symlinked")
    manifest_path = source / "manifest.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise ValueError("Backup manifest missing")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (not isinstance(manifest, dict) or
            manifest.get("schema") != FORMAT or
            manifest.get("kind") != "STUDYONE_PRIVATE_OFFLINE_BACKUP" or
            manifest.get("storageEncrypted") is not False):
        raise ValueError("Unknown backup format")
    files = manifest.get("files")
    if (not isinstance(files, dict) or "registry.sqlite3" not in files or
            not 1 <= len(files) <= MAX_DATABASES + 1):
        raise ValueError("Invalid backup file index")
    for relative, metadata in files.items():
        if (relative != "registry.sqlite3" and
                not (relative.startswith("accounts/") and relative.endswith(".sqlite3") and
                     _uuid(relative[len("accounts/"):-len(".sqlite3")]))):
            raise ValueError("Invalid backup file path")
        if not isinstance(metadata, dict) or set(metadata) != {"sha256", "bytes"}:
            raise ValueError("Invalid database metadata")
        candidate = source / relative
        if (candidate.is_symlink() or not candidate.is_file() or
                candidate.resolve().parent not in (source, source / "accounts")):
            raise ValueError("Unsafe backup path")
        if (type(metadata["bytes"]) is not int or
                candidate.stat().st_size != metadata["bytes"] or
                _digest(candidate) != metadata["sha256"]):
            raise ValueError("Backup checksum mismatch")
        _integrity(candidate)
    registry_ids = _account_ids(source / "registry.sqlite3")
    listed = {"accounts/" + item + ".sqlite3" for item in registry_ids}
    if listed != set(files) - {"registry.sqlite3"}:
        raise ValueError("Account inventory mismatch")
    # Isolated restoration rehearsal: copy bytes to a temporary directory,
    # reopen all databases, then dispose of the rehearsal. Never touch live DBs.
    with tempfile.TemporaryDirectory(prefix="studyone-restore-rehearsal-") as work:
        temp_root = Path(work)
        for relative in files:
            staged = temp_root / relative
            staged.parent.mkdir(exist_ok=True, parents=True)
            shutil.copyfile(source / relative, staged)
            _integrity(staged)
    return {"databases": len(files), "integrity": "PASS",
            "restoreRehearsal": "PASS", "encrypted": False}


def main():
    p = argparse.ArgumentParser(description="StudyOne offline backup and restore rehearsal")
    sub = p.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create")
    create.add_argument("--registry", required=True)
    create.add_argument("--data-root", required=True)
    create.add_argument("--destination", required=True)
    create.add_argument("--ack-server-stopped", action="store_true")
    verify = sub.add_parser("verify")
    verify.add_argument("--folder", required=True)
    args = p.parse_args()
    if args.command == "create":
        result = create_backup(args.registry, args.data_root, args.destination,
                               stopped=args.ack_server_stopped)
    else:
        result = verify_backup(args.folder)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
