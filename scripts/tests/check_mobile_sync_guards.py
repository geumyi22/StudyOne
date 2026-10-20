#!/usr/bin/env python3
"""Audit debug-only local sync opt-in design without reading private records."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class MobileGuardTests(unittest.TestCase):
    def test_release_android_network_policy_unchanged(self):
        release = read("app/src/main/res/xml/network_security_config.xml")
        debug = read("app/src/debug/res/xml/network_security_config.xml")
        self.assertIn('<base-config cleartextTrafficPermitted="false">', release)
        self.assertNotIn('cleartextTrafficPermitted="true"', release)
        self.assertIn('<base-config cleartextTrafficPermitted="false">', debug)
        self.assertEqual(debug.count('cleartextTrafficPermitted="true"'), 1)
        self.assertIn('<domain>127.0.0.1</domain>', debug)
        self.assertNotIn('<domain>0.0.0.0</domain>', debug)

    def test_android_requires_confirmation_and_never_saves_token(self):
        ui = read("app/src/main/java/com/studyone/app/MainActivity.java")
        client = read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        self.assertIn('ApplicationInfo.FLAG_DEBUGGABLE', ui)
        self.assertIn('setPositiveButton("새 기록 전송"', ui)
        self.assertIn('storage.checkedTasksForSync()', ui)
        self.assertIn('progressStorage.list()', ui)
        self.assertIn('prepared.toString().equals(confirmed.toString())', ui)
        self.assertIn('token.setText("")', ui)
        self.assertIn('Proxy.NO_PROXY', client)
        self.assertIn('setInstanceFollowRedirects(false)', client)
        self.assertIn('http://127.0.0.1:8765/', client)
        self.assertNotIn('saveToken', client)
        self.assertNotIn('SharedPreferences', client)
        self.assertIn('SYNC_CREATE_ONLY_GUARDS_PASSED', ui)
        self.assertIn('LOCAL_SYNC_HTTP_ROUNDTRIP_PASSED', ui)
        self.assertIn('adb reverse tcp:8765 tcp:8765',
                      read("scripts/emulator-smoke.sh"))

    def test_ios_guard_and_revalidation(self):
        main = read("ios/StudyOne/ContentView.swift")
        ui = read("ios/StudyOne/SyncExperimentView.swift")
        client = read("ios/StudyOne/StudySyncClient.swift")
        self.assertIn("#if DEBUG", main)
        self.assertIn("#if DEBUG", ui)
        self.assertIn("#if DEBUG", client)
        self.assertIn('SecureField("테스트 Bearer 토큰', ui)
        self.assertIn('tasksStore.checkedTasksForSync()', ui)
        self.assertIn('let progress = StudyProgressStore()', ui)
        self.assertIn('encoder.encode(latest) == encoder.encode(expected)', ui)
        self.assertIn('Button("신규 기록만 서버에 전송", role: .destructive)', ui)
        self.assertIn('token = ""', ui)
        self.assertIn('RefuseRedirect', client)
        self.assertIn('URLSessionConfiguration.ephemeral', client)
        self.assertIn('http://127.0.0.1:8765/', client)
        self.assertNotIn('UserDefaults.standard.set', client)
        self.assertNotIn('Keychain', client)

    def test_mobile_local_only_and_create_only(self):
        java = read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        swift = read("ios/StudyOne/StudySyncClient.swift")
        for code in (java, swift):
            self.assertIn('baseRevision', code)
            self.assertIn('deleted', code)
            self.assertIn('v1/snapshot', code)
            self.assertIn('v1/changes', code)
            self.assertIn('revision', code)
        self.assertIn('setRequestMethod("POST")', java)
        self.assertIn('new URL(BASE + endpoint)', java)
        self.assertIn('status == 409', java)
        self.assertIn('case 409:', swift)

if __name__ == "__main__":
    unittest.main()
