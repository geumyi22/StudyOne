#!/usr/bin/env python3
"""Static Android Canary migration guarantees; dynamic schema tamper smoke runs in emulator."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
MODEL=(ROOT/"app/src/main/java/com/studyone/app/StudyPortableBundle.java").read_text("utf-8")
UI=(ROOT/"app/src/main/java/com/studyone/app/MainActivity.java").read_text("utf-8")
LEGACY=(ROOT/"app/src/main/java/com/studyone/app/BackupIo.java").read_text("utf-8")
STORAGE=(ROOT/"app/src/main/java/com/studyone/app/Storage.java").read_text("utf-8")
SMOKE=(ROOT/"scripts/emulator-smoke.sh").read_text("utf-8")

class SafeMigration(unittest.TestCase):
    def test_four_sections_and_integrity(self):
        for value in ["studyone-transfer-bundle",'"tasks", "progress", "details", "wrongAnswers"',
                      '"sha256"', 'MessageDigest.getInstance("SHA-256")', "8_200_000",
                      "StudyProgressStorage.parseBackup(sample.progress)",
                      "StudyTaskDetailStorage.parseBackup(sample.details)",
                      "StudyWrongAnswers.parseBackup(sample.wrongAnswers)",
                      "BackupIo.parse(sample.tasks)", "public static boolean selfTest()"]:
            self.assertIn(value,MODEL)

    def test_existing_app_secrets_never_exported(self):
        for bad in ['storage.apiKey()', 'getString("api_key"', 'Bearer', 'token.setText']:
            self.assertNotIn(bad, MODEL)
        self.assertIn('BackupIo.exportData(tasks)',MODEL)
        self.assertIn('storage.checkedTasksForSync()',LEGACY)
        self.assertNotIn('for (Models.StudyTask t : storage.tasks())',LEGACY)

    def test_import_has_no_silent_overwrites(self):
        for value in ["if(!original.identical(approvedCurrent))", 'context.openFileOutput(name,Context.MODE_PRIVATE)',
                      "out.getFD().sync()", "progress.restoreBackup(verified.progressRecords)",
                      "details.restoreBackup(verified.detailRecords)",
                      "wrong.restore(verified.wrongRecords)", "BackupIo.restore(tasks,verified.taskRecords)"]:
            self.assertIn(value,MODEL)
        self.assertIn('if(!p.edit().putString("tasks",result.toString()).commit())',STORAGE)
        self.assertIn('if(id <= 0 || !ids.add(id))',LEGACY)

    def test_user_explicit_consent_and_recovery_path(self):
        for value in ["4종 학습 데이터 통합 백업 저장","통합 백업 가져오기 · 기존 기록 교체",
                      "4종 기록을 전부 교체하시겠습니까?",
                      'setPositiveButton("검증한 기록 4종 교체"',
                      "StudyPortableBundle.restoreWithRecovery(this",
                      "가져오기 전 원본 복구 파일 내보내기",
                      "일부 기록이 적용되었을 수 있습니다",
                      "BUNDLE_RECOVERY_EXPORT", "getPackageManagerVersion()"]:
            self.assertIn(value,UI)
        self.assertIn('getIntent().getBooleanExtra("studyone_bundle_self_test",false)',UI)
        self.assertIn('TRANSFER_BUNDLE_VALIDATION_PASSED',SMOKE)

    def test_no_false_account_sync_claim(self):
        self.assertIn('정식 계정 동기화는 아직 준비 중입니다.',UI)
        self.assertIn('if (isDeveloperBuild())',UI)

if __name__ == "__main__":
    unittest.main()
