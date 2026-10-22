#!/usr/bin/env python3
"""Cross-platform daily 5-role plan UI contract, with no user data."""
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[2]
java = (root / "app/src/main/java/com/studyone/app/StudyDailyChecklist.java").read_text(encoding="utf-8")
android = (root / "app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
swift = (root / "ios/StudyOne/ContentView.swift").read_text(encoding="utf-8")
categories = (root / "ios/StudyOne/StudyCategories.swift").read_text(encoding="utf-8")

class DailyReminderChecks(unittest.TestCase):
    def test_android_copy_without_persisting_or_uploading(self):
        self.assertIn("StudyDailyChecklist.format(current,today,details)", android)
        self.assertIn("clipboard.setPrimaryClip", android)
        self.assertIn("StudyCategories.ROLE_TITLES", java)
        self.assertIn("task.dueDate.isBefore(date) && !task.completed", java)
        self.assertIn("기존 분류 일정", java)
        self.assertNotIn("storage.", java)
        self.assertNotIn("http", java.lower())

    def test_android_live_today_group_and_one_tap(self):
        self.assertIn("appendTodayChecklist(c,tasks,LocalDate.now())", android)
        self.assertIn("StudyDailyChecklist.visible(all,today)", android)
        self.assertIn("StudyDailyChecklist.groupIndex(task,today)", android)
        self.assertIn("root.addView(taskCard(task))", android)
        self.assertIn("전날 못 다 · 원래 마감", android)
        self.assertIn("과거·미래 포함 전체 일정 펼치기", android)
        self.assertIn("public static int groupIndex(", java)
        self.assertIn("return 5; // preserve legacy", java)
        self.assertNotIn("saveTasks", java)

    def test_ios_grouped_roles_and_single_tap(self):
        self.assertIn("ForEach(StudyCategories.roles, id:", swift)
        self.assertIn("Section(role.title)", swift)
        self.assertIn("Button { store.toggle(item) }", swift)
        self.assertIn('Toggle("오늘 할꺼 + 밀린 일정만 표시"', swift)
        self.assertIn("UIPasteboard.general.string = reminderText", swift)
        self.assertIn("전날 못 다 · 원래 마감", swift)
        self.assertIn("store.delete(at: $0, from: byRole(role.title))", swift)

    def test_subject_prefix_and_safe_legacy(self):
        self.assertIn("append('[').append(subject)", java)
        self.assertTrue(
            'return "\\(marker) [\\(subject.isEmpty ? "기타" : subject)]' in swift or
            'let header = "\\(marker) [\\(subject.isEmpty ? "기타" : subject)]' in swift
        )
        self.assertIn("StudyCategories.ROLE_TITLES.length", java)
        self.assertIn("StudyCategories.roles.enumerated()", swift)
        self.assertIn("기존 분류 일정", swift)
        self.assertIn("static let legacyTypes", categories)

if __name__ == "__main__":
    unittest.main()
