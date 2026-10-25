#!/usr/bin/env python3
"""Static Phase23 integration invariants. Runtime fault tests run in Windows/JVM/Android CI."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
def file(name): return (ROOT / name).read_text(encoding="utf-8")

class FourStoreContract(unittest.TestCase):
    def test_all_three_platforms_use_exact_wire_format(self):
        sources = [
            file("app/src/main/java/com/studyone/app/StudyPortableBundle.java"),
            file("ios/StudyOne/StudyFourStoreBundle.swift"),
            file("windows/StudyOne.Desktop/StudyPortableBundle.cs"),
        ]
        for src in sources:
            self.assertIn("studyone-transfer-bundle", src)
            for key in ("tasks", "progress", "details", "wrongAnswers"):
                self.assertIn('"'+key+'"', src)
            self.assertIn("sha256", src)
        self.assertIn('MAX_BYTES = 8_200_000', sources[0])
        self.assertIn('limit = 8_200_000', sources[1])
        self.assertIn('Limit = 8_200_000', sources[2])

    def test_windows_consent_recovery_journal_and_fault_injection(self):
        cs = file("windows/StudyOne.Desktop/StudyPortableBundle.cs")
        ui = file("windows/StudyOne.Desktop/MainWindow.xaml.cs")
        xaml = file("windows/StudyOne.Desktop/MainWindow.xaml")
        tests = file("windows/StudyOne.Desktop.Tests/Program.cs")
        for token in ("public static IReadOnlyList<string> Pending(", "public static void Recover(",
                      "public static string Apply(", "CreateRecovery(directory, latest)",
                      'Path.Combine(folder, ".pending")',
                      "latest.Same(approved)", "StudyData.SaveAtomic("):
            self.assertIn(token, cs)
        self.assertIn("StudyPortableBundle.Pending(StudyData.DataDirectory)", ui)
        self.assertIn("StudyPortableBundle.Recover(", ui)
        self.assertIn("StudyPortableBundle.Apply(", ui)
        self.assertIn("Path.GetFullPath(path).StartsWith(localRoot", ui)
        for label in ("4종 통합 JSON 내보내기", "4종 통합 JSON 가져오기",
                      "미완료 가져오기 원본 복구"):
            self.assertIn(label, xaml)
        self.assertIn("Synthetic section-2 failure", tests)
        self.assertIn("Explicit interrupted journal recovery restores", tests)

    def test_android_rollback_is_attempted_after_durable_backup(self):
        bundle = file("app/src/main/java/com/studyone/app/StudyPortableBundle.java")
        helper = file("app/src/main/java/com/studyone/app/StudyFourStoreRollback.java")
        smoke = file("scripts/tests/StudyFourStoreRollbackSmoke.java")
        self.assertLess(bundle.index("context.openFileOutput(name"), bundle.index("StudyFourStoreRollback.apply("))
        self.assertIn("StudyFourStoreRollback.apply(forward,rollback", bundle)
        self.assertIn("for (int i = 3; i >= 0; i--)", helper)
        self.assertIn("verifyRestored.run()", helper)
        self.assertIn("for (int failing = 0; failing < 4; failing++)", smoke)

    def test_ios_four_store_import_has_verified_consent_and_recovery(self):
        bundle = file("ios/StudyOne/StudyFourStoreBundle.swift")
        importer = file("ios/StudyOne/StudyFourStoreImport.swift")
        view = file("ios/StudyOne/ContentView.swift")
        app = file("ios/StudyOne/StudyOneApp.swift")
        self.assertIn("static func export(tasks: URL, progress: URL, details: URL, wrong: URL)", bundle)
        self.assertIn("static func inspect(_ data: Data)", bundle)
        self.assertNotIn("static func import", bundle)
        for key in ("tasks", "progress", "details", "wrongAnswers"):
            self.assertIn(f'"{key}"', importer)
        for safeguard in (
            "StudyFourStoreBundle.inspect(data)",
            "StudyFourStoreBundle.inspect(source)",
            "guard try current() == approved.originals",
            "try saveJournal(approved.originals)",
            "try recoverIfNeeded()",
            "try FileManager.default.removeItem(at: pendingURL)",
            "FourStorePendingImport-v1.json",
            "StudyOne-fourstore-recovery",
            "digest(original) == checksum",
        ):
            self.assertIn(safeguard, importer)
        self.assertLess(importer.index("try saveJournal(approved.originals)"),
                        importer.index("try tasks.importBackup(from: incoming"))
        self.assertIn("4종 통합 백업 · 내보내기 및 안전 복원", view)
        self.assertIn("4종 통합 JSON 복원 파일 선택", view)
        self.assertIn("원본 복구 기록을 보관하고 4종 교체", view)
        self.assertIn("StudyFourStoreImport.prepare(", view)
        self.assertIn("StudyFourStoreImport.apply(", view)
        self.assertIn("StudyFourStoreImport.recoverIfNeeded()", app)
        self.assertIn("if let recoveryError", app)
        self.assertLess(app.index("try StudyFourStoreImport.recoverIfNeeded()"),
                        app.index("var body: some Scene"))
        self.assertIn("store.checkedTasksForSync()", view)
        self.assertIn("fourStoreProgress.exportBackup()", view)
        self.assertIn("fourStoreDetails.exportBackup()", view)
        self.assertIn("fourStoreWrong.exportBackup()", view)

if __name__ == "__main__":
    unittest.main()
