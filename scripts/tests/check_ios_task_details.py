#!/usr/bin/env python3
"""Static parity/safety contract; iOS compilation is checked by ios.yml on macOS."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
IOS_MODEL = (ROOT / "ios/StudyOne/StudyTaskDetail.swift").read_text(encoding="utf-8")
IOS_UI = (ROOT / "ios/StudyOne/ContentView.swift").read_text(encoding="utf-8")
ANDROID = (ROOT / "app/src/main/java/com/studyone/app/StudyTaskDetailStorage.java").read_text(encoding="utf-8")
LEGACY = (ROOT / "ios/StudyOne/ModelsAndStore.swift").read_text(encoding="utf-8")


class IOSStudyTaskDetailsContract(unittest.TestCase):
    def test_android_parity_and_limits(self):
        for term in ('"studyone-task-details"', '"format", "schema", "items"',
                     '"taskId", "estimatedMinutes", "steps", "completionCriteria"'):
            self.assertIn(term, IOS_MODEL)
        self.assertIn('"studyone-task-details"', ANDROID)
        self.assertIn('private let maximum = 5000', IOS_MODEL)
        self.assertIn('private let maximumBytes = 2_000_000', IOS_MODEL)
        self.assertIn('(1...600).contains(duration)', IOS_MODEL)
        self.assertIn('steps.count <= 20', IOS_MODEL)
        self.assertIn('$0.count <= 200', IOS_MODEL)
        self.assertIn('criteria.count <= 500', IOS_MODEL)
        self.assertIn('try fields.encodeNil(forKey: .estimatedMinutes)', IOS_MODEL)

    def test_existing_task_sync_and_progress_schemas_unchanged(self):
        self.assertIn('struct StudyTask: Codable, Identifiable, Equatable', LEGACY)
        self.assertIn('var completed: Bool', LEGACY)
        self.assertNotIn('estimatedMinutes', LEGACY)
        self.assertIn('TaskDetails-v1.json', IOS_MODEL)
        self.assertIn('private let fileURL', IOS_MODEL)
        self.assertNotIn('URLSession', IOS_MODEL)

    def test_fail_closed_local_import_and_safe_export(self):
        self.assertIn('loadError = "학습 상세 원본을 읽을 수 없습니다.', IOS_MODEL)
        self.assertIn('guard original == expectedOriginal', IOS_MODEL)
        self.assertIn('BeforeTaskDetailsImport', IOS_MODEL)
        self.assertIn('try (original ?? Data()).write(to: backupURL, options: .atomic)', IOS_MODEL)
        self.assertIn('try persist(verified)', IOS_MODEL)
        self.assertIn('FileProtectionType.completeUntilFirstUserAuthentication', IOS_MODEL)
        self.assertIn('try Data(contentsOf: fileURL) == bytes', IOS_MODEL)

    def test_user_interface_and_consent(self):
        self.assertIn('StudyTaskDetailEditor(task: item', IOS_UI)
        self.assertIn('detailStore.detail(for: item.id)', IOS_UI)
        self.assertIn('detailStore.save(taskId: item.id', IOS_UI)
        self.assertIn('detailStore.exportBackup()', IOS_UI)
        self.assertIn('StudyTaskDetailStore.parseBackup(data)', IOS_UI)
        self.assertIn('isPresented: $confirmDetailImport', IOS_UI)
        self.assertIn('detailStore.importBackup(data, expectedOriginal: detailOriginal)', IOS_UI)
        self.assertIn('extra.displayText.split(separator:', IOS_UI)
        self.assertIn('다른 기기에는 전용 JSON을 직접 가져와야 합니다.', IOS_UI)


if __name__ == "__main__":
    unittest.main()
