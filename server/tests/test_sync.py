"""StudyOne sync reference API tests. Synthetic fixtures only."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import json
import threading
import tempfile
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4
import unittest

from server.studyone_sync import (
    ApiProblem, SyncStore, make_handler, ThreadingHTTPServer,
    validate_record, run_server,
)

TOKEN = "canary-local-test-secret-" + ("z" * 40)
PROGRESS_ID = "e1ed246b-3412-4e6b-81b0-110e3f1769a2"
TASK_ID = "2710825280197801213"


def task(title="내신 숙제", done=False):
    return {"id": int(TASK_ID), "type": "숙제 항목", "subject": "과학",
            "title": title, "dueDate": "2026-10-22",
            "importance": 2, "completed": done}


def progress(status="미완료"):
    return {"id": PROGRESS_ID, "subject": "과학", "material": "오투",
            "unit": "7-2", "status": status, "inExamScope": True,
            "revision": 3, "updatedAt": 1792512983795}


def mutation(kind, identifier, base, payload, deleted=False):
    return {"kind": kind, "id": identifier, "baseRevision": base,
            "deleted": deleted, "payload": payload}


def command(*changes, request_id=None):
    return {"requestId": request_id or str(uuid4()), "mutations": list(changes)}


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="studyone-test-")
        self.addCleanup(self.tmp.cleanup)
        self.store = SyncStore(Path(self.tmp.name) / "records.sqlite3")

    def test_one_snapshot_excludes_tombstones(self):
        first = self.store.apply(command(mutation("task", TASK_ID, 0, task())))
        snap = self.store.snapshot()
        self.assertEqual(snap["cursor"], first["cursor"])
        self.assertEqual(len(snap["records"]), 1)
        self.assertEqual(snap["records"][0]["payload"]["title"], "내신 숙제")
        last = self.store.apply(command(mutation("task", TASK_ID, 1, None, True)))
        self.assertEqual(self.store.snapshot()["records"], [])
        changes = self.store.changes(0, 100)["changes"]
        self.assertEqual([x["deleted"] for x in changes], [False, True])
        self.assertEqual([x["revision"] for x in changes], [1, 2])
        self.assertEqual(last["cursor"], 2)

    def test_idempotency_and_reused_key_rejected(self):
        req = command(mutation("task", TASK_ID, 0, task()))
        first = self.store.apply(req)
        again = self.store.apply(req)
        self.assertEqual(first, again)
        self.assertEqual(len(self.store.changes(0, 20)["changes"]), 1)
        req["mutations"][0]["payload"]["title"] = "changed"
        with self.assertRaises(ApiProblem) as ctx:
            self.store.apply(req)
        self.assertEqual((ctx.exception.status, ctx.exception.code),
                         (409, "IDEMPOTENCY_KEY_REUSED"))

    def test_conflicting_batch_rolls_back_all(self):
        self.store.apply(command(mutation("task", TASK_ID, 0, task())))
        second_id = "7003"
        with self.assertRaises(ApiProblem) as ctx:
            self.store.apply(command(
                mutation("task", second_id, 0,
                         dict(task(), id=int(second_id), title="other")),
                mutation("task", TASK_ID, 0, task(title="stale"))))
        self.assertEqual(ctx.exception.status, 409)
        self.assertEqual(self.store.snapshot()["cursor"], 1)
        self.assertEqual(len(self.store.snapshot()["records"]), 1)

    def test_progress_server_revision_is_authoritative(self):
        self.store.apply(command(mutation("progress", PROGRESS_ID, 0, progress())))
        rec = self.store.snapshot()["records"][0]
        self.assertEqual(rec["revision"], 1)
        self.assertEqual(rec["payload"]["revision"], 1)
        self.assertEqual(rec["payload"]["status"], "미완료")
        original = rec["payload"]["updatedAt"]
        self.store.apply(command(mutation("progress", PROGRESS_ID, 1,
                                         progress("완료"))))
        current = self.store.snapshot()["records"][0]
        self.assertEqual(current["revision"], 2)
        self.assertEqual(current["payload"]["revision"], 2)
        self.assertNotEqual(current["payload"]["status"], rec["payload"]["status"])
        self.assertGreaterEqual(current["payload"]["updatedAt"], original)

    def test_unknown_and_todo_distinct(self):
        self.store.apply(command(mutation("progress", PROGRESS_ID, 0,
                                          progress("확인 필요"))))
        self.assertEqual(self.store.snapshot()["records"][0]["payload"]["status"],
                         "확인 필요")
        self.store.apply(command(mutation("progress", PROGRESS_ID, 1,
                                          progress("미완료"))))
        self.assertEqual(self.store.snapshot()["records"][0]["payload"]["status"],
                         "미완료")

    def test_broken_shapes_rejected_without_writes(self):
        scenarios = [
            command(mutation("task", TASK_ID, 0, dict(task(), apiKey="do not upload"))),
            command(mutation("task", TASK_ID, 0, dict(task(), dueDate="2026-02-30"))),
            command(mutation("task", TASK_ID, 0, dict(task(), completed=1))),
            command(mutation("progress", PROGRESS_ID, 0, dict(progress(), status=["완료"]))),
            command(mutation("progress", PROGRESS_ID, 0, dict(progress(), secret="bad"))),
            command(mutation("progress", PROGRESS_ID, 0, dict(progress(), id="bad"))),
            command(mutation("task", "00001", 0, dict(task(), id=1))),
            command(mutation("task", TASK_ID, 0, task()),
                    mutation("task", TASK_ID, 0, task())),
            command(mutation("task", TASK_ID, 0, None, True))
        ]
        for c in scenarios:
            with self.subTest(c=c), self.assertRaises(ApiProblem):
                self.store.apply(c)
        self.assertEqual(self.store.snapshot()["cursor"], 0)

    def test_cursor_paging_and_bounds(self):
        self.store.apply(command(mutation("task", TASK_ID, 0, task())))
        self.store.apply(command(mutation("task", TASK_ID, 1, task(done=True))))
        page1 = self.store.changes(0, 1)
        self.assertTrue(page1["hasMore"])
        page2 = self.store.changes(page1["nextCursor"], 1)
        self.assertFalse(page2["hasMore"])
        self.assertEqual(page2["changes"][0]["payload"]["completed"], True)
        for cursor, limit in [(-1, 5), (0, 501), (3, 5)]:
            with self.assertRaises(ApiProblem):
                self.store.changes(cursor, limit)

    def test_concurrent_update_only_one_wins(self):
        self.store.apply(command(mutation("task", TASK_ID, 0, task())))
        def attempt(suffix):
            try:
                self.store.apply(command(
                    mutation("task", TASK_ID, 1, task(title=suffix))))
                return "ok"
            except ApiProblem as e:
                return e.code
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(attempt, ["A", "B"]))
        self.assertCountEqual(results, ["ok", "REVISION_CONFLICT"])
        self.assertEqual(self.store.snapshot()["cursor"], 2)

    def test_delete_requires_revision_and_no_implicit_revival(self):
        with self.assertRaises(ApiProblem):
            self.store.apply(command(mutation("task", TASK_ID, 0, None, True)))
        self.store.apply(command(mutation("task", TASK_ID, 0, task())))
        self.store.apply(command(mutation("task", TASK_ID, 1, None, True)))
        with self.assertRaises(ApiProblem):
            self.store.apply(command(mutation("task", TASK_ID, 2, task())))

    def test_reject_non_local_listening_and_short_token(self):
        with self.assertRaises(RuntimeError):
            run_server(self.store.db_path, "0.0.0.0", 0, TOKEN)
        with self.assertRaises(RuntimeError):
            run_server(self.store.db_path, "127.0.0.1", 0, "short")


class HttpTests(CoreTests):
    def setUp(self):
        super().setUp()
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), make_handler(self.store, TOKEN))
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def http(self, method, path, payload=None, token=None, raw=None):
        data = raw if raw is not None else (
            json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None)
        headers = {}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=5) as resp:
                return resp.status, json.load(resp)
        except HTTPError as err:
            return err.code, json.loads(err.read())

    def test_requires_authorization_for_read_and_write(self):
        for method, path, payload in [
            ("GET", "/v1/snapshot", None),
            ("GET", "/v1/changes?after=0", None),
            ("POST", "/v1/changes", command(mutation("task", TASK_ID, 0, task())))
        ]:
            status, body = self.http(method, path, payload)
            self.assertEqual((status, body["error"]), (401, "UNAUTHORIZED"))
            status, _ = self.http(method, path, payload, token="incorrect")
            self.assertEqual(status, 401)

    def test_http_upload_download_and_no_sensitive_headers(self):
        status, response = self.http(
            "POST", "/v1/changes",
            command(mutation("task", TASK_ID, 0, task())), token=TOKEN)
        self.assertEqual(status, 200)
        status, snap = self.http("GET", "/v1/snapshot", token=TOKEN)
        self.assertEqual(status, 200)
        self.assertEqual(snap["cursor"], response["cursor"])
        self.assertEqual(snap["records"][0]["payload"]["id"], int(TASK_ID))

    def test_duplicate_json_keys_blocked(self):
        raw = (b'{"requestId":"' + str(uuid4()).encode() +
               b'","requestId":"duplicate","mutations":[]}')
        status, error = self.http("POST", "/v1/changes", token=TOKEN, raw=raw)
        self.assertEqual((status, error["error"]), (400, "INVALID_JSON"))


if __name__ == "__main__":
    unittest.main()
