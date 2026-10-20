# StudyOne 개발 2단계 — 교재·시험범위·진도 관리

> Canary 개발 브랜치 전용. Stable/Beta 출시가 아닙니다.
> 기존 1단계 학습 분류 개발 브랜치를 기반으로 작성했습니다.

## 구현된 기능

- Android 기존 학습 플래너 내 독립된 교재·시험범위·진도 목록, 등록·수정·삭제·진행 상태 변경 UI
- iOS 별도 교재·진도 탭, 상태 필터, 등록·수정·삭제·진행 상태 변경 UI
- 과목, 교재, 소단원 또는 모의고사/학습지/전체 오답 등의 자유 입력 항목, 시험범위 포함 여부
- 상태는 확인 필요 / 미완료 / 진행 중 / 완료 (확인 필요와 미완료는 엄격히 구별)
- 시험범위 제외 항목은 진도 완료율 분모에서 제외
- UUID 안정적 ID, 수정 revision, updatedAt(ms), 오래된 화면에서 수정 시 충돌 차단
- 손상된 로컬 저장 내용 발견 시 원본 데이터를 덮어쓰지 않고 저장 차단
- 기존 일별 숙제, 수행평가, 시험, 준비물 및 JSON 백업 schema 1 형식 변경 없음

## 로컬 저장 구조

| 항목 | Android | iOS |
| --- | --- | --- |
| 진도 저장소 | SharedPreferences studyone_progress_v1 | UserDefaults studyone_ios_progress_v1 |
| 직렬화 형식 | JSON schema=1, items[] | JSON schema=1, items[] |
| 항목 추적 | UUID, revision, updatedAt | UUID, revision, updatedAt |
| 기존 숙제 저장소 | studyone_v2 유지 | studyone_ios_settings_v1 유지 |

## 출시를 막는 주요 제한

**이 단계의 새로운 진도 기록은 기존 StudyOne JSON schema-1 백업 파일에 아직 포함되지 않습니다.**
기기에서 앱을 삭제하거나 앱 데이터를 지우면 진도가 사라질 수 있습니다. 다음 단계에서 버전 구분과 완전한 내보내기/복원 테스트를 만들기 전까지 일반 사용자용 Stable로 배포하지 않습니다.

Android와 iOS는 **서로 자동 동기화되지 않습니다.** ChatGPT의 할꺼/변경사항 인지해 연동, Windows EXE, UI/UX 전면 개편도 아직 구현하지 않았습니다.

## 검증 범위

- Android Gradle 빌드, lint, API 36 에뮬레이터 (GitHub CI).
- iOS XcodeGen unsigned IPA 컴파일/패키징 (GitHub CI).
- Android/iOS 4가지 상태 및 직렬화 필드 동일성 계약 테스트.
- JDK 모델 스모크(입력 검증, UUID 보존, 변경 revision 증가, 타임스탬프).
- 기존 다섯 분류 및 백업 schema-1 회귀 계약 검사.
- **미실시**: 실기기 E2E, 개인정보가 포함된 실사용 진도 이관, 설치형 Windows, 실제 ChatGPT 통합.

## 다음 필수 개발

1. 기존 schema 1 입력을 보존하고 신규 진도까지 안전하게 내보내는 백업 schema 2 및 Android/iOS 왕복 이관 테스트.
2. 과목별 교재/시험 날짜와 시험범위·진도 체계화, AI 계획과 로컬 진도의 중복 방지.
3. 계정, 보안, 클라우드 변경 이력, 오프라인 충돌 해소, 승인된 ChatGPT 읽기·쓰기 연결.
4. 통일된 UI/UX, Windows 설치형 앱, 실환경 성능 측정 및 Stable 릴리즈 수용 시험.

현재 배포된 v3.0.0-beta.2, 기존 태그/릴리즈 자산, Android 서명 설정과 버전 파일은 변경하지 않습니다.
