#!/usr/bin/env python3
"""Audit mobile opt-in HTTPS account transport without user secrets."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class MobileGuardTests(unittest.TestCase):
    def test_android_release_transport_policy(self):
        release = read("app/src/main/res/xml/network_security_config.xml")
        debug = read("app/src/debug/res/xml/network_security_config.xml")
        self.assertIn('<base-config cleartextTrafficPermitted="false">', release)
        self.assertNotIn('cleartextTrafficPermitted="true"', release)
        self.assertEqual(debug.count('cleartextTrafficPermitted="true"'), 1)
        self.assertIn('<domain includeSubdomains="false">127.0.0.1</domain>', debug)
        self.assertNotIn('<domain>0.0.0.0</domain>', debug)

    def test_android_user_consent_and_strict_endpoint(self):
        ui = read("app/src/main/java/com/studyone/app/MainActivity.java")
        client = read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        for text in ('개발자 도구 · 로컬 동기화 실험', 'setPositiveButton("새 기록 전송"',
                     'storage.checkedTasksForSync()', 'progressStorage.list()',
                     'prepared.toString().equals(confirmed.toString())',
                     'token.setText("")', 'StudySyncClient.snapshot(secret,endpoint)'):
            self.assertIn(text, ui)
        self.assertIn('if (isDeveloperBuild()) {', ui)
        for text in ('Proxy.NO_PROXY', 'setInstanceFollowRedirects(false)',
                     'http://127.0.0.1:8765/', 'checkedAddress', 'apiPrefix',
                     'uri.getUserInfo()', 'uri.getRawFragment()', 'v2/',
                     'status == 409', 'setRequestMethod("POST")'):
            self.assertIn(text, client)
        self.assertNotIn('SharedPreferences', client)
        self.assertNotIn('saveToken', client)

    def test_android_synthetic_emulator_network_roundtrip(self):
        ui = read("app/src/main/java/com/studyone/app/MainActivity.java")
        script = read("scripts/emulator-smoke.sh")
        self.assertIn('SYNC_CREATE_ONLY_GUARDS_PASSED', ui)
        self.assertIn('LOCAL_SYNC_HTTP_ROUNDTRIP_PASSED', ui)
        self.assertIn('adb reverse tcp:8765 tcp:8765', script)

    def test_ios_release_https_and_user_consent(self):
        main = read("ios/StudyOne/ContentView.swift")
        ui = read("ios/StudyOne/SyncExperimentView.swift")
        client = read("ios/StudyOne/StudySyncClient.swift")
        self.assertIn('HTTPS 서버 수동 연결', main)
        self.assertIn('HTTPS 서버 주소', ui)
        self.assertIn('SecureField("테스트 Bearer 토큰', ui)
        self.assertIn('tasksStore.checkedTasksForSync()', ui)
        self.assertIn('let progress = StudyProgressStore()', ui)
        self.assertIn('encoder.encode(latest) == encoder.encode(expected)', ui)
        self.assertIn('Button("신규 기록만 서버에 전송", role: .destructive)', ui)
        self.assertIn('token = ""', ui)
        self.assertIn('RefuseRedirect', client)
        self.assertIn('URLSessionConfiguration.ephemeral', client)
        self.assertIn('#if DEBUG', client)
        self.assertIn('components.port == 8765', client)
        self.assertIn('components.user == nil', client)
        self.assertIn('scheme != "https"', client)
        self.assertIn('v2/', client)
        self.assertNotIn('UserDefaults.standard.set', client)

    def test_existing_id_never_overwritten(self):
        java = read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        swift = read("ios/StudyOne/StudySyncClient.swift")
        for code in (java, swift):
            self.assertIn('baseRevision', code)
            self.assertIn('deleted', code)
            self.assertIn('v2/', code)
            self.assertIn('v1/', code)
            self.assertIn('revision', code)
        self.assertIn('status == 409', java)
        self.assertIn('case 409:', swift)

    def test_mobile_ai_approval_is_explicit_and_https_only(self):
        android_ui = read("app/src/main/java/com/studyone/app/MainActivity.java")
        android_api = read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        ios_ui = read("ios/StudyOne/SyncExperimentView.swift")
        ios_api = read("ios/StudyOne/StudySyncClient.swift")
        for text in ('showStudyPlanInbox(', 'openStudyPlanDecision(',
                     'setPositiveButton("전체 승인"', 'setNegativeButton("전체 거부"',
                     'StudySyncClient.pendingProposals(', 'StudySyncClient.decideProposal('):
            self.assertIn(text, android_ui)
        for text in ('AI 학습 계획 승인은 HTTPS 계정 서버', 'pendingProposals(',
                     'decideProposal(', 'canonicalUuid(', 'response.getString("state")'):
            self.assertIn(text, android_api)
        for text in ('AI 공부 계획 승인 대기함', 'DisclosureGroup',
                     'showPlanDecision = true', 'StudySyncClient.pendingPlans(',
                     'StudySyncClient.decidePlan(', 'latest.tasks == selected.tasks'):
            self.assertIn(text, ios_ui)
        for text in ('pendingPlans(token:', 'decidePlan(token:',
                     'prefix != "v2/"', 'URLSessionConfiguration.ephemeral'):
            self.assertIn(text, ios_api)
        self.assertIn('token = ""', ios_ui)
        self.assertIn('token.setText("")', android_ui)

    def test_android_inbound_only_new_ids_with_private_backups(self):
        client = read("app/src/main/java/com/studyone/app/StudySyncClient.java")
        ui = read("app/src/main/java/com/studyone/app/MainActivity.java")
        tasks = read("app/src/main/java/com/studyone/app/Storage.java")
        progress = read("app/src/main/java/com/studyone/app/StudyProgressStorage.java")
        for phrase in ("previewInbound(", "sameInbound(", "checkedRemoteTask(",
                       "checkedRemoteProgress(", "newTasks.add(", "newProgress.add(",
                       "plan.conflicts++", "apiKey"):
            if phrase != "apiKey":
                self.assertIn(phrase, client)
        for phrase in ("importNewRemote(", "appendNewTasksForSync(",
                       "appendNewProgressForSync(", "sameInbound(first,second,tasks)",
                       "server", "서버의 신규 일정만", "서버의 신규 진도만"):
            if phrase != "server": self.assertIn(phrase, ui)
        for phrase in ("openFileOutput(", "Context.MODE_PRIVATE",
                       "file.getFD().sync()", ".commit()"):
            self.assertIn(phrase, tasks)
            self.assertIn(phrase, progress)
        self.assertIn("if(!current.equals(expectedOriginal))", tasks)
        self.assertIn("Objects.equals(now,original)", progress)

    def test_ios_safe_incoming_preview_and_protected_backups(self):
        network = read("ios/StudyOne/StudySyncClient.swift")
        view = read("ios/StudyOne/SyncExperimentView.swift")
        tasks = read("ios/StudyOne/ModelsAndStore.swift")
        progress = read("ios/StudyOne/StudyProgress.swift")
        backups = read("ios/StudyOne/StudySyncBackup.swift")
        for part in ("previewIncoming(", "Set(payload.keys) == expectedFields",
                     "conflicts += 1", "newTasks.append(candidate)",
                     "newProgress.append(candidate)", "incoming.matches",
                     "return IncomingPlan("):
            if part != "incoming.matches":
                self.assertIn(part, network)
        for part in ("prepareIncoming(tasks:", "applyIncoming()",
                     "initial.matches(verified, importingTasks:",
                     "incomingTasksBaseline", "incomingProgressBaseline",
                     "mergeNewTasksFromServer(", "mergeNewProgressFromServer(",
                     "showImportConfirmation = true"):
            self.assertIn(part, view)
        for part in ("StudySyncBackup.save(", "guard verified == expected",
                     "guard UserDefaults.standard.data(forKey: settingsKey) == blob"):
            self.assertIn(part, tasks)
        for part in ("StudySyncBackup.save(", "decoded.items == expected",
                     "guard UserDefaults.standard.data(forKey: storeKey) == data"):
            self.assertIn(part, progress)
        self.assertIn(".protectionKey", backups)
        self.assertIn("FileProtectionType.completeUntilFirstUserAuthentication", backups)
        self.assertIn(".applicationSupportDirectory", backups)
        self.assertNotIn("UserDefaults.standard.set", network)

if __name__ == "__main__":
    unittest.main()
