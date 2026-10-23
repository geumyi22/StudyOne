#!/usr/bin/env python3
"""Cross-platform contract guard for the five permanent StudyOne study roles."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA_PATH = ROOT / "app/src/main/java/com/studyone/app/StudyCategories.java"
SWIFT_PATH = ROOT / "ios/StudyOne/StudyCategories.swift"

class StudyCategoriesContractTest(unittest.TestCase):
    def test_android_ios_role_parity(self):
        java = JAVA_PATH.read_text(encoding="utf-8")
        swift = SWIFT_PATH.read_text(encoding="utf-8")
        def java_list(name):
            match = re.search(r"\b" + name + r"\s*=\s*\{(.*?)\};", java, re.S)
            self.assertIsNotNone(match, name)
            return re.findall(r'"([^"]*)"', match.group(1))
        titles = java_list("ROLE_TITLES")
        descriptions = java_list("ROLE_DESCRIPTIONS")
        roles = re.findall(r'title:\s*"([^"]+)",\s*description:\s*"([^"]+)"', swift, re.S)
        self.assertEqual(len(titles), 5)
        self.assertEqual(len(set(titles)), 5)
        self.assertEqual(len(descriptions), 5)
        self.assertEqual(titles, [title for title, _ in roles])
        self.assertEqual(descriptions, [description for _, description in roles])
        self.assertEqual(
            titles,
            ["개별 항목 (전날 못 다)", "정리 및 복습 항목", "수행 및 암기 항목",
             "숙제 항목", "기타 항목"]
        )

    def test_legacy_options_preserved(self):
        java = JAVA_PATH.read_text(encoding="utf-8")
        swift = SWIFT_PATH.read_text(encoding="utf-8")
        for old in ("과제", "수행평가", "시험", "준비물"):
            self.assertIn('"' + old + '"', java)
            self.assertIn('"' + old + '"', swift)

    def test_no_backup_format_change(self):
        android_backup = (ROOT / "app/src/main/java/com/studyone/app/BackupIo.java").read_text(encoding="utf-8")
        ios_backup = (ROOT / "ios/StudyOne/ModelsAndStore.swift").read_text(encoding="utf-8")
        self.assertIn('root.put("schema", 1)', android_backup)
        self.assertIn('schema: 1', ios_backup)

if __name__ == "__main__":
    unittest.main()
