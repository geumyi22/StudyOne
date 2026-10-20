#!/usr/bin/env python3
"""Validate cross-platform local progress contract; no personal study data."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
ANDROID = ROOT / "app/src/main/java/com/studyone/app"
IOS = ROOT / "ios/StudyOne"

class ProgressContract(unittest.TestCase):
    def test_status_semantics_identical(self):
        java = (ANDROID / "StudyProgressItem.java").read_text(encoding="utf-8")
        swift = (IOS / "StudyProgress.swift").read_text(encoding="utf-8")
        java_statuses = re.findall(
            r'public static final String\s+(UNKNOWN|TODO|IN_PROGRESS|DONE)\s*=\s*"([^"]+)"', java)
        swift_statuses = re.findall(
            r'case\s+(unknown|todo|inProgress|done)\s*=\s*"([^"]+)"', swift)
        self.assertEqual([x[1] for x in java_statuses], [x[1] for x in swift_statuses])
        self.assertEqual(
            [x[1] for x in java_statuses], ["확인 필요", "미완료", "진행 중", "완료"])

    def test_storage_fields_and_isolation(self):
        java = (ANDROID / "StudyProgressStorage.java").read_text(encoding="utf-8")
        swift = (IOS / "StudyProgress.swift").read_text(encoding="utf-8")
        keys = ("id", "subject", "material", "unit", "status",
                "inExamScope", "revision", "updatedAt")
        for key in keys:
            self.assertIn(f'object.put("{key}"', java)
            self.assertRegex(swift, rf'let {key}:\s*')
        self.assertIn('root.put("schema", 1)', java)
        self.assertIn('StudyProgressEnvelope(schema: 1, items: entries)', swift)
        self.assertIn('"studyone_progress_v1"', java)
        self.assertIn('"studyone_ios_progress_v1"', swift)
        self.assertIn("throw new IllegalStateException", java)
        self.assertIn("loadError =", swift)

    def test_existing_backup_schema_not_changed(self):
        android = (ANDROID / "BackupIo.java").read_text(encoding="utf-8")
        ios = (IOS / "ModelsAndStore.swift").read_text(encoding="utf-8")
        self.assertIn('root.put("schema", 1)', android)
        self.assertIn('schema: 1', ios)

    def test_ui_reachable(self):
        android = (ANDROID / "MainActivity.java").read_text(encoding="utf-8")
        ios = (IOS / "ContentView.swift").read_text(encoding="utf-8")
        self.assertIn('progressStorage = new StudyProgressStorage(this)', android)
        self.assertIn('c.addView(section("교재 · 시험범위 · 진도"))', android)
        self.assertIn('StudyProgressView().tabItem', ios)
        self.assertIn('String[] n={"홈","시간표","급식","플래너","추천","설정"}', android)

if __name__ == "__main__":
    unittest.main()
