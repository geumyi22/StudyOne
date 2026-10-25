# StudyOne Phase 29 — Cross-device real-HTTPS acceptance

This phase is about **checking an end-to-end route**, not implementing another backup/outbox screen.

## Exact evidence boundary

- An isolated real TLS listener is created on `127.0.0.1`, with an ephemeral self-signed test certificate verified against an explicitly trusted certificate in the client test.
- Three distinct synthetic device credentials (labeled Android, iOS, Windows) belong to the same synthetic account. A fourth device belongs to a separate synthetic account.
- These are **Python protocol simulation clients**, not code running inside a physical Android phone, iPhone, or Windows EXE. The test exercises the same documented `/v2/session`, `/v2/changes`, `/v2/snapshot` API wire shape as the app clients.
- No production account, paid server, OAuth identity provider, real study record, API token, Google service, or user's actual device is accessed.

## Assertions: one continuous cross-device lifecycle

1. Three distinct device tokens all authenticate over TLS to the same StudyOne account, never to another tenant.
2. Android **simulation** uploads an offline two-record task + progress batch; server returns revisions 1 and a cursor of 2.
3. Windows **simulation** downloads a complete snapshot; confirms both task and progress payloads. Separate account sees nothing.
4. iOS **simulation** downloads two incremental changefeed pages with `limit=1`, obeying cursor boundaries and receiving no duplicates.
5. Windows **simulation** writes an explicit-revision progress edit (revision 2).
6. Offline Android **simulation** attempts a stale revision-1 edit; server refuses `409 REVISION_CONFLICT` and retains the correct revision.
7. After an explicit newer snapshot and conflict resolution, Android writes revision 3 and simulates an HTTP response loss. Retrying the exact body and request ID returns the same receipt, without a new change.
8. Retrying an altered body with the same request ID is rejected. Another account may use the same logical task ID but cannot modify the first account's record.
9. The HTTPS listener is shut down, the registry is reopened from the persisted SQLite files and a new HTTPS listener starts. Snapshots, changefeed cursors and idempotency receipts survive the restart.
10. Read-only device cannot mutate, and revoked device's token stops working.
11. Separate test: MCP assistant suggests a study plan and sees it in **pending** state. No active task is created before an authenticated owner device approves; assistant and the other account are forbidden from approving.

## Re-run locally without fees

The integration test automatically uses a temporary directory and generates only fictional study entries. Requires Python 3.12 and the local `openssl` executable.

```sh
python -m unittest discover -s server/tests -p 'test_phase29_cross_device_tls.py' -v
```

The CI automatically executes this as an explicit acceptance step and again within the existing full backend regression suite. This test does **not** require the optional PyJWT/OAuth provider, cloud hosting, app installation, or OpenAI API usage.

## Still not complete

- **Actual app binaries** on three platforms talking to the same user account and propagating updates end-to-end, including true device-offline behavior and user-approved overwrite UX.
- Safe automatic background sync and conflict resolution for edits/deletions; the current outboxes are manually triggered and create-only.
- Live OAuth/OIDC sign-in/consent and ChatGPT plugin registration with a publicly hosted HTTPS resource.
- Accurate full four-store backup from the **real user device**: records reconstructed from ChatGPT messages do not include original tasks, task details or wrong-answer stores.
- Public app release and install/update/data-preservation QA.

Do not claim that the user's actual homework records have been synchronized or backed up from a device, and do not merge the PR until all CI required gates are green.
