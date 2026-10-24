# StudyOne Phase22 — Android · iOS · Windows 진도 안전성 배치 개발

> 개발용 소스/CI에만 반영되는 기능입니다. 실제 휴대폰·iPhone·Windows PC의 학습 기록이나 설치된 앱을 자동 변경하지 않으며, Stable/Beta 배포를 뜻하지 않습니다.

## 1. 플랫폼 간 온라인 내신 문제 등록 동등화

- **국어·사회·역사·과학·영어·수학**에 같은 순서로 `unit="온라인 내신 문제"` 항목을 제안합니다.
- 각 과목에 이미 이 진도명이 있으면 **교재명이 다르거나 진행 상태가 달라도 건너뜁니다**. 기존 ID, revision, updatedAt, 완료/진행 중 상태를 임의 수정하지 않습니다.
- 부족한 항목만 `material="온라인 문제풀이"`, `status="미완료"`, `inExamScope=true`로 추가합니다. 등록 전 미리보기·사용자 동의를 요청합니다.
- **Android** 기존 구현 유지, **iOS** SwiftUI 진도 화면과 **Windows** WPF 진도 탭에 동등한 바로가기를 추가했습니다.
- 일반 `온라인 내신 문제` 진도만 추가하며 족보닷컴 로그인/외부 사이트 문제풀이 자동화 기능은 **아닙니다**.

## 2. 진도만 가져올 때 원본 복구 보호

기존에는 **단독 진도 JSON 가져오기**가 기존 전체 진도를 교체하면서 사전 원본 복구를 일관되게 보장하지 않았습니다. 이번 수정은 다음 안전 절차를 추가합니다.

1. 가져올 `studyone-progress/schema 1` 데이터의 항목 수·ID·상태 등을 검사합니다.
2. 기존 진도 원본을 읽고 가져올 개수를 미리보기로 표시합니다.
3. 사용자가 교체를 명시적으로 승인하면, **미리보기 이후 원본 수정 여부를 검사**합니다. 수정됐으면 저장하지 않습니다.
4. 원본을 **기기 내부에 먼저 별도 복구본**으로 보관합니다. 복구본 생성이 실패하면 가져오기도 하지 않습니다.
5. 데이터 교체를 수행하고 결과와 복구본의 위치를 표시합니다. Windows는 원자적인 임시 파일 이동 후 교체를 사용합니다.
6. 해당 기능은 **진도 JSON 하나**에만 적용됩니다. 기존 4종 통합 백업/복구 및 다른 형식의 가져오기는 동일하게 유지되며, 4종 전체의 ACID 원자성은 보장하지 않습니다.

### 실제 기기 데이터 주의

- Android 앱 내부 `StudyOne-progress-before-import-*.json` / Windows `Recovery/StudyOne-progress-before-replace-*.json` / iOS Application Support `StudyOne/BeforeSync`에 백업됩니다.
- **내부 복구본은 기기/앱 삭제·저장소 초기화까지 버티지 못할 수 있습니다.** 기기 이전/업데이트 전에는 별도 **4종 통합 백업을 외부 폴더에 저장**하세요.
- 잘못된/손상된 현재 진도 데이터를 자동 수정하거나 삭제하지 않습니다. 파일 무결성 및 권한 충돌은 별도 QA가 필요합니다.
- 특정 사용자의 60/64/65개 진도 항목, 수행평가, 학습 이력은 코드/CI에 업로드하거나 자동 삽입하지 않았습니다.
- ChatGPT 직접 연동, 클라우드 계정/자동 동기화, OAuth 기능을 추가한 작업이 아닙니다.

## 3. 배치 CI / E2E / QA

| 검증 | 수행 범위 |
| --- | --- |
| **Planner Contract** | 3플랫폼 온라인 문제 6과목·중복 방지·기존 상태 보존·승인·복구 가드 계약 |
| **Java JVM** | 온라인 문제 신규/중복/상태보존/재실행 멱등성 테스트 |
| **Android APK** | SDK37 빌드, lint, APK 무결성, API36 에뮬레이터 스모크 |
| **Android 격리 E2E** | 실제 Android SharedPreferences와 앱 내부 파일 복구 왕복; 임시 전용 네임스페이스 사용; 실제 사용자 진도에 접근하지 않음 |
| **iOS unsigned IPA** | SwiftUI 새 UI 및 저장소 코드 macOS Xcode 컴파일, 기존 iOS CI 검증 |
| **Windows Canary EXE** | .NET8 WPF 컴파일/배포, 기존 테스트 + 복구본 존재·이전 원본 동일·stale 원본 거부 |
| **기존 서버 테스트** | 변경하지 않은 HTTPS 계정 서버/MCP 테스트는 기존 CI에서 별도 실행 가능. 실사용 ChatGPT OAuth E2E는 아님 |

배치 CI 통과는 실기기 설치·수동 탭/대화상자 클릭 테스트, iPhone Apple 서명, 운영 HTTPS/OAuth, Windows 설치 관리자 배포를 대체하지 않습니다.

## 4. QA 뒤 남은 일

- Android/iOS/Windows 실기기에서 6과목 추가/재시도/취소, 가져오기 전 원본 파일 복구, 강제 종료·저장 공간 부족 시 동작 검증
- 사용자가 앱 내부 백업을 외부로 별도 내보내는 복구 UI·백업 관리 개선
- 일정·상세·오답까지 확장된 4종 일관성/롤백, 클라우드 자동 동기화 충돌 처리
- 계정·HTTPS 운영 서버·OAuth/MCP 실제 연결·정식 서명/Stable 배포

**관련:** [Phase21 Android 초기 구현](IMPLEMENTATION-PHASE21-ANDROID-ONLINE-PRACTICE.md), [버전 매트릭스](VERSION-MATRIX.md), [Next Stable 로드맵](NEXT-STABLE-ROADMAP.md).
