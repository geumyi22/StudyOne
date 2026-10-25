# Phase 31 — Android app ↔ Windows .NET client 실제 네트워크 교차 E2E

실제 StudyOne Android 에뮬레이터에 디버그 APK를 설치·실행하고, **앱의 Java `StudySyncClient`와 Windows용 .NET `SyncClient` 코드가 동일한 임시 SQLite 서버를 통해 데이터를 주고받는지** 검증합니다.

## 자동 테스트의 실제 데이터 흐름

1. GitHub Actions의 Ubuntu Android emulator job이 **일회용** `127.0.0.1:8765` HTTP v1 서버, 임시 SQLite 파일, 가상 토큰을 준비합니다. `adb reverse`로 에뮬레이터 앱 프로세스에서 로컬 서버만 접근합니다.
2. 실제 Android 앱 프로세스가 `StudySyncClient.snapshot/prepareNewOnly/uploadNew`를 실행해 과학 일정 1건 + 오투 진도 1건을 서버에 기록합니다. 모든 저장 확인은 Phase30의 상세 ACK 검사 수행.
3. GitHub Runner에서 **실제 StudyOne Windows .NET `SyncClient`**가 같은 서버의 일정·진도 2개를 읽어 해석하고, 다른 신규 일정 1건을 직접 POST합니다.
4. Android 앱을 **강제 종료한 뒤 새로 실행**하여 Windows가 보낸 정확한 일정 ID·제목과 Android 기존 기록·진도까지 모두 확인합니다.
5. Android의 `previewInbound`가 가상 같은-ID 다른 내용의 **충돌 1개**를 안전하게 표시하는지 확인합니다. 잘못된 Bearer는 거부하고, 미리보기는 서버 커서를 바꾸지 않습니다.
6. 외부 Python 검증은 실제 클라이언트 실행 결과를 대신하지 않고, 해당 서버의 **최종 저장소가 총 3건, 커서 3**인지 별도로 확인합니다.

## 검증 범위 및 남은 일

이것은 단순한 Python 모의 휴대폰이 아니라 **실제 Android 앱 프로세스 + 실제 Windows .NET 앱 통신 라이브러리**가 로컬 서버에 연결한 E2E입니다. 그러나 앱 화면의 전체 터치 흐름, 실물 갤럭시 휴대폰, 공개 HTTPS v2 계정 서버, 실사용 OAuth 로그인, 자동 배경 동기화는 여전히 검증 또는 구현되지 않았습니다.

iOS Simulator ↔ Windows .NET 교차 E2E는 기존 Phase31 PR #56에서 별도 macOS 러너로 검증했으며, 이 PR은 그 파일을 중복 편집하지 않습니다.

- 합성 과제만 사용하고 계정·학습 원본·비밀번호·키체인·운영 서버/DB에는 접근하지 않습니다.
- 기존 디버그 APK의 로컬 v1 HTTP 테스트만 사용하며 앱 배포 설정·TLS 신뢰 설정·공개 릴리즈는 변경하지 않습니다.
- 사용자 화면이나 알림을 통해 테스트를 실행하지 않고 **디버그 플래그 + 인텐트 추가 인자 + 앱 프로세스 내의 격리된 리스트**로만 수행합니다.
- 앱 소스가 아닌 macOS/Windows 테스트의 단순 문구로 성공을 표시하지 않습니다. 앱 내부 Android 로그와 .NET 도구 로그 및 SQLite 서버 기록 세 곳을 모두 대조합니다.
