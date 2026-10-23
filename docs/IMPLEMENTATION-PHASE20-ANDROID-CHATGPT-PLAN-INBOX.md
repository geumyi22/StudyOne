# StudyOne 20단계 — Android 서명 앱용 수동 ChatGPT 계획 승인함 (Canary 후보)

**중요:** 이 단계는 Android UI + 기존 개인 서버 프로토콜을 연결하는 코드입니다. 실제 계정 서버 배포, ChatGPT 앱 플러그인/MCP 연결, OAuth, 원격 데이터 업로드는 아직 없습니다. 서명된 Canary.6의 학습 데이터에 손대지 않았습니다.

## 사용자 동작

1. 사용자 본인의 HTTPS StudyOne 서버를 구축하고 쓰기 권한 장치 토큰을 로컬 운영 콘솔에서 발급받은 경우에만 기능을 사용할 수 있습니다. 서버 비밀번호·토큰을 GitHub에 등록하거나 채팅에 붙여넣지 않습니다.
2. 앱의 **설정 → 계정 동기화 · ChatGPT 계획 → ChatGPT 공부 계획 승인함 · 수동 HTTPS 연결**을 선택합니다.
3. HTTPS 서버의 **루트 URL** 및 앱용 장치 토큰을 해당 일회성 화면에만 입력합니다. 토큰은 저장하지 않고 화면을 닫으면 지워집니다. HTTP, URL에 사용자 정보, 경로·쿼리·프래그먼트가 있는 주소는 거부됩니다.
4. **AI 학습 계획 승인 대기함 확인**을 누르면 서버에서 최대 20개 승인 대기 초안을 읽습니다. 초안별 날짜·과목·제목을 확인한 뒤 **전체 승인/전체 거부/취소**를 선택합니다.
5. 승인은 별도의 확인된 사용자 동작입니다. 서버는 기준 `baseCursor`가 바뀌지 않았는지 원자적으로 확인해 충돌한 계획은 409 `STALE_PLAN_CURSOR`로 거부합니다.
6. 승인된 신규 일정은 아직 서버에만 있습니다. Android 앱에서 **승인 완료된 신규 일정 가져오기**를 별도로 눌러, 2회 조회·수동 확인·기존 데이터 백업 뒤 **새 ID만** 휴대폰에 추가합니다. 동일 ID가 이미 있으면 덮어쓰지 않습니다.

## 데이터 보존과 한계

- 기존 `com.studyone.app` 서명 Canary.6은 변경하지 않았습니다. 이 변경은 단일 앱으로 빌드될 향후 Canary용 코드입니다.
- 자동 서버 연결/푸시/로그인은 아직 없습니다. 앱 자체는 외부 서버 없이 그대로 작동하며 기존 일정·진도·오답을 유지합니다.
- read 전용 토큰은 초안을 조회할 수 있지만 **승인하려면 write 권한 장치 토큰**이 필요합니다. 장치/계정 토큰의 서버 측 수동 발급은 아직 개발자 전용입니다.
- `변경사항 인지해`용 MCP 조회는 Phase19의 `studyone_read_changes`에 구현돼 있지만 실제 ChatGPT 연결이 등록되지 않아 이 채팅에서는 사용할 수 없습니다.
- User-facing product claims about automatic ChatGPT linking, OAuth login and real-time multi-device sync remain prohibited.
- 기존 개발자 도구의 업로드 기능은 `FLAG_DEBUGGABLE` 뒤에 남겨두었고 프로덕션 전용 승인함에는 노출하지 않았습니다.
- 민감한 학습정보를 서버로 업로드하는 데는 별도 확정 동의가 필요합니다. 서버 측 합성 데이터 테스트만 사용합니다.

## CI 검증

- Android APK assemble/lint/emulator (GitHub Actions).
- Planner Contract: `scripts/tests/check_android_chatgpt_inbox.py`, 기존 `check_developer_sync_ui.py`, `check_mobile_sync_guards.py`.
- Phase19 서버 DB/계정 격리 및 기준 커서 무결성 테스트.

향후 단계: 운영 가능한 계정 인증, OAuth 기반 ChatGPT 연결 등록, Android 오프라인 충돌 및 자동 동기화, Windows/iOS 동등 기능, 개인정보 보안 감사.
