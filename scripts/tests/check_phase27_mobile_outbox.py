#!/usr/bin/env python3
"""Cross-platform contract for manually approved create-only durable outboxes."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
def read(p): return (ROOT/p).read_text(encoding="utf-8")
class Phase27MobileOutboxContract(unittest.TestCase):
    def test_android_atomic_file_no_token_and_manual_confirmation(self):
        storage=read("app/src/main/java/com/studyone/app/StudySyncOutbox.java")
        ui=read("app/src/main/java/com/studyone/app/MainActivity.java")
        sync=read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        for part in ("AtomicFile", "file.startWrite()", "file.finishWrite(output)",
                     "file.failWrite(output)", "MAX_RECORDS=1000",
                     'getInt("baseRevision")!=0', 'getBoolean("deleted")',
                     'getString("accountId")', 'getString("requestId")',
                     "validateLocal(", "checkSession(", "removeFirst("):
            self.assertIn(part,storage)
        self.assertNotIn('getSharedPreferences(',storage)
        self.assertIn('StudySyncClient.accountSession(secret,endpoint)',ui)
        self.assertIn('StudySyncClient.uploadQueued(secret,endpoint,first)',ui)
        self.assertIn('.setPositiveButton("대기 요청 전송"',ui)
        self.assertIn("ANDROID_OUTBOX_ISOLATED_E2E_PASSED",
                      read("scripts/emulator-smoke.sh"))
        self.assertIn("JSONObject response=call(token,\"changes\",queued,address)",sync)

    def test_ios_strict_replay_uses_device_identity_without_background_upload(self):
        queue=read("ios/StudyOne/StudySyncOutbox.swift")
        client=read("ios/StudyOne/StudySyncClient.swift")
        ui=read("ios/StudyOne/SyncExperimentView.swift")
        for fragment in ('maxBatches = 100', 'maxItems = 1000',
                         '.write(to: url, options: .atomic)',
                         '.protectionKey: FileProtectionType.completeUnlessOpen',
                         'session.accountId == batch.accountId',
                         'session.scope == "read-write"', 'session.audience == "device"',
                         'validateCurrent(', 'acknowledge('):
            self.assertIn(fragment,queue)
        self.assertIn("Batch(requestId: batch.id, mutations: mutations)",client)
        self.assertIn('endpoint == "session"',client)
        self.assertIn("authenticatedSession(",ui)
        self.assertIn("StudySyncOutbox.acknowledge(first.id)",ui)
        self.assertIn("showQueueConfirmation",ui)
        self.assertIn("StudySyncOutbox.validateCurrent(first",ui)

    def test_windows_is_existing_approved_reference(self):
        windows=read("windows/StudyOne.Desktop/StudySyncOutbox.cs")
        self.assertIn("MaxBatches = 100",windows)
        self.assertIn("MaxItems = 1000",windows)
        self.assertIn('session.Scope != "read-write"',windows)
        self.assertIn('batch.Endpoint != endpoint.AbsoluteUri',windows)
        self.assertIn('StudyData.SaveAtomic(FilePath(dataDirectory), bytes)',windows)

if __name__=="__main__":
    unittest.main()
