# StudyOne 버전·배포 매트릭스

기준일: **2026-10-23**, [통합 PR #43](https://github.com/geumyi22/StudyOne/pull/43) 병합 이후. **GitHub 공개 릴리즈**, **`main` 개발 소스**, **실제 사용자 기기 설치/운영 상태**를 혼동하지 않습니다.

| 항목 | Android | iOS | Windows |
| --- | --- | --- | --- |
| GitHub 공개 최신 릴리즈 | **v3.0.0-beta.2** | **v3.0.0-beta.2** (unsigned) | 없음 |
| `main` 소스 후보 | **3.1.0-canary.7** | **3.1.0-canary.6** | **3.1.0-canary.6 기능 후보** |
| 빌드 코드 | **30107** | **30106** | 별도 Windows 앱 |
| 앱 ID | `com.studyone.app` | `com.studyone.ios` | WPF/.NET Windows 앱 |
| 최신 개발 빌드 | 공식 서명 Canary.7 APK 검증, CI debug는 분리 설치 | 미서명 IPA·시뮬레이터 빌드 | 미서명 WPF EXE 프리뷰 |
| 실제 설치·데이터 유지 | 공식 서명 Beta.2→Canary.6→Canary.7 **에뮬레이터 덮어쓰기 통과**. 실사용 기록 보존은 기기별 확인 필요 | 실제 iPhone 설치/서명 미검증 | 설치·제거·자동 업데이트 실기기 검증 필요 |
| 기기간 자동 동기화 | **없음** | **없음** | **없음** |
| 서버 연동 기능 | 수동 HTTPS 계획 승인함·신규 ID만 가져오기 코드 | 실험적 HTTPS 클라이언트 | 실험적 계정 서버 클라이언트 |
| ChatGPT 직접 연결 | **미배포** — MCP 프로토타입만 있음 | **미배포** | **미배포** |

## 검증 결과

- [PR #43](https://github.com/geumyi22/StudyOne/pull/43): Android 빌드·Lint·에뮬레이터, iOS unsigned IPA 및 Debug simulator 컴파일, Windows WPF/.NET, 플래너, 계정 권한·서버 동기화, Windows/Linux 복구 CI 모두 통과.
- [서명된 Canary.7 검증 실행](https://github.com/geumyi22/StudyOne/actions/runs/39961148498): 공식 서명 인증서 일치·단일 `com.studyone.app` 업데이트·패키지 버전 30107 확인. **정식 공개 릴리즈는 아님**.
- iOS 버전은 `ios/version.json`에서 독립 관리하며, Android용 루트 `version.json`이 Canary.7로 올라가더라도 iOS를 Canary.7로 잘못 표시하지 않습니다.

## 불변 조건 및 다음 작업

1. 이미 게시된 GitHub Beta 릴리즈·태그·APK·IPA는 **수정/삭제하지 않음**.
2. `main` 업데이트는 휴대폰의 기존 로컬 데이터·서명키·사용자 설정을 변경하지 않음.
3. Canary.7 공식 서명 APK는 **기존 공식 서명 설치본 위에만** 덮어쓰기 가능. Debug의 별도 패키지에는 적용할 수 없음.
4. 개인 학습 기록은 로컬 앱 원본이며 서버에 자동 업로드하지 않음. **4종 백업 외부 저장** 권장.
5. ChatGPT `할꺼` / `변경사항 인지해`는 OAuth/실사용 HTTPS 계정 서버·앱 동기화가 등록되기 전까지 자동 연결되지 않음.
6. 기존 릴리즈 워크플로는 **수동 승인 전용**으로 보호. 기존 릴리즈를 자동 재발행하지 않음.
7. 다음 단계: HTTPS 운영 서버·계정/기기 인증 → ChatGPT MCP/OAuth 연결 → Android/iOS/Windows 실기기 동기화·안전성 검증.

관련: [Next Stable 로드맵](NEXT-STABLE-ROADMAP.md), [ChatGPT 연동 설계](CHATGPT-SYNC-DESIGN.md), [Android 계획 승인함](IMPLEMENTATION-PHASE20-ANDROID-CHATGPT-PLAN-INBOX.md).
