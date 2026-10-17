# StudyOne iOS — v3.0.0-beta.2 (build 30001)

Android와 별도의 **네이티브 SwiftUI iOS 16+** 포트입니다. 패키지 식별자는 `com.studyone.ios`입니다.

구현: NEIS 학교 검색·시간표·중식, 과제/수행평가/시험 관리, 로컬 공부 우선순위 추천, 개인 OpenAI 키(Keychain) 기반 학습 도우미(요청별 동의), schema-1 JSON 백업/복원.

제한: Android의 홈 위젯·로컬 알림·APK 업데이터는 아직 구현하지 않았습니다. iOS 설치 데이터는 Android와 자동 동기화되지 않습니다. iOS API 실사용/실기기 설치는 CI에서 검증하지 않았습니다. App Store 배포 또는 Apple 서명은 포함하지 않습니다.

## macOS 빌드

```sh
brew install xcodegen
bash ios/scripts/build_unsigned_ipa.sh
```

`version.json`에서 버전명을 읽어 `ios/dist/StudyOne-v3.0.0-beta.2-unsigned.ipa`와 SHA-256 파일을 생성합니다. Xcode에서 코드 서명 없이 컴파일되므로, iPhone에는 AltStore/SideStore 등으로 **별도 서명**해서 설치해야 합니다.

공개 릴리즈의 Android APK는 고정 릴리즈 인증서로 서명되지만, 이 IPA는 끝까지 **unsigned**입니다.

GitHub Actions는 테스트된 main 커밋의 Android 서명 프리릴리즈가 생성된 후, **같은 SHA의 태그에만** 검증된 unsigned IPA 및 checksum을 첨부합니다. 서명 APK 배포가 실패하면 iOS 빌드 결과물은 Actions artifact로 남고 GitHub Release에는 첨부하지 않습니다.
