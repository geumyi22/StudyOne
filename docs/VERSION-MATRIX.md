# StudyOne 버전·배포 매트릭스

기준일: 2026-10-17. 아래는 **구현/설계 상태**와 **CI 검증 여부**를 구분합니다.

| 항목 | Android | iOS |
| --- | --- | --- |
| 릴리즈 라벨 | v3.0.0-beta.2 | v3.0.0-beta.2 |
| 빌드 번호 | 30001 | 30001 |
| 패키지 ID | com.studyone.app | com.studyone.ios |
| 최소 운영체제 | Android API 26 | iOS 16 |
| 산출물 | 고정 서명 APK | unsigned IPA |
| 빌드 기반 | Java / Gradle | SwiftUI / XcodeGen / Xcode |
| 과제·학교 정보 | 각 기기 로컬 저장 | 각 기기 로컬 저장 |
| 앱 간 자동 동기화 | 없음 | 없음 |
| 백업 교환 | JSON schema 1 | JSON schema 1 |
| 실기기 테스트 | 별도 확인 필요 | 별도 확인 필요 |

- 3.0.0-beta.1 Android 릴리즈는 삭제하지 않으며 SHA/서명 인증서를 유지합니다.
- 3.0.0-beta.2 Android는 기존 `com.studyone.app`·고정 릴리즈 인증서를 재사용하므로 이전 고정 서명판과 덮어쓰기 호환성을 목표로 합니다. 최종 CI에서 확인해야 합니다.
- 3.0.0-beta.2 iOS unsigned IPA는 앱 스토어 배포·자동 설치 또는 Apple 배포 서명을 의미하지 않습니다.
- Stable, App Store, Play Store 배포를 의미하지 않는 Beta 채널입니다.
