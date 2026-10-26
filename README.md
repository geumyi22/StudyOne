# StudyOne

학생의 **시간표 · 급식 · 과제 · 수행평가 · 시험 · 공부 계획**을 한곳에서 관리하는 개인 학습 앱입니다. Android 네이티브(Java)와 iOS 네이티브(SwiftUI)로 개발합니다.

> **공개 최신 릴리즈: Beta v3.0.0-beta.2 (build 30001)** · **통합 `main` 개발 소스: Android 3.1.0-canary.7 / iOS·Windows Canary.6 기반**. 새 소스가 자동으로 공개 릴리즈가 되거나 기존 휴대폰에 설치되는 것은 아닙니다. [상세 버전 매트릭스](docs/VERSION-MATRIX.md)
>
> **차기 목표 (미출시): `v4.0.0-beta.1` 단 1회 → `v4.0.0` Stable.** 서명·실기기·4종 복원·운영 HTTPS/OAuth·3플랫폼 검증이 필요하며 [4.0.0 통합 출하 차단 기준](docs/RELEASE-4.0.0-ACCEPTANCE.md)을 충족하기 전에는 릴리스하지 않습니다.

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

- [2026-10-24 GitHub 저장소 현황 및 중복 PR 정리](docs/REPOSITORY-STATUS-2026-10-24.md)
- [버전 매트릭스](docs/VERSION-MATRIX.md) · [CHANGELOG](CHANGELOG.md)
- [현재 릴리즈 노트](docs/RELEASE-3.0.0-BETA.2.md)
- [iOS 빌드 및 알려진 한계](ios/README.md)
- [고정 서명 및 업데이트](docs/SIGNING-AND-UPDATES.md)
- [다음 Stable 개발 로드맵 (계획)](docs/NEXT-STABLE-ROADMAP.md)
- [ChatGPT ↔ StudyOne 동기화 설계 (실사용 미연결)](docs/CHATGPT-SYNC-DESIGN.md) · [MCP 변경 이력·기준 커서 검사](docs/IMPLEMENTATION-PHASE19-CHATGPT-CHANGEFEED-AND-CURSOR.md) · [Android HTTPS 계획 승인함](docs/IMPLEMENTATION-PHASE20-ANDROID-CHATGPT-PLAN-INBOX.md)
- [Stable·Beta·Canary 릴리즈 및 저장소 관리 원칙](docs/RELEASE-POLICY.md)

기존 역사적 v2.x 및 v3 beta.1 릴리즈와 문서는 삭제하지 않습니다.


## 최신 통합 및 남은 과제

- [통합 PR #43](https://github.com/geumyi22/StudyOne/pull/43)으로 Android·iOS·Windows 개발 코드, 복구·서명·채널 정책, ChatGPT MCP 프로토타입과 Android 수동 계획 승인함을 `main`에 병합했습니다. 7개 워크플로의 사전 통합 검증(모바일·데스크톱·서버·복구)이 통과했습니다.
- **ChatGPT가 이 대화에서 앱 기록을 즉시 읽거나 쓰는 것은 아직 불가능합니다.** HTTPS 운영 서버, 로그인/복구, ChatGPT OAuth/MCP 앱 등록, 모바일 안전 자동 동기화가 남아 있습니다.
- Android는 공식 서명 Canary.7의 인플레이스(덮어쓰기) 설치를 에뮬레이터에서 검증했습니다. iOS는 unsigned Canary.6, Windows는 WPF Canary.6 후보이므로 **세 플랫폼 Stable로 게시한 상태가 아닙니다**.
- 새 빌드 설치 전 사용자 본인의 **4종 학습 데이터 통합 백업**을 외부에 저장하세요. 개발 브랜치·CI·GitHub 병합은 사용자의 휴대폰 데이터에 접근하거나 초기화하지 않습니다.

안전한 후속 일정과 Stable 수용 기준은 [로드맵](docs/NEXT-STABLE-ROADMAP.md) 및 [버전·배포 매트릭스](docs/VERSION-MATRIX.md)를 참조하세요.
