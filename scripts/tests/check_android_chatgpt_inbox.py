#!/usr/bin/env python3
"""Guard production-visible HTTPS-only StudyOne ChatGPT plan approval without secrets."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = (ROOT / "app/src/main/java/com/studyone/app/MainActivity.java").read_text("utf-8")
CLIENT = (ROOT / "app/src/main/java/com/studyone/app/StudySyncClient.java").read_text("utf-8")


class ChatGPTProductionInbox(unittest.TestCase):
    def test_production_settings_has_explicit_opt_in(self):
        self.assertIn('Button chatgptPlans=secondary("ChatGPT 공부 계획 승인함 · 수동 HTTPS 연결")', UI)
        self.assertIn('chatgptPlans.setOnClickListener(v->openVerifiedPlanInbox())', UI)
        self.assertIn('private void openVerifiedPlanInbox() {', UI)
        self.assertIn("이 ChatGPT 대화와", UI)
        self.assertIn("아직 자동으로 연결되지 않습니다.", UI)
        self.assertIn("정식 계정 동기화는 아직 준비 중입니다.", UI)
        # Original write-capable developer transport must remain behind DEBUG.
        self.assertIn('if (isDeveloperBuild()) {', UI)
        self.assertIn('setPositiveButton("실험 도구 열기",(d,w)->syncExperimentDialog())', UI)

    def test_production_inbox_is_read_and_approve_only(self):
        inbox = UI.split("private void openVerifiedPlanInbox() {", 1)[1].split(
            "/**\n     * Development-only manual sync", 1)[0]
        for fragment in (
            'StudySyncClient.checkedAccountAddress(',
            'showStudyPlanInbox(',
            'importNewRemote(true,',
            'token.setText("")',
            'setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD)',
            'AI 학습 계획 승인 대기함 확인',
            '승인 완료된 신규 일정 가져오기',
        ):
            self.assertIn(fragment, inbox)
        for forbidden in (
            'syncExperimentDialog(', 'uploadNew(', 'prepareNewOnly(',
            'SharedPreferences', 'AiSecrets.save(', 'storage.saveApiKey(',
            'http://127.0.0.1:', 'startActivityForResult('
        ):
            self.assertNotIn(forbidden, inbox)

    def test_account_url_requires_verified_https(self):
        method = CLIENT.split("public static String checkedAccountAddress(String address) {", 1)[1].split(
            "private static String apiPrefix", 1)[0]
        self.assertIn("checkedAddress(address)", method)
        self.assertIn('!normalized.startsWith("https://")', method)
        self.assertIn("throw new IllegalArgumentException", method)
        self.assertIn('uri.getUserInfo()', CLIENT)
        self.assertIn('uri.getRawQuery()', CLIENT)
        self.assertIn('uri.getRawFragment()', CLIENT)
        self.assertIn('setInstanceFollowRedirects(false)', CLIENT)
        self.assertIn('Proxy.NO_PROXY', CLIENT)

    def test_no_implicit_plan_commit_or_local_overwrite(self):
        self.assertIn('setPositiveButton("전체 승인"', UI)
        self.assertIn('setNegativeButton("전체 거부"', UI)
        self.assertIn('StudySyncClient.pendingProposals(token,endpoint)', UI)
        self.assertIn('StudySyncClient.decideProposal(token,endpoint', UI)
        self.assertIn("sameInbound(first,second,tasks)", UI)
        self.assertIn("storage.appendNewTasksForSync(", UI)
        self.assertIn("getApplicationContext()", UI)
        self.assertIn('if(!originalTasks.equals(storage.rawTasksForSync())', UI)


if __name__ == "__main__":
    unittest.main()
