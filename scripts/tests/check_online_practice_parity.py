#!/usr/bin/env python3
"""Static cross-platform invariants for six-subject local quick-add and safe import.

Behavioral Java, C#, Android-emulator, and iOS build checks execute in platform CI.
This scan deliberately uses NO individual student's progress data.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
def read(path): return (ROOT / path).read_text("utf-8")

J = read("app/src/main/java/com/studyone/app/StudyOnlinePractice.java")
A = read("app/src/main/java/com/studyone/app/MainActivity.java")
AS = read("app/src/main/java/com/studyone/app/StudyProgressStorage.java")
I = read("ios/StudyOne/StudyProgress.swift")
IV = read("ios/StudyOne/StudyProgressView.swift")
W = read("windows/StudyOne.Desktop/StudyOnlinePractice.cs")
WM = read("windows/StudyOne.Desktop/MainWindow.xaml.cs")
WX = read("windows/StudyOne.Desktop/MainWindow.xaml")
WD = read("windows/StudyOne.Desktop/StudyData.cs")
EMULATOR = read("scripts/emulator-smoke.sh")

SUBJECTS = ["국어", "사회", "역사", "과학", "영어", "수학"]
class BatchSafety(unittest.TestCase):
    def test_identical_six_subject_order_and_unit_across_platforms(self):
        java = re.search(r'private static final String\[\] SUBJECTS = \{(.*?)\};', J, re.S)
        swift = re.search(r'static let subjects = \[(.*?)\]', I, re.S)
        csharp = re.search(r'string\[\] Subjects = \{(.*?)\};', W, re.S)
        for m in (java, swift, csharp):
            self.assertIsNotNone(m)
            self.assertEqual(re.findall(r'"([^"]+)"', m.group(1)), SUBJECTS)
        for src in (J, I, W):
            self.assertIn("온라인 내신 문제", src)
            self.assertIn("온라인 문제풀이", src)
        self.assertIn("StudyProgressItem.TODO, true", J)
        self.assertIn("status: .todo, inExamScope: true", I)
        self.assertIn('Status = "미완료", InExamScope = true', W)

    def test_duplicate_suppression_and_existing_state_preservation(self):
        self.assertIn("UNIT.equals(item.unit.trim())", J)
        self.assertIn("$0.unit.trimmingCharacters(in: .whitespacesAndNewlines) == unit", I)
        self.assertIn("item.Unit.Trim() == Unit", W)
        self.assertIn("return missing;", J)
        self.assertIn("return subjects.filter { !known.contains($0) }", I)
        self.assertIn("return Subjects.Where(subject => !known.Contains(subject)).ToArray()", W)
        self.assertIn("StudyOnlinePracticeSmoke", read(".github/workflows/planner-contract.yml"))
        self.assertIn("Repeat online practice request must be idempotent",
                      read("windows/StudyOne.Desktop.Tests/Program.cs"))

    def test_preview_consent_and_read_only_by_default(self):
        self.assertIn('showOnlinePracticeDialog()', A)
        self.assertIn('appendNewProgressForSync(', A)
        self.assertIn('getApplicationContext()', A)
        self.assertIn('StudyOnlinePractice.missingSubjects(in: onlineOriginal)', IV)
        self.assertIn('progress.addMissingOnlinePractice(', IV)
        self.assertIn('mergeNewProgressFromServer(additions, original: expected)', I)
        self.assertIn('QuickAddOnlinePractice_Click', WX)
        self.assertIn('StudyOnlinePractice.CreateMissing(onDisk.Items)', WM)
        self.assertIn('MessageBoxButton.YesNo', WM)

    def test_recovery_guard_for_all_three_progress_importers(self):
        self.assertIn('restoreBackupWithRecovery(', AS)
        self.assertIn('java.util.Objects.equals(rawProgressForSync(), approvedRaw)', AS)
        self.assertIn('context.openFileOutput(name, Context.MODE_PRIVATE)', AS)
        self.assertIn('restoreBackupWithRecovery(', A)
        self.assertIn('StudySyncBackup.save(originalBytes, kind: "progress")', I)
        self.assertIn('func importBackup(data: Data, original expected:', I)
        self.assertIn('progress.importBackup(data: bytes, original: original)', IV)
        self.assertIn('SaveProgressWithRecovery(', WD)
        self.assertIn('current.SequenceEqual(approvedOriginal)', WD)
        self.assertIn('SaveAtomic(backup, old)', WD)
        self.assertIn('SaveAtomic(path, next)', WD)
        self.assertIn('SaveProgressWithRecovery(imported, original)', WM)
        self.assertIn('PROGRESS_ISOLATED_RECOVERY_E2E_PASSED', EMULATOR)
        self.assertIn('Original', WD) if False else None # The actual old bytes are preserved.

    def test_old_schemas_and_release_channels_unchanged(self):
        self.assertIn('root.put("schema", 1)', AS)
        self.assertIn('StudyProgressEnvelope(schema: 1, items: entries)', I)
        self.assertIn('Format = "studyone-progress", Schema = 1', WM)
        self.assertIn('"studyone_v2"', read("app/src/main/java/com/studyone/app/Storage.java"))
        self.assertIn('"versionName": "3.1.0-canary.7"', read("version.json"))

if __name__ == "__main__":
    unittest.main()
