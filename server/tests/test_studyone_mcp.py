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
                                 "studyone_pending_study_plans",
                                 "studyone_propose_study_plan"])
        self.assertFalse(any("delete" in n or "apply" in n or "write_task" in n
                             for n in names))

    def test_mcp_read_only_credentials_create_pending_draft_not_tasks(self):
        args = {
            "requestId": str(uuid4()), "title": "10월 21일 공부 계획",
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


if __name__ == "__main__":
    unittest.main()
