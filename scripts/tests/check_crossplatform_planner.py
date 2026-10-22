#!/usr/bin/env python3
"""Planner date-view parity contracts across Android/iOS/Windows without user data."""
from pathlib import Path
import unittest

R=Path(__file__).resolve().parents[2]
android=(R/"app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
swift=(R/"ios/StudyOne/ContentView.swift").read_text(encoding="utf-8")
wpf=(R/"windows/StudyOne.Desktop/MainWindow.xaml").read_text(encoding="utf-8")
cs=(R/"windows/StudyOne.Desktop/MainWindow.xaml.cs").read_text(encoding="utf-8")
selector=(R/"windows/StudyOne.Desktop/StudyTaskViews.cs").read_text(encoding="utf-8")
testcs=(R/"windows/StudyOne.Desktop.Tests/Program.cs").read_text(encoding="utf-8")
project=(R/"ios/project.yml").read_text(encoding="utf-8")
version=(R/"version.json").read_text(encoding="utf-8")

class Parity(unittest.TestCase):
    def test_android_preserves_original_views(self):
        for token in ('"오늘", "내일", "전체", "진도", "오답", "쓰기", "관리"',
                      'StudyPlannerSections.tomorrowHomework(tasks,today)',
                      'StudyPlannerSections.tomorrow(tasks,today)',
                      'StudyPlannerSections.allSorted(tasks)',
                      'appendTodayChecklist(c,tasks,today)'):
            self.assertIn(token,android)
    def test_ios_today_tomorrow_all_and_no_task_clone(self):
        for token in ('private let scheduleModes = ["오늘", "내일", "전체"]',
                      'Picker("일정 날짜", selection: $scheduleMode)',
                      'Calendar.current.date(byAdding: .day, value: 1, to: Date())',
                      'item.dueDate == tomorrow',
                      'Section("내일 숙제 · 미리 하기")',
                      'ForEach(tomorrowHomework) { item in taskRow(item) }',
                      'Button { store.toggle(item) }',
                      'if !byRole(role.title).isEmpty'):
            self.assertIn(token,swift)
        self.assertNotIn('store.add(item)',swift)
    def test_windows_selector_is_reused_and_id_stable(self):
        for token in ('TaskDateFilterCombo','TaskDateFilter_SelectionChanged',
                      'ComboBoxItem Content="오늘 + 미완료 이월"',
                      'ComboBoxItem Content="내일 마감"',
                      'ComboBoxItem Content="전체 일정"'):
            self.assertIn(token,wpf)
        self.assertIn('StudyTaskViews.Select(',cs)
        self.assertIn('taskBackup.Tasks, DateOnly.FromDateTime(DateTime.Today), taskDisplayMode',cs)
        for token in ('public static IReadOnlyList<StudyTask> Select(',
                      'displayMode == 0', 'displayMode == 1', 'displayMode == 2',
                      'task.DueDate == tomorrow && IsHomework(task)',
                      '.ThenBy(task => task.Id)'):
            self.assertIn(token,selector)
        self.assertIn('ReferenceEquals(todayPreview.Single',testcs)
        self.assertIn('Read-only filtering never rewrites homework status or date',testcs)
    def test_aligned_canary_version(self):
        self.assertIn('CURRENT_PROJECT_VERSION: 30106',project)
        self.assertIn('"versionName": "3.1.0-canary.6"',version)

if __name__ == "__main__":
    unittest.main()
