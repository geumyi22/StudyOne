#!/usr/bin/env python3
"""Platform contract smoke checks; actual builds run in platform-specific CI."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
A=(ROOT/"app/src/main/java/com/studyone/app/StudyWrongAnswers.java").read_text(encoding="utf-8")
AI=(ROOT/"app/src/main/java/com/studyone/app/MainActivity.java").read_text(encoding="utf-8")
I=(ROOT/"ios/StudyOne/StudyWrongAnswers.swift").read_text(encoding="utf-8")
IV=(ROOT/"ios/StudyOne/ContentView.swift").read_text(encoding="utf-8")
W=(ROOT/"windows/StudyOne.Desktop/StudyWrongAnswers.cs").read_text(encoding="utf-8")
WV=(ROOT/"windows/StudyOne.Desktop/MainWindow.xaml").read_text(encoding="utf-8")

class Parity(unittest.TestCase):
    def test_portable_keys_and_limits(self):
        for name,text in [("Android",A),("iOS",I),("Windows",W)]:
            with self.subTest(name=name):
                for field in ["studyone-wrong-answers","subject","material","topic",
                              "correction","nextReview","retries","mastered"]:
                    self.assertIn(field,text)
                self.assertIn("2_000_000",text)
                self.assertIn("1000",text)
        self.assertIn("studyone_wrong_answers_v1",A)
        self.assertIn("WrongAnswers-v1.json",I)
        self.assertIn("wrong-answers.json",W)

    def test_no_implicit_account_sync(self):
        for text in [A,I,W]:
            self.assertNotIn("URLSession",text)
            self.assertNotIn("HttpClient",text)
            self.assertNotIn("AiService.ask",text)
            self.assertIn("Before",text) if text!=A else self.assertIn("before-import",text)

    def test_ui_and_confirmation(self):
        self.assertIn("appendWrongAnswerNotebook(c)",AI)
        self.assertIn("StudyWrongAnswers.parseBackup",AI)
        self.assertIn("wrongAnswers.restore(incoming)",AI)
        self.assertIn("StudyWrongAnswersView()",IV)
        self.assertIn("confirmImport = true",I)
        self.assertIn("StudyWrongAnswersData.ImportWithBackup",W)
        self.assertIn("오답 JSON 가져오기",WV)

if __name__=="__main__":
    unittest.main()
