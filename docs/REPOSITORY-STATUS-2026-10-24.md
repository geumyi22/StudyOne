# StudyOne 저장소 상태 및 유지보수 점검 — 2026-10-24

> 이 문서는 **GitHub 소스·릴리즈·CI·PR의 점검 기록**입니다. 개인 휴대폰의 설치/동기화 상태를 인증하지 않으며 새 Stable 릴리즈를 뜻하지 않습니다.

## 확인된 기준선

| 구분 | 2026-10-24 점검 결과 |
| --- | --- |
| 기본 브랜치 | `main` (`cac8ec8f353df27ee4fa3353938e480a00090344`, 문서 정리 시작 기준) |
| 마지막 기능 통합 | [PR #43](https://github.com/geumyi22/StudyOne/pull/43), 2026-10-23 병합 |
| 통합 후 문서 상태 정리 | [PR #44](https://github.com/geumyi22/StudyOne/pull/44), 2026-10-23 병합 |
| 공개 최신 | [v3.0.0-beta.2](https://github.com/geumyi22/StudyOne/releases/tag/v3.0.0-beta.2), **prerelease** (Android APK, iOS unsigned IPA) |
| `main` Android | `3.1.0-canary.7` / `versionCode 30107`, 공개 배포 아님 |
| `main` iOS | `3.1.0-canary.6` / `CFBundleVersion 30106`, 미서명 후보 |
| `main` Windows | WPF/.NET 8 Canary.6 소스·미서명 EXE 프리뷰, 정식 설치판 아님 |
| ChatGPT/계정 동기화 | 계정별 HTTPS·MCP·계획 승인함 **개발용 프로토타입**. 운영 서버/OAuth/실제 ChatGPT 계정 연결 및 자동 동기화는 **아직 없음** |

## CI 및 설치 검증 범위

- [Android APK 최신 `main` CI](https://github.com/geumyi22/StudyOne/actions/runs/39969122296): 2026-10-23 성공.
- [통합 Android / iOS / Planner Contract](https://github.com/geumyi22/StudyOne/pull/43): 통합 시점 워크플로 성공 기록. Windows·서버·복원 등 검증 내역은 PR 체크 및 워크플로 기록을 참고.
- [공식 서명 Canary.7 단일 앱 업데이트 검증](https://github.com/geumyi22/StudyOne/actions/runs/39961148498): 인증서·패키지·버전 및 Beta.2→Canary.6→Canary.7 **에뮬레이터** 업그레이드 검증. **공개 릴리즈/실사용 데이터 검증과 다름**.
- iOS 실기기 서명·설치, Windows 설치 관리자/자동 업데이트, 운영 HTTPS/OAuth 및 여러 실기기 동기화 E2E는 아직 완료됐다고 표시하지 않음.

## 누적 개발 PR 정리

- 이전 개발 스택의 열린 Draft PR **#5–#33 (29개)** 를 2026-10-24에 종료하고 개별 PR에 근거를 기록함.
- 각 브랜치를 [PR #43의 최종 head](https://github.com/geumyi22/StudyOne/pull/43)에 비교한 결과 모두 **head 조상(`ahead_by=0`)** 이므로, 개발 스택 통합 뒤 중복 병합할 필요가 없음.
- **중요:** `main`은 통합 커밋을 사용했으므로 원래 PR의 커밋이 `main`의 직접 조상이라는 의미가 아님. 종료한 각 PR도 GitHub에서 별도 병합(`merged`)된 것은 아님.
- 종료는 상태만 변경한 것이며 **원본 브랜치, 커밋, PR 논의, 릴리즈, 태그, GitHub Actions 기록을 삭제하지 않음**.
- 후속 유지보수 PR은 기존 통합 스택과 분리해 `main` 기준으로 작성. 오래된 브랜치를 다시 `main`에 직접 합치지 않음.

## 다음 작업 우선순위 (새 릴리즈 아님)

1. **P0 사용자 데이터:** 일정·진도·상세·오답 4종 백업의 실제 복원/부분 실패/중복/충돌 및 롤백 검증. 어떤 테스트도 사용자의 실데이터를 직접 덮어쓰지 않도록 함.
2. **P0 Android 안정화:** 공식 서명 버전 덮어쓰기와 앱 데이터 보존, 7탭 플래너, 이월/체크, 오답·쓰기 기능 회귀 검증.
3. **P1 iOS·Windows:** 기능 차이 문서화, 실기기 설치/서명/복원, Windows 설치·업데이트 검증.
4. **P1 서버 보안:** 운영 HTTPS, 계정 분리·토큰/세션/복구, 배포 시크릿, 감사 및 재해 복구.
5. **P1 ChatGPT 연동:** 인증된 OAuth/MCP 앱 연결, 제한된 읽기, 초안 제안·사용자 승인·충돌 처리, 취소/재시도 E2E.
6. **P2 Stable:** 플랫폼별 릴리즈 체크리스트·성능/접근성 검증을 완료한 뒤 별도 승인과 신규 태그로만 게시.

## 보존 및 운영 규칙

- `version.json`, `ios/version.json`, 앱 ID, 인증서/서명 정책, 기존 릴리즈 첨부 파일은 **문서 정리만을 위해 변경하지 않음**.
- API 키·토큰·개인 공부/학교 데이터는 코드·문서·CI 로그·백업 공개 자료에 포함하지 않음.
- 과거 설계 문서가 '미구현'이라고 표시하는 경우 **당시 설계 기준의 기록**일 수 있음. 현재 코드는 [버전 매트릭스](VERSION-MATRIX.md), [구현 단계 문서](IMPLEMENTATION-PHASE20-ANDROID-CHATGPT-PLAN-INBOX.md), 실제 소스와 비교해 판단.
- 오래된 릴리즈/브랜치/워크플로는 의존성 분석 없이 삭제하지 않음.

참고: [루트 README](../README.md) · [버전/배포 매트릭스](VERSION-MATRIX.md) · [릴리즈 정책](RELEASE-POLICY.md) · [Next Stable 로드맵](NEXT-STABLE-ROADMAP.md).
