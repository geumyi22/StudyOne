#!/usr/bin/env python3
"""Static UX guardrails; real mobile compilation happens in platform CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = (ROOT / "app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
IOS = (ROOT / "ios/StudyOne/StudyProgressView.swift").read_text(encoding="utf-8")
JAVA_EXPORT = (ROOT / "app/src/main/java/com/studyone/app/StudyProgressChecklist.java").read_text(encoding="utf-8")
ANDROID_STORE = (ROOT / "app/src/main/java/com/studyone/app/StudyProgressStorage.java").read_text(encoding="utf-8")
IOS_STORE = (ROOT / "ios/StudyOne/StudyProgress.swift").read_text(encoding="utf-8")

class ProgressOneTapContract(unittest.TestCase):
    def test_android_one_tap_completion_and_explicit_four_states(self):
        self.assertIn("completed.setChecked(StudyProgressItem.DONE.equals(item.status))", ANDROID)
        self.assertIn("String next=isChecked?StudyProgressItem.DONE:StudyProgressItem.TODO;", ANDROID)
        self.assertIn(".setItems(StudyProgressItem.STATUSES", ANDROID)
        self.assertNotIn('Button cycle=secondary("상태 변경")', ANDROID)
        self.assertIn("progressStorage.update(item.id,item.revision", ANDROID)

    def test_ios_one_tap_and_state_menu(self):
        self.assertIn('Image(systemName: item.status == .done ? "checkmark.circle.fill" : "circle")', IOS)
        self.assertIn("status: item.status == .done ? .todo : .done", IOS)
        self.assertIn("ForEach(StudyProgressStatus.allCases)", IOS)
        self.assertIn("try progress.update(", IOS)

    def test_copy_is_local_and_subject_prefixed(self):
        self.assertIn('android.content.ClipboardManager', ANDROID)
        self.assertIn('StudyProgressChecklist.format(progressStorage.list())', ANDROID)
        self.assertIn('UIPasteboard.general.string = checklistText', IOS)
        self.assertIn('append(\'[\').append(item.subject)', JAVA_EXPORT)
        self.assertIn('"☑ " : "☐ "', JAVA_EXPORT)
        self.assertIn('let mark = item.status == .done ? "☑" : "☐"', IOS)
        self.assertIn('[\\(item.subject)]', IOS)

    def test_separate_backups_and_revision_guards_kept(self):
        self.assertIn('"studyone_progress_v1"', ANDROID_STORE)
        self.assertIn('"studyone_ios_progress_v1"', IOS_STORE)
        self.assertIn("current.revision != expectedRevision", ANDROID_STORE)
        self.assertIn("items[index].revision == item.revision", IOS_STORE)

if __name__ == "__main__":
    unittest.main()
