"""A minimal stateless MCP-style JSON-RPC 2.0 bridge for StudyOne Canaries.

This module defines read and proposal-only tools. It does not authorize direct
task mutations by assistants. Real ChatGPT app registration, OAuth, and a
publicly reachable HTTPS MCP endpoint remain separate integration steps.
"""
from __future__ import annotations

try:
    from .studyone_proposals import ProposalStore
    from .studyone_study_schedule import preview as preview_study_schedule
    from .studyone_sync import ApiProblem, json_bytes
except ImportError:
    from studyone_proposals import ProposalStore
    from studyone_study_schedule import preview as preview_study_schedule
    from studyone_sync import ApiProblem, json_bytes

PROTOCOL = "2025-06-18"
VERSION = "0.1.0-canary"


def definitions():
    base = {"type": "object", "additionalProperties": False}
    return [
        {
            "name": "studyone_read_snapshot",
            "description": (
                "Read this authenticated user's current tasks, textbook progress "
                "and server revisions. Read-only; does not change anything."
            ),
            "inputSchema": dict(base, properties={}, required=[])
        },
        {
            "name": "studyone_read_changes",
            "description": (
                "Read this account's task/progress change log after the specified cursor, "
                "including deletions. For '변경사항 인지해', paginate until hasMore=false. "
                "Never claim local unsynced changes have been observed."
            ),
            "inputSchema": {
                "type": "object", "additionalProperties": False,
                "required": ["after"],
                "properties": {
                    "after": {"type": "integer", "minimum": 0},
                    "limit": {"type": "integer", "minimum": 1, "maximum": 100}
                }
            }
        },
        {
            "name": "studyone_pending_study_plans",
            "description": (
                "Read AI-proposed plans awaiting explicit approval in the "
                "StudyOne app. This tool does not approve them."
            ),
            "inputSchema": dict(base, properties={}, required=[])
        },
        {
            "name": "studyone_preview_paper_schedule",
            "description": (
                "READ-ONLY preview of remaining printed exams/worksheets across "
                "calendar days with a daily cap, unit lanes and alternating "
                "worksheet/textbook concept review. Never edits a user's "
                "tasks or progress. Preview does not imply automatic approval."
            ),
            "inputSchema": {
                "type": "object", "additionalProperties": False,
                "required": ["startDate", "endDate", "lanes"],
                "properties": {
                    "startDate": {"type": "string", "format": "date"},
                    "endDate": {"type": "string", "format": "date"},
                    "maxPerDay": {"type": "integer", "minimum": 1, "maximum": 12},
                    "firstReview": {"type": "string", "enum": ["학습지", "교재"]},
                    "skipDates": {"type": "array", "maxItems": 120,
                                  "items": {"type": "string", "format": "date"}},
                    "lanes": {
                        "type": "array", "minItems": 1, "maxItems": 8,
                        "items": {
                            "type": "object", "additionalProperties": False,
                            "required": ["label", "items"],
                            "properties": {
                                "label": {"type": "string", "minLength": 1, "maxLength": 80},
                                "items": {
                                    "type": "array", "minItems": 1, "maxItems": 20,
                                    "items": {
                                        "type": "object", "additionalProperties": False,
                                        "required": ["title", "total"],
                                        "properties": {
                                            "title": {"type": "string", "minLength": 1,
                                                      "maxLength": 80},
                                            "total": {"type": "integer", "minimum": 1,
                                                      "maximum": 60},
                                            "completed": {"type": "integer", "minimum": 0,
                                                          "maximum": 60}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        {
            "name": "studyone_propose_study_plan",
            "description": (
                "Submit a DRAFT study plan based on an exact snapshot cursor. "
                "Reject stale snapshots or modifications before app approval. "
                "Creating a proposal NEVER writes active tasks. The user must "
                "review and approve it in an authenticated StudyOne device."
            ),
            "inputSchema": {
                "type": "object",
                "additionalProperties": False,
                "required": ["requestId", "title", "tasks", "baseCursor"],
                "properties": {
                    "requestId": {"type": "string", "format": "uuid"},
                    "baseCursor": {"type": "integer", "minimum": 0},
                    "title": {"type": "string", "minLength": 1, "maxLength": 100},
                    "tasks": {
                        "type": "array", "minItems": 1, "maxItems": 30,
                        "items": {
                            "type": "object",
                            "additionalProperties": False,
                            "required": [
                                "type", "subject", "title",
                                "dueDate", "importance"
                            ],
                            "properties": {
                                "type": {"type": "string", "maxLength": 100},
                                "subject": {"type": "string", "maxLength": 60},
                                "title": {"type": "string", "maxLength": 500},
                                "dueDate": {"type": "string", "format": "date"},
                                "importance": {"type": "integer", "minimum": 1, "maximum": 3}
                            }
                        }
                    }
                }
            }
        }
    ]


def respond(message_id, result=None, error=None):
    response = {"jsonrpc": "2.0", "id": message_id}
    if error is None:
        response["result"] = result
    else:
        response["error"] = error
    return response


def call(registry, account_id, name, arguments):
    if not isinstance(arguments, dict):
        raise ApiProblem(400, "INVALID_TOOL_ARGUMENTS")
    store = registry.store(account_id)
    if name == "studyone_read_snapshot":
        if arguments:
            raise ApiProblem(400, "INVALID_TOOL_ARGUMENTS")
        return store.snapshot()
    if name == "studyone_read_changes":
        if (set(arguments) not in ({"after"}, {"after", "limit"}) or
                type(arguments.get("after")) is not int or
                type(arguments.get("limit", 100)) is not int or
                not 1 <= arguments.get("limit", 100) <= 100):
            raise ApiProblem(400, "INVALID_CHANGE_QUERY")
        return store.changes(arguments["after"], arguments.get("limit", 100))
    if name == "studyone_pending_study_plans":
        if arguments:
            raise ApiProblem(400, "INVALID_TOOL_ARGUMENTS")
        return ProposalStore(store).pending()
    if name == "studyone_preview_paper_schedule":
        return preview_study_schedule(arguments)
    if name == "studyone_propose_study_plan":
        if set(arguments) != {"requestId", "title", "tasks", "baseCursor"}:
            raise ApiProblem(400, "PLAN_CURSOR_REQUIRED")
        return ProposalStore(store).create(arguments)
    raise ApiProblem(404, "UNKNOWN_TOOL")


def dispatch(registry, account_id, request):
    """Dispatch authorized JSON-RPC request. None denotes an MCP notification."""
    if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
        return respond(None, error={"code": -32600, "message": "INVALID_REQUEST"})
    message_id = request.get("id")
    method = request.get("method")
    if not isinstance(method, str):
        return respond(message_id, error={"code": -32600, "message": "INVALID_REQUEST"})
    if method == "notifications/initialized" and "id" not in request:
        return None
    if "id" not in request or type(message_id) not in (str, int):
        return respond(None, error={"code": -32600, "message": "INVALID_REQUEST"})
    try:
        if method == "initialize":
            return respond(message_id, {
                "protocolVersion": PROTOCOL,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "StudyOne", "version": VERSION}
            })
        if method == "ping":
            return respond(message_id, {})
        if method == "tools/list":
            return respond(message_id, {"tools": definitions()})
        if method == "tools/call":
            args = request.get("params")
            if not isinstance(args, dict) or not isinstance(args.get("name"), str):
                raise ApiProblem(400, "INVALID_TOOL_CALL")
            data = call(registry, account_id, args["name"],
                        args.get("arguments", {}))
            return respond(message_id, {
                "content": [{
                    "type": "text",
                    "text": json_bytes(data).decode("utf-8")
                }],
                "isError": False
            })
        return respond(message_id, error={"code": -32601, "message": "METHOD_NOT_FOUND"})
    except ApiProblem as exc:
        return respond(message_id, {
            "content": [{"type": "text", "text": exc.code}],
            "isError": True
        })
    except (ValueError, TypeError):
        return respond(message_id, {
            "content": [{"type": "text", "text": "INVALID_TOOL_ARGUMENTS"}],
            "isError": True
        })
