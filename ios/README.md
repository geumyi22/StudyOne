# StudyOne iOS — v3.0.0-beta.2 (build 30001)

Android와 별도의 **네이티브 SwiftUI iOS 16+** 포트입니다. 패키지 식별자는 `com.studyone.ios`입니다.

구현: NEIS 학교 검색·시간표·중식, 과제/수행평가/시험 관리, 로컬 공부 우선순위 추천, 개인 OpenAI 키(Keychain) 기반 학습 도우미(요청별 동의), schema-1 JSON 백업/복원.

제한: Android의 홈 위젯·로컬 알림·APK 업데이터는 아직 구현하지 않았습니다. iOS 설치 데이터는 Android와 자동 동기화되지 않습니다. iOS API 실사용/실기기 설치는 CI에서 검증하지 않았습니다. App Store 배포 또는 Apple 서명은 포함하지 않습니다.

## 차기 4.0.0 개발 브랜치 기능 (현재 공개 버전에 없음)

[통합 Draft PR #60](https://github.com/geumyi22/StudyOne/pull/60)에는 기존 **4종 통합 JSON 내보내기**에 더해 사용자 승인형 **일정/진도/학습 상세/오답 동시 가져오기**가 구현 중입니다. 섹션 체크섬 검증, 원본 바이트 복구 기록, 중단 후 앱 재실행 복구와 iOS Simulator 합성 장애 주입 테스트를 포함합니다.

이 코드는 공개 iOS Beta.2 IPA에 반영되지 않았습니다. **최종 CI·실기기·4종 안전 복구 검증 전까지 설치 또는 실제 사용자 데이터 이전을 권장하지 않습니다.** ChatGPT와 자동 동기화되는 기능도 아닙니다. [상세 기록](../docs/IMPLEMENTATION-V4-IOS-FOURSTORE-RESTORE.md).

## macOS 빌드

```sh
brew install xcodegen
bash ios/scripts/build_unsigned_ipa.sh
```

`version.json`에서 버전명을 읽어 `ios/dist/StudyOne-v3.0.0-beta.2-unsigned.ipa`와 SHA-256 파일을 생성합니다. Xcode에서 코드 서명 없이 컴파일되므로, iPhone에는 AltStore/SideStore 등으로 **별도 서명**해서 설치해야 합니다.

공개 릴리즈의 Android APK는 고정 릴리즈 인증서로 서명되지만, 이 IPA는 끝까지 **unsigned**입니다.

GitHub Actions는 테스트된 main 커밋의 Android 서명 프리릴리즈가 생성된 후, **같은 SHA의 태그에만** 검증된 unsigned IPA 및 checksum을 첨부합니다. 서명 APK 배포가 실패하면 iOS 빌드 결과물은 Actions artifact로 남고 GitHub Release에는 첨부하지 않습니다.
