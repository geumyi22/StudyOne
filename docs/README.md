# StudyOne 문서 탐색 및 정리 기준

> **갱신: 2026-11-18 (KST) · 문서 전용 Draft PR #64**  
> 이 파일은 안내·색인 문서입니다. 코드 병합, 앱 설치, 기기 데이터 접근, 배포·릴리스 승인이 아닙니다.  
> 현재 공개 최신은 **v3.0.0-beta.2**, `main`은 v4 Draft 미반영, v4 Beta/Stable은 **미출시**입니다.

## 지금 먼저 볼 문서

| 목적 | 기준 문서 | 해석 |
| --- | --- | --- |
| 공개 앱·플랫폼 지원 현황 | [상위 README](../README.md) | 공개 버전과 설치 가능한 앱을 우선 확인 |
| 개발 코드와 검증 상태 | [2026-11-15 저장소 현황 + 11/18 최신 부록](REPOSITORY-STATUS-2026-11-15.md) | 시점별 사실을 구분; 맨 앞 최신 부록부터 읽기 |
| v4 기능/보안/출시 필수 기준 | [v4 수용 기준 (개발 브랜치)](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/docs/RELEASE-4.0.0-ACCEPTANCE.md) | 필수 항목 미완료 시 배포 금지 |
| P5 실기기·계정·복원 검증 | [P5 실제 실행 절차](V4-P5-DEVICE-QA-PLAN.md) · [이슈 #63](https://github.com/geumyi22/StudyOne/issues/63) | 사용자 보고 Canary 수동 QA와 정식 출시 게이트 구분 |
| 버전/기기별 차이 | [버전 매트릭스](VERSION-MATRIX.md) | 2026-10 당시 스냅샷. 최신 v4 현황은 위 현황 문서 우선 |
| 공개 릴리스 이력 | [CHANGELOG](../CHANGELOG.md) · [v3 Beta 2 릴리스 노트](RELEASE-3.0.0-BETA.2.md) | 이미 발행된 릴리스는 삭제·재작성 금지 |
| 서명·업데이트 | [서명과 업데이트](SIGNING-AND-UPDATES.md) · [릴리스 운영 정책](RELEASE-POLICY.md) | Android 패키지/인증서, iOS 서명, Windows 설치 검증 선행 |

## v4 개발 경로 (미병합)

1. [Draft PR #60](https://github.com/geumyi22/StudyOne/pull/60) — 통합 v4 기반, `main` 대상으로 미병합
2. [Draft PR #61](https://github.com/geumyi22/StudyOne/pull/61) — #60 위 Windows 학습 도구·교차 플랫폼 학습 UX
3. [Draft PR #62](https://github.com/geumyi22/StudyOne/pull/62) — #61 위 P0~P4 후속. 기준 HEAD `3a0a3c6`, 자동 CI **7/7 PASS** (합성·에뮬레이터·시뮬레이터)
4. [문서 전용 Draft PR #64](https://github.com/geumyi22/StudyOne/pull/64) — `main` 기반 안내, P5 절차·문서 색인. 앱 코드와 v4 스택을 병합하지 않음

[PR #59](https://github.com/geumyi22/StudyOne/pull/59)도 미병합 상태입니다. 향후 v4 통합 시 기능 중복/차이를 확인한 뒤 사용자 승인 절차에 따라 반영 여부를 결정해야 합니다.

## 문서 분류

| 분류 | 경로/예시 | 보존 이유 |
| --- | --- | --- |
| 현재 제품·설치·버전 | `../README.md`, `VERSION-MATRIX.md`, `RELEASE-POLICY.md`, `SIGNING-AND-UPDATES.md` | 사용 안내와 호환성·서명 계약 |
| 기존 릴리스 증거 | `RELEASE-*.md`, `../CHANGELOG.md` | 재현·호환성·배포 감사 기록 |
| Canary 이력 | `CANARY-*.md`, `SIGNED-SINGLE-APP-CANARY.md` | 테스트·설계 변경과 데이터 보존 근거 |
| 단계별 구현 | `IMPLEMENTATION-PHASE*.md` | 당시 동작과 검증 범위 기록; 최신 구현으로 단정 금지 |
| 신규 v4 기능 | `IMPLEMENTATION-V4-*.md` (개발 브랜치) | 아직 `main`에 병합되지 않은 설계·구현 증거 |
| 서비스·인증 | `CHATGPT-SYNC-DESIGN.md`, `NEIS-HTTP500-REPORT.md` | 장애 분석, OAuth/MCP 초기 설계 이력 |
| 역사적 계획 | `NEXT-STABLE-ROADMAP.md`, `REPOSITORY-STATUS-2026-10-24.md` | 당시 판단만 보존; 현재 상태는 최신 문서 우선 |
| 안전하게 비활성화한 이력 | `archive/legacy-release-v2.7.yml` | `.github/workflows/` 밖으로 이동된 과거 자동 릴리스 워크플로; 실행 대상 아님 |

## 삭제·정리 후보 검토 (2026-11-18)

- **확정 삭제 대상 없음.** `main` 전체 트리(216개 추적 파일, 그중 문서 54개·실행 워크플로 12개)를 확인했으나, 비슷한 제목의 구현/릴리스 문서는 서로 다른 단계·시점의 기록입니다.
- 내용 해시가 완전히 동일한 두 파일은 Android 런처 `ic_launcher.xml`과 `ic_launcher_round.xml`입니다. 서로 다른 리소스 이름을 사용하므로 **중복 제거 금지**.
- **추가 승인 필요(보류):** `.github/workflows/one-time-signed-canary6-preview.yml`, `one-time-signed-canary7-preview.yml`은 특정 커밋에 고정된 일회성 서명 프리뷰로서 수동 실행 가능성이 남아 있습니다. 향후 참조/재검증이 불필요하다는 명시적 승인을 받은 뒤에만 비활성화 또는 아카이브/삭제 여부를 결정합니다.
- `.github/workflows/signed-canary-preview.yml`, `signed-release.yml`, CI 워크플로, `docs/archive/`, 과거 릴리스 및 기존 브랜치/PR/태그는 **보존**. 오래됐다는 이유만으로 삭제 금지.
- 기존 사용자 데이터 4종(`tasks`, `progress`, `details`, `wrongAnswers`), 앱 ID, 서명, GitHub Release 자산을 정리 작업으로 변경하거나 저장소에 복사하지 않습니다.

## 정리 작업 완료와 검증 수준

- 본 문서와 README/저장소 현황은 문서 전용 Draft PR에서 관리합니다. **`main`은 사용자의 별도 병합 승인 전 그대로** 유지합니다.
- Canary.7 Android 실기기 수동 테스트 **19/19 사용자 보고 PASS**는 기록으로 보존하지만 정식 앱 서명·데이터 복원·운영 3기기 동기화·iOS 물리 기기 검증을 대체하지 않습니다.
- v4 출하 결정은 [P5 이슈 #63](https://github.com/geumyi22/StudyOne/issues/63)과 [v4 수용 기준](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/docs/RELEASE-4.0.0-ACCEPTANCE.md)을 모두 만족한 뒤 별도 승인으로 진행합니다.
