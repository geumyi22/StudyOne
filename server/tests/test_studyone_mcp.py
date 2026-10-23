"""Read and proposal-only MCP-style tool bridge tests."""
import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

from server.private_sync import Registry, handler_for
from server.studyone_mcp import dispatch, definitions


class McpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="studyone-mcp-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.registry = Registry(root / "auth.sqlite3", root / "data")
        self.account = self.registry.account("one")
        self.other = self.registry.account("other")
        self.assistant = self.registry.device(self.account, "assistant", "read")
        self.editor = self.registry.device(self.account, "windows", "read-write")
        self.remote = self.registry.device(self.other, "other", "read")
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), handler_for(self.registry, tls_enabled=False))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def api(self, token, path, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(self.url + path, data=data, method="POST", headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream"
        })
        try:
            with urlopen(request, timeout=5) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except HTTPError as error:
            return error.code, json.loads(error.read())

    @staticmethod
    def rpc(name, args=None):
        return {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": name, "arguments": args or {}}}

    def test_initialize_and_tool_list_no_direct_mutation(self):
        status, init = self.api(self.assistant["token"], "/mcp",
                                {"jsonrpc": "2.0", "id": 42, "method": "initialize"})
        self.assertEqual(status, 200)
        self.assertEqual(init["result"]["protocolVersion"], "2025-06-18")
        status, response = self.api(self.assistant["token"], "/mcp",
                                    {"jsonrpc": "2.0", "id": 43, "method": "tools/list"})
        names = [t["name"] for t in response["result"]["tools"]]
        self.assertEqual(names, ["studyone_read_snapshot",
                                 "studyone_read_changes",
                                 "studyone_pending_study_plans",
                                 "studyone_propose_study_plan"])
        self.assertFalse(any("delete" in n or "apply" in n or "write_task" in n
                             for n in names))

    def test_mcp_read_only_credentials_create_pending_draft_not_tasks(self):
        args = {
            "requestId": str(uuid4()), "title": "10월 21일 공부 계획",
            "baseCursor": 0,
            "tasks": [
                {"type": "숙제 항목", "subject": "수학",
                 "title": "수학 오답노트 작성", "dueDate": "2026-10-21",
                 "importance": 2}
            ]
        }
        status, result = self.api(self.assistant["token"], "/mcp",
                                  self.rpc("studyone_propose_study_plan", args))
        self.assertEqual(status, 200)
        self.assertEqual(result["result"]["isError"], False)
        proposal = json.loads(result["result"]["content"][0]["text"])
        self.assertEqual(proposal["state"], "pending")
        self.assertEqual(self.registry.store(self.account).snapshot()["records"], [])
        status, plans = self.api(self.assistant["token"], "/mcp",
                                 self.rpc("studyone_pending_study_plans"))
        self.assertEqual(len(json.loads(plans["result"]["content"][0]["text"])["proposals"]), 1)
        status, approved = self.api(
            self.editor["token"], f"/v2/proposals/{proposal['id']}/approve", {})
        self.assertEqual((status, approved["state"]), (200, "approved"))
        status, snapshot = self.api(self.assistant["token"], "/mcp",
                                    self.rpc("studyone_read_snapshot"))
        self.assertEqual(len(json.loads(snapshot["result"]["content"][0]["text"])["records"]), 1)

    def test_mcp_token_scope_and_account_isolation(self):
        noauth, _ = self.api("garbage", "/mcp", self.rpc("studyone_read_snapshot"))
        self.assertEqual(noauth, 401)
        second, result = self.api(self.remote["token"], "/mcp",
                                  self.rpc("studyone_pending_study_plans"))
        self.assertEqual(second, 200)
        self.assertEqual(json.loads(result["result"]["content"][0]["text"])["proposals"], [])

    def test_unknown_tool_reports_error_without_writing(self):
        status, response = self.api(self.assistant["token"], "/mcp",
                                    self.rpc("studyone_direct_delete_everything", {}))
        self.assertEqual(status, 200)
        self.assertTrue(response["result"]["isError"])
        self.assertEqual(self.registry.store(self.account).snapshot()["cursor"], 0)

    def test_bad_notification_only_returns_202_for_initialized(self):
        code, response = self.api(self.assistant["token"], "/mcp",
                                  {"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual((code, response), (202, None))

    def test_unknown_protocol_method_returns_jsonrpc_error(self):
        status, response = self.api(self.assistant["token"], "/mcp",
                                    {"jsonrpc": "2.0", "id": "test", "method": "resources/read"})
        self.assertEqual(status, 200)
        self.assertEqual(response["error"]["code"], -32601)


    @staticmethod
    def sample_task(identifier, title):
        return {"id": identifier, "type": "숙제 항목", "subject": "과학",
                "title": title, "dueDate": "2026-10-23",
                "importance": 2, "completed": False}

    def write_task(self, identifier, title):
        payload = {"requestId": str(uuid4()), "mutations": [{
            "kind": "task", "id": str(identifier), "baseRevision": 0,
            "deleted": False, "payload": self.sample_task(identifier, title)
        }]}
        return self.api(self.editor["token"], "/v2/changes", payload)

    def proposal_args(self, cursor):
        return {"requestId": str(uuid4()), "title": "최신 진도 반영 계획",
                "baseCursor": cursor, "tasks": [{
                    "type": "숙제 항목", "subject": "국어",
                    "title": "오답 재확인", "dueDate": "2026-10-23",
                    "importance": 2
                }]}

    def test_changes_paged_tombstones_and_account_isolation(self):
        self.assertEqual(self.write_task(111111, "1회")[0], 200)
        self.assertEqual(self.write_task(222222, "2회")[0], 200)
        code, first = self.api(self.assistant["token"], "/mcp",
                               self.rpc("studyone_read_changes",
                                        {"after": 0, "limit": 1}))
        self.assertEqual(code, 200)
        part = json.loads(first["result"]["content"][0]["text"])
        self.assertEqual((part["head"], part["nextCursor"], part["hasMore"]), (2, 1, True))
        self.assertEqual(part["changes"][0]["payload"]["title"], "1회")
        code, later = self.api(self.assistant["token"], "/mcp",
                               self.rpc("studyone_read_changes",
                                        {"after": part["nextCursor"], "limit": 1}))
        page = json.loads(later["result"]["content"][0]["text"])
        self.assertEqual((page["nextCursor"], page["hasMore"]), (2, False))
        self.assertEqual(page["changes"][0]["payload"]["title"], "2회")
        code, remote = self.api(self.remote["token"], "/mcp",
                                self.rpc("studyone_read_changes", {"after": 0}))
        self.assertEqual((code, json.loads(remote["result"]["content"][0]["text"])
                          ["changes"]), (200, []))
        delete = {"requestId": str(uuid4()), "mutations": [{
            "kind": "task", "id": "111111", "baseRevision": 1,
            "deleted": True, "payload": None
        }]}
        self.assertEqual(self.api(self.editor["token"], "/v2/changes", delete)[0], 200)
        code, tail = self.api(self.assistant["token"], "/mcp",
                              self.rpc("studyone_read_changes", {"after": 2}))
        change = json.loads(tail["result"]["content"][0]["text"])["changes"][0]
        self.assertTrue(change["deleted"])
        self.assertIsNone(change["payload"])

    def test_change_cursor_strict_and_no_side_effects(self):
        for args in ({}, {"after": True}, {"after": -1}, {"after": 0, "limit": 0},
                     {"after": 0, "limit": 101}, {"after": 0, "extra": 1},
                     {"after": 99}):
            code, response = self.api(self.assistant["token"], "/mcp",
                                      self.rpc("studyone_read_changes", args))
            self.assertEqual(code, 200)
            self.assertTrue(response["result"]["isError"], args)
        self.assertEqual(self.registry.store(self.account).snapshot()["cursor"], 0)

    def test_cursor_bound_draft_and_approval_reject_stale_snapshot(self):
        missing = self.proposal_args(0)
        del missing["baseCursor"]
        code, out = self.api(self.assistant["token"], "/mcp",
                             self.rpc("studyone_propose_study_plan", missing))
        self.assertEqual(code, 200)
        self.assertTrue(out["result"]["isError"])
        self.assertEqual(self.write_task(333333, "먼저 변경")[0], 200)
        code, stale = self.api(self.assistant["token"], "/mcp",
                               self.rpc("studyone_propose_study_plan",
                                        self.proposal_args(0)))
        self.assertEqual((code, stale["result"]["isError"]), (200, True))
        self.assertIn("STALE_PLAN_CURSOR", stale["result"]["content"][0]["text"])
        self.assertEqual(self.registry.store(self.account).snapshot()["cursor"], 1)
        args = self.proposal_args(1)
        code, draft = self.api(self.assistant["token"], "/mcp",
                               self.rpc("studyone_propose_study_plan", args))
        proposal = json.loads(draft["result"]["content"][0]["text"])
        self.assertEqual(proposal["state"], "pending")
        # Repeated requestId is idempotent, not another proposal.
        code, again = self.api(self.assistant["token"], "/mcp",
                               self.rpc("studyone_propose_study_plan", args))
        self.assertEqual(proposal["id"],
                         json.loads(again["result"]["content"][0]["text"])["id"])
        self.assertEqual(self.write_task(444444, "승인 전 수정")[0], 200)
        code, rejected = self.api(self.editor["token"],
                                  f"/v2/proposals/{proposal['id']}/approve", {})
        self.assertEqual((code, rejected["error"]), (409, "STALE_PLAN_CURSOR"))
        self.assertEqual(self.registry.store(self.account).snapshot()["cursor"], 2)
        self.assertEqual(len(self.registry.store(self.account).snapshot()["records"]), 2)
        self.assertEqual(len(self.registry.store(self.account).changes(0, 100)["changes"]), 2)

    def test_cursor_bound_approval_commits_when_unchanged(self):
        code, draft = self.api(self.assistant["token"], "/mcp",
                               self.rpc("studyone_propose_study_plan",
                                        self.proposal_args(0)))
        self.assertEqual(code, 200)
        proposal = json.loads(draft["result"]["content"][0]["text"])
        self.assertEqual(self.registry.store(self.account).snapshot()["cursor"], 0)
        code, result = self.api(self.editor["token"],
                                f"/v2/proposals/{proposal['id']}/approve", {})
        self.assertEqual((code, result["state"]), (200, "approved"))
        self.assertEqual(len(self.registry.store(self.account).snapshot()["records"]), 1)


if __name__ == "__main__":
    unittest.main()
