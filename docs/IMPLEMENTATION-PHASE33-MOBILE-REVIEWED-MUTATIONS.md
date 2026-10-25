# Phase 33 — Android / iOS 기존 기록 건별 원격 수정·삭제 승인

이번 단계는 Phase32 Windows의 원격 수정·삭제 승인을 모바일 개발판으로 확장합니다. **자동 동기화가 아닙니다.** 앱의 수동 동기화 실험 화면에서 사용자가 각 기록을 지정·검토·승인해야 서버에 기존 기록의 수정·삭제를 전송합니다.

## Android

- `StudySyncClient.prepareReviewedChange`: 지정 `task`/ `progress` ID, 실제 서버 `revision`과 로컬 내용을 검증하고 단일 변경안을 만들며 서버와 같은 내용은 거부합니다.
- `StudySyncClient.uploadReviewed`: 기존 ID의 `baseRevision >=1`을 포함한 `POST /v2/changes`에 정확히 1개 mutation 전송. Phase30의 모든 ACK 필드·수정 버전 검증 적용.
- `StudySyncClient.saveReviewedRecovery`: 전송 전 사설 앱 폴더 `files/SyncRemoteRecovery/`에 서버 원본 JSON을 Android `AtomicFile`로 저장. 보관 실패 시 전송하지 않습니다.
- 기존 동기화 실험 화면에 종류·ID 입력 + 별도 수정/삭제 버튼 + 원격/로컬 내용과 실제 계정 UUID의 사용자 승인 대화상자.
- 승인 직후 HTTPS 인증 `/v2/session` 재확인, 서버 `cursor`, 동일 mutation, 현재 로컬 기록 검사. 토큰 저장·백그라운드 실행 없음.

## iOS

- `StudySyncClient.prepareReviewedChange`: HTTPS v2 스냅샷 검증, 기존 서버 항목의 실제 revision과 기기 로컬 값 차이를 계산한 한 건의 수정 또는 삭제를 제안.
- `ReviewedMutation`은 원격 삭제 시 JSON `payload:null`을 명시적으로 직렬화하며, 정상 신규 생성용 `Mutation`/오프라인 대기열과 구조를 분리합니다.
- `StudySyncClient.saveReviewedRecovery`: Application Support/StudyOne/SyncRemoteRecovery에 원격 기존 payload를 **원자적 쓰기 + iOS 파일 보호 속성**으로 보관.
- `SyncExperimentView`: 정확한 기존 ID, 일정·진도 Picker, 원격 값·로컬 값 비교, 최종 사용자 확인, 승인 후 서버·기기 값 재검사, 단일 검증 ACK 확인.
- iOS Release 앱에는 기존 개발용 동기화 화면이 공개되지 않으며, 디버그/검증 기능만 확장합니다.

## 안전 조건 및 한계

1. HTTPS 계정 서버만 사용합니다. **개발용 HTTP v1 서버로 기존 ID 변경·삭제를 호출하지 않습니다.**
2. 계정 전용 `GET /v2/session` 쓰기 권한과 실제 `accountId`가 재확인되지 않으면 전송하지 않습니다.
3. **서버 삭제**는 사용자가 ID를 명시하고, 해당 ID가 기기에는 **없는 경우에만** 허용합니다. 로컬 기록은 자동으로 지우거나 서버 tombstone을 자동 수신하지 않습니다.
4. 서버 revision이 다르면 서버 SQLite CAS에서 HTTP 409로 차단되고, 앱은 충돌을 자동 병합하지 않습니다.
5. 네트워크 응답이 불명확하면 재전송 전 서버를 다시 조회하도록 안내합니다. 개별 원격 복구 JSON에는 학습 데이터가 들어 있으며 기기 잠금·백업이 필요합니다.
6. Android/iOS 실제 HTTPS 서버 실기기 사용자 승인 E2E, 실제 OAuth 로그인·ChatGPT MCP 앱 등록, 안정적인 백그라운드 동기화·실기기 데이터 복원은 아직 별개 검증 대상입니다. 이번 기능 구현만으로 완제품 출시라고 주장하지 않습니다.

## QA 게이트

- Android Gradle 빌드·Lint·실제 에뮬레이터 Debug smoke
- iOS unsigned IPA와 Debug Simulator 컴파일
- 3플랫폼 코드 계약 및 서버 TLS CAS 회귀 검증
- 유료 서버/토큰 생성, 현재 사용자 학습 파일 덮어쓰기, 공개 앱 릴리즈 없음
