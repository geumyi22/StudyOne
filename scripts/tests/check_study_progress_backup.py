#!/usr/bin/env python3
"""Portable StudyOne progress-only backup contract; no private records."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

class ProgressBackupContract(unittest.TestCase):
    def test_format_matches_across_platforms(self):
        android = (ROOT / "app/src/main/java/com/studyone/app/StudyProgressStorage.java").read_text(encoding="utf-8")
        ios = (ROOT / "ios/StudyOne/StudyProgress.swift").read_text(encoding="utf-8")
        for value in ('"studyone-progress"', 'schema'):
            self.assertIn(value, android)
            self.assertIn(value, ios)
        for field in ("id", "subject", "material", "unit",
                      "status", "inExamScope", "revision", "updatedAt"):
            self.assertIn(f'object.put("{field}"', android)
            self.assertIn(f'let {field}:', ios)
        self.assertIn("public static List<StudyProgressItem> parseBackup", android)
        self.assertIn("func importBackup(data: Data, original expected:", ios)
        self.assertIn("Set(ids).count == ids.count", ios)
        self.assertIn("if (!ids.add(item.id))", android)
        self.assertIn("PROGRESS_BACKUP_ROUNDTRIP_PASSED",
                      (ROOT / "scripts/emulator-smoke.sh").read_text(encoding="utf-8"))

    def test_separate_user_confirmed_import(self):
        android = (ROOT / "app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
        ios = (ROOT / "ios/StudyOne/StudyProgressView.swift").read_text(encoding="utf-8")
        self.assertIn('ACTION_CREATE_DOCUMENT', android)
        self.assertIn('PROGRESS_IMPORT', android)
        self.assertIn('진도 교체', android)
        self.assertIn("ShareLink(item: exportURL)", ios)
        self.assertIn("진도 JSON 백업 불러오기", ios)
        self.assertIn("진도 백업으로 기존 데이터를 교체할까요?", ios)
        self.assertIn("pendingImport = bytes", ios)
        self.assertIn("progress.importBackup(data: bytes, original: original)", ios)
        self.assertIn("StudySyncBackup.save(originalBytes, kind:",
                      (ROOT / "ios/StudyOne/StudyProgress.swift").read_text(encoding="utf-8"))

    def test_existing_task_backup_unchanged(self):
        android = (ROOT / "app/src/main/java/com/studyone/app/BackupIo.java").read_text(encoding="utf-8")
        ios = (ROOT / "ios/StudyOne/ModelsAndStore.swift").read_text(encoding="utf-8")
        self.assertIn('root.put("schema", 1)', android)
        self.assertIn('schema: 1', ios)
        self.assertNotIn("studyone-progress", android)
        self.assertNotIn("studyone-progress", ios)

if __name__ == "__main__":
    unittest.main()
