#!/usr/bin/env python3
from pathlib import Path
import unittest

source = (Path(__file__).resolve().parents[2] / "app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")

class DeveloperSyncUI(unittest.TestCase):
    def test_debug_gate_and_warning(self):
        self.assertIn('private boolean isDeveloperBuild()',source)
        self.assertIn('android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE',source)
        self.assertIn('if (isDeveloperBuild()) {',source)
        self.assertIn('개발자 도구 · 로컬 동기화 실험',source)
        self.assertIn('setPositiveButton("실험 도구 열기",(d,w)->syncExperimentDialog())',source)
        self.assertNotIn('Button sync=secondary("계정 서버 수동 연결·신규 항목 전송")',source)

    def test_regular_status_is_unambiguous(self):
        self.assertIn('정식 계정 동기화는 아직 준비 중입니다.',source)
        self.assertIn('현재 학습 기록은 이 기기에 저장되며',source)
        self.assertIn('private void appendTodayChecklist(',source)
        self.assertIn('private void appendWrongAnswerNotebook(',source)

if __name__ == "__main__":
    unittest.main()
