# StudyOne

학생의 **시간표 · 급식 · 과제 · 수행평가 · 시험 · 공부 계획**을 한곳에서 관리하는 개인 학습 앱입니다. Android 네이티브(Java)와 iOS 네이티브(SwiftUI)로 개발합니다.

> **공개 최신 릴리즈: Beta v3.0.0-beta.2 (build 30001)** · **통합 `main` 개발 소스: Android 3.1.0-canary.7 / iOS·Windows Canary.6 기반**. 새 소스가 자동으로 공개 릴리즈가 되거나 기존 휴대폰에 설치되는 것은 아닙니다. [상세 버전 매트릭스](docs/VERSION-MATRIX.md)

## 현재 개발 상태 및 저장소 정리 (2026-11-18 · 미출시)

- **공개 최신은 [v3.0.0-beta.2](https://github.com/geumyi22/StudyOne/releases/tag/v3.0.0-beta.2)**이며 `main` HEAD는 [`59a789d`](https://github.com/geumyi22/StudyOne/commit/59a789d947277526d3255f01b30ebda33bd43488)입니다. v4 개발 코드는 아직 `main`에 병합되지 않았고 **v4.0.0-beta.1/Stable은 모두 미발행**입니다.
- **v4 코드:** 누적 Draft [#60](https://github.com/geumyi22/StudyOne/pull/60) → [#61](https://github.com/geumyi22/StudyOne/pull/61) → [#62](https://github.com/geumyi22/StudyOne/pull/62), 개발 HEAD `3a0a3c6`. Android·iOS unsigned·Planner·복구·Sync·Account TLS·릴리스 계약 [CI 7종 성공](https://github.com/geumyi22/StudyOne/commit/3a0a3c6f26c776961c1bde53191893ad80bd9883/checks); 이는 합성 데이터·에뮬레이터·시뮬레이터 검증입니다. 별도 [PR #59](https://github.com/geumyi22/StudyOne/pull/59)도 미병합으로 통합 시 중복·차이 확인이 필요합니다.
- **실제 휴대전화 일부 확인:** Galaxy S24 Ultra에서 기존 정식 설치본 기본 화면 스모크 **5/5** 및 **분리 설치된 Canary.7 수동 QA 19/19**가 사용자 보고로 통과했습니다. 기존 데이터 원본 4종 백업/복원, 정식 서명 업그레이드, iOS·Windows 실기기, 운영 서버·OAuth·3기기 동기화는 **미검증**입니다. 따라서 [P5 이슈 #63](https://github.com/geumyi22/StudyOne/issues/63)은 계속 차단 상태입니다.
- **저장소 정리:** `main` 파일 **216개** 중 문서 **54개**, 실행 워크플로 **12개**를 점검하고 [문서 인덱스](docs/README.md)를 작성했습니다. 서로 다른 버전·검증 이력은 중복으로 보고 삭제하지 않았습니다. 특정 SHA에 고정된 Canary.6/7 일회성 서명 프리뷰 워크플로 2개만 **추가 검토 후보**이며 명시적 파일별 승인 전 삭제하지 않습니다.
- **이번 변경 범위:** [문서 Draft PR #64](https://github.com/geumyi22/StudyOne/pull/64)에만 README/현황/문서 색인 업데이트. **코드·기존 설치본·서명·학습 데이터·태그·릴리스·`main` 변경 없음**. [최신 감사 및 삭제 판단](docs/REPOSITORY-STATUS-2026-11-15.md)에서 근거 확인.

## 2026-11-15 개발 현황 (이전 스냅샷 · 위 11/18 최신 안내 우선)

- 현재 공개 최신은 **`v3.0.0-beta.2`**이며 `main`은 v4 미반영 개발 기준선입니다.
- **v4 Draft 작업:** [통합 #60](https://github.com/geumyi22/StudyOne/pull/60) → [Windows 학습 UX #61](https://github.com/geumyi22/StudyOne/pull/61) → [P0~P4 후속 구현 #62](https://github.com/geumyi22/StudyOne/pull/62). **세 PR 모두 Draft·미병합**입니다.
- #62 최신 개발 HEAD는 `3a0a3c6`입니다. **S24 Ultra(Android 17) 읽기 전용 ADB 검사**가 기본 사용자 영역만 조회하도록 개선되었고, 일부 결과가 출력돼도 권한 오류가 있으면 실패로 처리합니다. 합성 기기 검사 10개와 APK 검사 7개·버전 계약 4개를 통과했고, [같은 HEAD의 GitHub Actions 7개가 모두 성공](https://github.com/geumyi22/StudyOne/commit/3a0a3c6f26c776961c1bde53191893ad80bd9883/checks)했습니다(Android 빌드, iOS unsigned, v4 비배포 계약 등). 사용자 S24 Ultra에서 `pm list packages --user 0 --show-versioncode com.studyone.app` 재조회 결과 `com.studyone.app versionCode:30106`을 오류 없이 확인했습니다. **이는 기본 사용자 영역의 설치 패키지·버전코드 확인일 뿐**, 앱 서명·데이터 보존과 iPhone·Windows 설치 및 운영 계정 연결은 미완료입니다.
- **기존 S24 Ultra 설치본 수동 화면 기본 스모크 5/5 사용자 보고 PASS:** 실행·화면 이동·기록 표시·레이아웃·재실행을 읽기 전용으로 확인했습니다. **v4 후보 앱 전체 P5 실기기·서명·데이터 복구/업데이트·운영 통합 QA는 여전히 미완료**입니다. [추적 이슈 #63](https://github.com/geumyi22/StudyOne/issues/63). 병합·베타/Stable 출시는 별도 승인 전 진행하지 않습니다.

[2026-11-15 GitHub 현황·검증 증거·남은 작업](docs/REPOSITORY-STATUS-2026-11-15.md) · [P5 Galaxy S24 Ultra·iPhone·Windows QA 절차](docs/V4-P5-DEVICE-QA-PLAN.md) · [v4 출하 차단 기준 (Draft)](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/docs/RELEASE-4.0.0-ACCEPTANCE.md)

## 플랫폼 및 실제 지원 기능

Android·iOS 표는 주요 앱 기능을 설명하며 Windows는 [빌드 후보](windows/README.md)로 별도 관리합니다. ChatGPT 실사용 계정 연결 및 세 플랫폼 자동 동기화는 아직 미배포입니다.

| 기능 | Android | iOS |
| --- | --- | --- |
| NEIS 학교 검색, 시간표, 중식 | 지원 | 지원 |
| 과제 · 수행평가 · 시험 관리 | 지원 | 지원 |
| 오프라인 학습 우선순위 추천 | 지원 | 지원 |
| OpenAI Responses API 학습 도움 (개인 키 필요) | 지원 | 지원 |
| JSON 백업/복원 (schema 1) | 지원 | 지원 |
| 로컬 알림 · 홈 위젯 | 지원 | 미구현 |
| 앱 내부 APK 업데이트 | 지원 | 해당 없음 |
| 클라우드 계정 · 실시간 교차기기 동기화 | 미구현 | 미구현 |

AI 도우미는 질문 설명, 복습 퀴즈, 계획 작성을 제공합니다. 과제 정보 전송은 기본 해제이며 요청 직전 미리보기·사용자 승인을 거칩니다. 실제 API 호출에는 사용자가 제공한 OpenAI 키가 필요하며 별도 요금이 발생할 수 있습니다.

Android의 OpenAI 키는 Android Keystore AES-GCM, iOS 키는 Keychain에 저장합니다. **키를 소스 코드·GitHub Actions 로그·APK·IPA·JSON 백업에 포함하지 않습니다.** 현 구현은 개인 키를 사용해 기기에서 OpenAI/NEIS에 HTTPS로 직접 접속하며 중앙 서버 프록시 방식은 아닙니다.

## 다운로드 및 업데이트

- [GitHub Releases](https://github.com/geumyi22/StudyOne/releases): 해당 버전의 Android `StudyOne-v*.apk`, iOS `StudyOne-v*-unsigned.ipa`, SHA-256 검증 파일
- Android: 고정 릴리즈 서명 버전은 동일 인증서·패키지명·더 높은 versionCode로 덮어쓰기 업데이트됩니다. debug 서명 버전에서는 바로 업데이트되지 않을 수 있습니다.
- iOS: **미서명 IPA**는 곧바로 설치할 수 없습니다. 별도 서명이 필요하며 아이폰 실기기 설치/외부 서비스 검증은 미완료입니다.
- Android ↔ iOS 데이터는 자동 동기화되지 않습니다. 수동 JSON 백업/복원은 제공되나 OS별 데이터 파일은 별개입니다.

## 빌드 및 CI

- Android: Gradle 9.3.1 / AGP 9.1.1 / JDK 17 / SDK 37 / minSdk 26. [Android APK CI](https://github.com/geumyi22/StudyOne/actions/workflows/android.yml)
- Android Canary.7: 공식 서명 인증서·APK manifest·SHA-256 검증과 Beta.2→Canary.6→Canary.7 단일 앱 덮어쓰기 에뮬레이터 테스트를 통과했습니다. [검증 기록](https://github.com/geumyi22/StudyOne/actions/runs/39961148498). 기존 Beta.2 릴리즈 워크플로는 자동 공개를 중단하고 수동 승인 전용으로 보존합니다.
- iOS: SwiftUI / iOS 16+ / macOS Xcode + XcodeGen, signed IPA가 아닌 **unsigned IPA**. [iOS CI](https://github.com/geumyi22/StudyOne/actions/workflows/ios.yml)
- 개인 OpenAI 유료 API E2E, 실기기 iOS 기능, 자동 동기화는 CI에서 검증하지 않습니다.

## 문서

- **[문서 색인 및 보존·삭제 검토 기준](docs/README.md)** — 현재 개발, P5 QA, 예전 단계별 기록을 구분해 찾기 위한 안내
- [2026-10-24 GitHub 저장소 현황 및 중복 PR 정리](docs/REPOSITORY-STATUS-2026-10-24.md)
- [버전 매트릭스](docs/VERSION-MATRIX.md) · [CHANGELOG](CHANGELOG.md)
- [현재 릴리즈 노트](docs/RELEASE-3.0.0-BETA.2.md)
- [iOS 빌드 및 알려진 한계](ios/README.md)
- [고정 서명 및 업데이트](docs/SIGNING-AND-UPDATES.md)
- [다음 Stable 개발 로드맵 (계획)](docs/NEXT-STABLE-ROADMAP.md)
- [ChatGPT ↔ StudyOne 동기화 설계 (실사용 미연결)](docs/CHATGPT-SYNC-DESIGN.md) · [MCP 변경 이력·기준 커서 검사](docs/IMPLEMENTATION-PHASE19-CHATGPT-CHANGEFEED-AND-CURSOR.md) · [Android HTTPS 계획 승인함](docs/IMPLEMENTATION-PHASE20-ANDROID-CHATGPT-PLAN-INBOX.md)
- [Stable·Beta·Canary 릴리즈 및 저장소 관리 원칙](docs/RELEASE-POLICY.md)

기존 역사적 v2.x 및 v3 beta.1 릴리즈와 문서는 삭제하지 않습니다.


## `main`에 반영된 마지막 통합 및 남은 과제

- [통합 PR #43](https://github.com/geumyi22/StudyOne/pull/43)으로 Android·iOS·Windows 개발 코드, 복구·서명·채널 정책, ChatGPT MCP 프로토타입과 Android 수동 계획 승인함을 `main`에 병합했습니다. 7개 워크플로의 사전 통합 검증(모바일·데스크톱·서버·복구)이 통과했습니다.
- **ChatGPT가 이 대화에서 앱 기록을 즉시 읽거나 쓰는 것은 아직 불가능합니다.** HTTPS 운영 서버, 로그인/복구, ChatGPT OAuth/MCP 앱 등록, 모바일 안전 자동 동기화가 남아 있습니다.
- Android는 공식 서명 Canary.7의 인플레이스(덮어쓰기) 설치를 에뮬레이터에서 검증했습니다. iOS는 unsigned Canary.6, Windows는 WPF Canary.6 후보이므로 **세 플랫폼 Stable로 게시한 상태가 아닙니다**.
- 새 빌드 설치 전 사용자 본인의 **4종 학습 데이터 통합 백업**을 외부에 저장하세요. 개발 브랜치·CI·GitHub 병합은 사용자의 휴대폰 데이터에 접근하거나 초기화하지 않습니다.

안전한 후속 일정과 Stable 수용 기준은 [로드맵](docs/NEXT-STABLE-ROADMAP.md) 및 [버전·배포 매트릭스](docs/VERSION-MATRIX.md)를 참조하세요.
