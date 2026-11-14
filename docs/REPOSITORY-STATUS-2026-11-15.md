# StudyOne 저장소 · v4 개발 현황 — 2026-11-15

> **상태 스냅샷 / 문서 정리용.** 이 문서에서 `CI_VERIFIED`는 표시된 **특정 커밋**의 자동화 검증이며 `DEVICE_VERIFIED`, `PRODUCTION_VERIFIED`, `RELEASED`와 다릅니다. 문서 추가는 앱 코드 병합, 데이터 이전, 운영 연결 또는 출시 승인이 아닙니다.

## 1. 지금 사용할 기준선

| 구분 | 2026-11-15 GitHub 확인 결과 |
| --- | --- |
| 공식 저장소 | [geumyi22/StudyOne](https://github.com/geumyi22/StudyOne) |
| 기본 `main` HEAD | [`59a789d`](https://github.com/geumyi22/StudyOne/commit/59a789d947277526d3255f01b30ebda33bd43488), v4 Draft 미반영 |
| 공개 최신 | [`v3.0.0-beta.2`](https://github.com/geumyi22/StudyOne/releases/tag/v3.0.0-beta.2) (2026-10-17, prerelease) |
| 기본 브랜치의 개발 후보 | Android `3.1.0-canary.7`, iOS·Windows Canary.6 기반 ([기존 버전 매트릭스](VERSION-MATRIX.md), 2026-10-23 스냅샷) |
| 개발 최전선 | [Draft PR #62](https://github.com/geumyi22/StudyOne/pull/62), `4408e6eb1c71f8675380789b4db56be85f126fd9` |
| v4 출시 여부 | **미출시** — `v4.0.0-beta.1` / `v4.0.0` 태그·릴리스 없음 |
| 사용자 실기기·운영 계정 | 이번 점검에서 접근·검증하지 않음 |

현재 `main`의 기능과 v4 Draft에 구현한 기능을 구분하세요. 공개 베타, Canary 후보, CI 테스트 산출물, 사용자의 실제 설치본은 같은 것이 아닙니다. iOS GitHub IPA는 **unsigned**, Windows 후보는 **미서명**이며, 실사용 설치/교차기기 자동 동기화 준비 완료를 뜻하지 않습니다.

## 2. v4 Draft 개발 스택 (병합 전)

| 순서 | 작업 | 대상/기준 SHA | 상태 |
| --- | --- | --- | --- |
| 1 | [PR #60 — v4 통합 후보](https://github.com/geumyi22/StudyOne/pull/60) | `main` ← `feature/studyone-v4-integrated-release-candidate` / `b3e4b97` | **Draft, 미병합** |
| 2 | [PR #61 — Windows 학습 도구·플랫폼 일치](https://github.com/geumyi22/StudyOne/pull/61) | #60 브랜치 ← `feature/studyone-v4-learning-ux-20261113` / `baf3615` | **Draft, 미병합** |
| 3 | [PR #62 — P0~P4 후속 개발](https://github.com/geumyi22/StudyOne/pull/62) | #61 브랜치 ← `feature/studyone-v4-p0-p4-20261114` / `4408e6e` | **Draft, 미병합** |

위 PR은 **누적 스택**입니다. #62를 `main`에서 독립 구현된 것으로 보거나, 세 PR을 독립적으로 임의 병합하지 마세요. 병합 직전 각 PR의 최종 SHA/차이/체크/충돌을 다시 점검하고 사용자 별도 판단을 받습니다.

## 3. 구현 범위와 검증 수준

| 단계 | Draft 구현 · 자동 테스트 근거 | 남은 경계 |
| --- | --- | --- |
| **P0 데이터 보존** | tasks / progress / details / wrongAnswers의 복구·변경 방지 경로 보존, 합성 4종 복구 및 해시 CI | 사용자 원본 4종의 실제 단말 복원 리허설 미실시 |
| **P1 모바일 범용 시험지** | Android/iOS 과목·교재·단원·회차·기간·제외일·일일 상한 편집, 읽기 전용 미리보기, 별도 승인형 숙제 등록. 정확한 UUID·회차 일치 시에만 완료 자동 채움. 기존 국어 18회 템플릿 보존 | 실기기 시각·접근성·진도 데이터 전체 통합 QA 미실시 |
| **P2 내신 플래너** | 5분류, 오늘/내일/전체, 시험범위·진도·오답·수행평가 기존 동작을 보존하면서 신규 숙제 연결 | 실기기 전체 회귀, 범용 교재 스키마 장기 이관은 별도 |
| **P3 동기화 안전성** | 기존 revision CAS, requestId, tombstone, 오프라인 큐, 계정 분리 테스트 유지 | 실제 운영 HTTPS 인증 계정과 Android↔iOS↔Windows 자동 동기화 미완료 |
| **P4 ChatGPT/MCP** | 읽기 전용 `studyone_read_plan_status` 및 계정별 승인 결과/cursor 테스트 | 실계정 OAuth/MCP 등록·권한 철회·운영 연결 미완료 |
| **P5 통합·실기기·출하 QA** | **진행하지 않음** | [별도 미완료 추적 이슈 #63](https://github.com/geumyi22/StudyOne/issues/63) |

상세 구현은 [#62의 기록](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/docs/IMPLEMENTATION-V4-P0-P4-20261114.md)을 참고합니다. 합성 데이터가 성공해도 실제 학생의 진도/완료 상태가 안전하게 보존된다고 단정할 수 없습니다.

## 4. 최신 v4 HEAD의 GitHub Actions 확인

**검증 대상:** `4408e6eb1c71f8675380789b4db56be85f126fd9`. 다음 7개 실행은 2026-11-15 조회 시 모두 `completed / success`, 동일 `head_sha`였습니다.

| CI | 실행 | 2026-11-15 확인 |
| --- | --- | --- |
| Planner Contract | [43603434813](https://github.com/geumyi22/StudyOne/actions/runs/43603434813) | PASS |
| v4 release contract (nonpublishing) | [43603434738](https://github.com/geumyi22/StudyOne/actions/runs/43603434738) | PASS |
| Recovery Rehearsal (Windows/Linux, 합성) | [43603434735](https://github.com/geumyi22/StudyOne/actions/runs/43603434735) | PASS |
| Sync Core | [43603434749](https://github.com/geumyi22/StudyOne/actions/runs/43603434749) | PASS |
| Account TLS Backend | [43603434721](https://github.com/geumyi22/StudyOne/actions/runs/43603434721) | PASS |
| Android APK / lint / emulator | [43603434709](https://github.com/geumyi22/StudyOne/actions/runs/43603434709) | PASS |
| iOS unsigned IPA / simulator E2E | [43603434760](https://github.com/geumyi22/StudyOne/actions/runs/43603434760) | PASS |

이 7개는 **코드의 당시 자동화 검증 기록**입니다. 이후 코드·의존성·CI 정의·빌드 설정이 바뀌면 새 HEAD에 대해 다시 검사해야 합니다. 사용자 기기 설치, 운영 HTTPS, ChatGPT 실제 계정, 실제 3기기 동기화, Android 정식 서명/Windows 설치/iOS 물리 기기는 포함하지 않습니다.

## 5. 남은 작업 / 출시 차단

1. [P5 검증 이슈 #63](https://github.com/geumyi22/StudyOne/issues/63) — Android/iPhone/Windows 실기기, 기존 버전 업그레이드, 4종 실원본 보호·복원, 시각·성능·접근성·오프라인·앱 기능 회귀. 실데이터는 기기 외부/GitHub로 업로드하지 않음.
2. **운영 연결은 별도 승인:** 테스트 계정·HTTPS·도메인/호스팅 비용·OAuth/MCP 앱 등록 및 권한 철회, 3기기 충돌·재연결·복원 등 운영 E2E.
3. **출하 게이트:** [`RELEASE-4.0.0-ACCEPTANCE.md`](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/docs/RELEASE-4.0.0-ACCEPTANCE.md) 충족, 단일 최종 SHA 필수 CI, 차단 결함 0, 서명/무결성·데이터 보존 근거, 사용자 **명시적 출하 승인**.
4. 출하가 승인되더라도 원칙은 **`v4.0.0-beta.1` 1회 공개 후 검증된 `v4.0.0` Stable**. 기존 태그·베타 자산·서명을 덮어쓰지 않습니다.

## 6. 문서·브랜치 유지관리 원칙

- [2026-10-24 저장소 현황](REPOSITORY-STATUS-2026-10-24.md)과 [버전 매트릭스](VERSION-MATRIX.md)는 당시의 **역사적 기준선**이며, 이 문서는 v4 Draft 상태를 보완합니다. 기존 날짜·근거를 소급 수정하지 않습니다.
- [이슈 #1](https://github.com/geumyi22/StudyOne/issues/1)은 v2.7 출발 당시 로드맵이므로 현재 v4의 유일한 진행표로 사용하지 않습니다. 현재 P5는 #63을 우선합니다.
- 기존 기능/브랜치/PR/릴리스/태그/워크플로는 임의 삭제하지 않고, `main` 코드·버전 메타데이터·서명·사용자 데이터·공개 릴리스는 이번 **문서 정리에서 변경하지 않습니다**.
- [릴리스 정책](RELEASE-POLICY.md)에 따라 문서 수정도 별도 브랜치·PR에서 검토합니다. 문서 자체가 테스트 통과나 출시를 보장하지 않습니다.
