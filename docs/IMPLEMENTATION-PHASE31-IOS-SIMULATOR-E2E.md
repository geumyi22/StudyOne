# Phase 31 — Real iOS Simulator application-level network E2E

## Why this is new

The previous Phase29 test used Python HTTP clients pretending to be three devices. The old iOS GitHub workflow compiled an iOS Simulator app but never launched it. Phase31 now installs and launches the **real StudyOne SwiftUI Debug application** on a booted iPhone Simulator and drives its **actual StudySyncClient URLSession transport, request encoder, strict receipt verification and inbound parser** against a disposable local SQLite sync server.

This is stronger than a mock and stronger than compilation-only QA. It does **not** establish a public server, complete account sign-in, production OAuth approval, or true three-device live UI synchronization.

## Execution and isolation

1. GitHub macOS runner builds the unsigned Release IPA and unsigned Debug iOS Simulator app.
2. A shell script starts the Python reference server on loopback `127.0.0.1:8765`, with **random synthetic bearer token** and **temporary SQLite file**. Nothing binds to a LAN or the public internet.
3. The script boots an available iOS Simulator, installs `StudyOne.app`, and launches it with explicit `SIMCTL_CHILD_...` opt-in test environment. The test code is compiled **only for Debug Simulator builds**.
4. App reads an empty snapshot, encodes exactly two artificial records (one task, one progress), POSTs with the real Swift `uploadNew` and receipt verifier, and GETs again to confirm both IDs and server cursor.
5. App invokes the real inbound parser with an empty second client record collection and confirms the same two records would be staged without overwrite. Divergent local content with a matching ID must be classified as a conflict, and a fake bearer token must be denied.
6. App writes a result marker to its simulator-only temporary directory. CI reads it. A **real portable Windows .NET SyncClient** (linked from the WPF app, not a Python imitation) reads both iOS records from the same server, validates the inbound merge and writes one new synthetic Windows task (cursor 3). Then the iOS app is cold-restarted and runs `StudySyncClient.snapshot` and `previewIncoming` again to verify the Windows record is visible. An independent server query confirms 3 durable rows.
7. Shell trap terminates the ephemeral server and removes its temporary database and token. No actual student data, app sync settings, UserDefaults study records, token vault, iOS keychain or stored backups are accessed or modified by the harness.

The loopback-only **v1 HTTP** protocol is used for Simulator network transport because production HTTPS/issuer provisioning is deliberately outside this task. This test is not a claim of external TLS, real login, Android↔iOS cross-device identity, or signed App Store release. An existing Android emulator exercises the actual Android client loopback network path separately. Windows is now exercised through its real portable `.NET SyncClient` HTTP transport against the iOS app's same live server, but **a Windows graphical WPF UI E2E still has not been performed**.

## Follow-up

Next genuine integration gate: actual Windows .NET SyncClient with ephemeral HTTPS server, and the Android app's HTTPS account resource flow with verified test certificates (never bypass TLS verification in shipping builds). After those, a single multi-platform same-account E2E environment and explicit per-record conflict choices can be implemented. The external ChatGPT OAuth application registration remains separate.
