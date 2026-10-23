#!/usr/bin/env python3
"""StudyOne Android Canary navigation visual/state regression contract."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
SOURCE=(ROOT/"app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
SMOKE=(ROOT/"scripts/emulator-smoke.sh").read_text(encoding="utf-8")
ICONS=["home","timetable","meals","planner","coach","settings"]


class NavContract(unittest.TestCase):
    def test_six_real_vector_icons(self):
        for name in ICONS:
            with self.subTest(icon=name):
                xml=(ROOT/f"app/src/main/res/drawable/ic_nav_{name}.xml").read_text(encoding="utf-8")
                self.assertIn("<vector",xml)
                self.assertIn("android:viewportWidth=\"24\"",xml)
                self.assertIn("android:pathData=",xml)
                self.assertIn("android:strokeColor=",xml)

    def test_dynamic_selection_not_static_first_tab_blue(self):
        self.assertIn("private void updateNavigationSelection()",SOURCE)
        self.assertIn("boolean active=i==currentTab",SOURCE)
        self.assertIn("navIcons.get(i).setColorFilter(active?BLUE_DARK:MUTED",SOURCE)
        self.assertIn("label.setTextColor(active?BLUE_DARK:MUTED)",SOURCE)
        self.assertIn("cell.setSelected(active)",SOURCE)
        self.assertIn("cell.setActivated(active)",SOURCE)
        self.assertNotIn("b.setTextColor(i==0?BLUE:MUTED)",SOURCE)
        self.assertIn("currentTab=tab;\n        updateNavigationSelection();",SOURCE)
        self.assertIn("cell.setOnClickListener(v->showTab(index))",SOURCE)

    def test_both_phone_and_tablet_paths_share_state(self):
        self.assertIn("private View bottomNav()",SOURCE)
        self.assertIn("navigationItem(i,false)",SOURCE)
        self.assertIn("private View tabletNavigation()",SOURCE)
        self.assertIn("navigationItem(i,true)",SOURCE)
        self.assertIn("navCells.add(cell)",SOURCE)
        self.assertIn("navIcons.add(icon)",SOURCE)
        self.assertIn("navLabels.add(label)",SOURCE)
        self.assertIn("getInt(\"studyone_active_tab\", 0)",SOURCE)
        self.assertIn("out.putInt(\"studyone_active_tab\", currentTab)",SOURCE)

    def test_emulator_clicks_all_six_routes(self):
        self.assertIn("private boolean navigationSelfTest()",SOURCE)
        self.assertIn("navCells.get(i).performClick()",SOURCE)
        self.assertIn("NAVIGATION_STATE_SYNC_PASSED",SOURCE)
        self.assertIn("--ez studyone_nav_self_test true",SMOKE)
        self.assertIn("grep -q 'NAVIGATION_STATE_SYNC_PASSED'",SMOKE)

    def test_sections_and_header_have_hierarchy(self):
        self.assertIn("private View section(String x)",SOURCE)
        self.assertIn('welcome.addView(tv("오늘의 학습",13,BLUE_DARK,true))',SOURCE)
        self.assertIn("nav.setElevation(dp(7))",SOURCE)
        self.assertIn("marker.setBackground(accent)",SOURCE)


if __name__=="__main__":
    unittest.main()
