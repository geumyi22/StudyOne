#!/usr/bin/env python3
"""Static navigation/data-isolation contract for Android planner hub. Runtime verified on emulator."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
UI=(ROOT/"app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
SELECTORS=(ROOT/"app/src/main/java/com/studyone/app/StudyPlannerSections.java").read_text(encoding="utf-8")
CHECKLIST=(ROOT/"app/src/main/java/com/studyone/app/StudyDailyChecklist.java").read_text(encoding="utf-8")
SMOKE=(ROOT/"scripts/emulator-smoke.sh").read_text(encoding="utf-8")

class PlannerHub(unittest.TestCase):
    def test_full_seven_sections(self):
        for text in ('"오늘", "내일", "전체", "진도", "오답", "쓰기", "관리"',
                     'private View plannerPageNavigation()',
                     'private void openPlannerPage(int page)',
                     'appendTodayChecklist(c,tasks,today)',
                     'StudyPlannerSections.tomorrowHomework(tasks,today)',
                     'StudyPlannerSections.tomorrow(tasks,today)',
                     'StudyPlannerSections.allSorted(tasks)',
                     'appendProgressHub(c)',
                     'appendWrongAnswerNotebook(c)',
                     'appendWritingPractice(c)',
                     'appendPlannerManagement(c)',
                     'Button calendar=secondary("월간 달력에서 일정 보기")',
                     'Button detailExport=secondary("학습 상세 정보 JSON 백업")',
                     'Button progressImport=secondary("진도 JSON 백업 가져오기")',
                     'Button copyToday=secondary("오늘 할꺼 · 과목별 리마인더 목록 복사")'):
            with self.subTest(feature=text):self.assertIn(text,UI)

    def test_planner_state_and_no_duplicate_storage(self):
        self.assertIn('out.putInt("studyone_planner_page",plannerPage)',UI)
        self.assertIn('b.getInt("studyone_planner_page",0)',UI)
        self.assertIn('plannerPage=page;\n        showTab(3);',UI)
        self.assertIn('return "숙제 항목".equals(task.type) || "과제".equals(task.type)',SELECTORS)
        self.assertIn('LocalDate tomorrow = today.plusDays(1);',SELECTORS)
        self.assertIn('if (tomorrow.equals(task.dueDate)) out.add(task);',SELECTORS)
        self.assertIn('new ArrayList<>(tasks)',SELECTORS)
        self.assertIn('if (task.dueDate.equals(date) || (task.dueDate.isBefore(date) && !task.completed))',CHECKLIST)
        self.assertNotIn('storage.addTask(',SELECTORS)
        self.assertNotIn('storage.setTaskCompleted(',SELECTORS)
        self.assertNotIn('storage.deleteTask(',SELECTORS)

    def test_empty_daily_sections_not_repeated(self):
        self.assertIn('if(count==0)continue;',UI)
        self.assertIn('오늘 마감이거나 이월된 일정은 없습니다.',UI)
        self.assertIn('내일 숙제 · 미리 하기',UI)
        self.assertIn('마감일을 바꾸지 않고 오늘부터 미리 완료 체크',UI)
        self.assertIn('마감일을 내일로 설정하세요.',UI)

    def test_real_emulator_click_paths(self):
        self.assertIn('private boolean plannerHubSelfTest()',UI)
        self.assertIn('for(int i=0;i<PLANNER_PAGES.length;i++)',UI)
        self.assertIn('openPlannerPage(i);',UI)
        self.assertIn('PLANNER_HUB_SEVEN_PAGES_PASSED',UI)
        self.assertIn('--ez studyone_planner_hub_self_test true',SMOKE)

if __name__ == "__main__":unittest.main()
