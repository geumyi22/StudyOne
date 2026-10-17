# StudyOne v3.0.0-beta.2 — Android + iOS beta

## 주요 변경 사항
- **Android**: 기존 v3 AI 학습 도우미, NEIS, 학습 일정·알림·위젯, 로컬 JSON 백업 및 고정 서명 유지. 버전 코드 30001로 증가.
- **iOS (preview)**: 네이티브 SwiftUI, NEIS 시간표·급식, 과제·시험 관리, 학습 우선순위, 개인 키 기반 OpenAI AI 도우미, 동의형 과제 전송 및 JSON 백업/복원. iOS build 30001.
- GitHub Releases에서 Android 서명 APK와 iOS 미서명 IPA를 **별도 이름과 SHA-256**으로 제공합니다.

## 설치 및 호환성
- Android APK: `com.studyone.app`, 이전 고정 서명 v2.8/v3 beta.1 설치판을 보존하며 덮어쓰기 업데이트하도록 서명 지문·버전 및 에뮬레이터 설치 테스트를 수행합니다. debug 서명 APK는 별도입니다.
- iOS IPA: `com.studyone.ios`, **미서명**. iPhone 설치에는 AltStore/SideStore 등 별도 앱 서명이 필요합니다.
- Android ↔ iOS **자동 동기화 없음**. JSON schema-1 파일로 수동 이전 가능합니다. 가져오기 전에 기존 데이터를 별도로 백업하세요.
- iOS 앱에는 Android의 위젯/로컬 알림/앱 내부 APK 업데이터 기능이 없습니다.

## 보안 · 비용 · 검증 범위
- 개인 OpenAI 키는 앱에 내장하지 않습니다. Android Keystore 및 iOS Keychain에서 기기별 관리하며 JSON 백업으로 내보내지 않습니다.
- 기기는 OpenAI/NEIS HTTPS API에 직접 요청합니다. 서버 프록시·계정 관리·중앙 키 관리 기능은 아직 없습니다.
- OpenAI 사용은 선택적·별도 과금이며 개인 API 키와 요청 내용 동의가 필요합니다.
- CI는 Android Gradle 빌드·lint·서명 인증서·업그레이드 에뮬레이터, iOS Xcode IPA 패키징·SHA를 검증합니다. **아이폰 실기기 설치, 유료 OpenAI 호출, NEIS 실시간 응답과 답변 정확도는 이 CI로 검증되지 않습니다.**
- 본 버전은 **베타 프리릴리즈**로 Stable이 아닙니다.
