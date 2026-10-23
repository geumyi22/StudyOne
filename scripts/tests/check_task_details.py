#!/usr/bin/env python3
"""Static Android optional task guidance and data-preservation contract."""
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[2]
model = (root / "app/src/main/java/com/studyone/app/StudyTaskDetail.java").read_text(encoding="utf-8")
storage = (root / "app/src/main/java/com/studyone/app/StudyTaskDetailStorage.java").read_text(encoding="utf-8")
activity = (root / "app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
formatter = (root / "app/src/main/java/com/studyone/app/StudyDailyChecklist.java").read_text(encoding="utf-8")
tasks = (root / "app/src/main/java/com/studyone/app/Models.java").read_text(encoding="utf-8")
legacy = (root / "app/src/main/java/com/studyone/app/BackupIo.java").read_text(encoding="utf-8")

class DetailGuards(unittest.TestCase):
    def test_limits_and_immutable_values(self):
        for piece in ("estimatedMinutes < 1", "estimatedMinutes > 600",
                      "steps.size() > 20", "step.trim().length() > 200",
                      "criteria.length() > 500", "Collections.unmodifiableList",
                      "public boolean isEmpty()"):
            self.assertIn(piece, model)

    def test_storage_is_separate_and_backup_is_explicit(self):
        for piece in ('"studyone_task_details_v1"', '"studyone-task-details"',
                      'doc.put("schema", 1)', "2_000_000",
                      "getContent", "context.openFileOutput(",
                      "output.getFD().sync()", "editor.commit()"):
            if piece != "getContent":
                self.assertIn(piece, storage)
        self.assertIn("StudyTaskDetailStorage.parseBackup(", activity)
        self.assertIn("detailStorage.restoreBackup(parsed)", activity)
        self.assertIn(".setPositiveButton(\"상세 정보 교체\"", activity)
        self.assertIn("DETAILS_EXPORT", activity)
        self.assertIn("DETAILS_IMPORT", activity)
        self.assertNotIn("http", storage.lower())
        self.assertNotIn("https", storage.lower())

    def test_ui_and_reminder_copy(self):
        for piece in ("private void detailDialog(", 'Button guidance=secondary("상세")',
                      "detailStorage.save(detail)", "detail.displayText()",
                      "StudyDailyChecklist.format(current,today,details)"):
            self.assertIn(piece, activity)
        self.assertIn("Map<Long, StudyTaskDetail>", formatter)
        self.assertIn("detail.displayText()", formatter)

    def test_original_task_backup_schema_unchanged(self):
        self.assertIn("public static final class StudyTask", tasks)
        self.assertIn('root.put("schema", 1)', legacy)
        self.assertNotIn("StudyTaskDetail", legacy)
        self.assertNotIn("estimatedMinutes", legacy)
        self.assertNotIn("StudyTaskDetailStorage", tasks)

if __name__ == "__main__":
    unittest.main()
