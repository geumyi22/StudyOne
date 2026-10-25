# StudyOne v4 — iOS 4종 통합 JSON 복원 구현 검토

상태: **구현 브랜치에 반영 / 실사용 검증은 별도** (2026-10-26).
대상: Draft PR #60 \`feature/studyone-v4-integrated-release-candidate\`.
기존 공개 beta.2, 운영 서버, 개인 데이터 또는 기기 서명은 변경하지 않는다.

## 구현 범위

- Android/Windows와 같은 \`studyone-transfer-bundle\` schema 1: tasks/progress/details/wrongAnswers, 섹션별 SHA-256 검사
- 가져오기 전 모든 섹션 스키마, 최대 개수, ID·필드 유효성 검증
- 읽기 오류 또는 사용자가 미리보기를 확인하는 사이 로컬 데이터가 변했으면 승인 불가
- 설정 화면에서 **4종 통합 JSON 선택 → 구성 요소 개수 미리보기 → 명시적 전체 교체 승인**
- 첫 번째 쓰기 전에 4종 원본의 정확한 바이트/존재 여부와 SHA-256을 앱 내부 비공개 write-ahead 복구 기록에 저장
- 순차 가져오기 후 4개 저장소의 메모리·데이터 일치 확인; 실패 시 원래 4종을 복원하고 활성 상태도 새로 로드
- 앱 중간 종료 후 다음 실행에서 미완료 복원 기록을 먼저 확인해 원본 복구; 복구 오류가 나면 일반 학습 화면 진입 차단
- Keychain/OpenAI/NEIS 비밀은 백업과 통합 가져오기에 포함되지 않음

## 실제 QA 필수 (미완료를 성공으로 표시하지 말 것)

1. 합성 Android 4종 백업을 iOS Simulator에 가져와 항목별 값과 ID·전체 상태 대조
2. 잘못된 digest/중복 ID/과도한 크기/지원하지 않는 schema에서 **실제 원본 불변** 검증
3. 승인 미리보기 직후 각 로컬 저장소를 바꾸고 stale approval 차단 확인
4. 1/2/3/4번째 저장 도중 강제 중단, 즉시 실패, 재시작 복구 각각 테스트
5. 원본 파일이 없던 경우에도 정확한 부재 상태로 돌아가는지 확인
6. journal 파손·보호 속성 오류·저장 공간 부족 시 정상 학습 진입을 차단하는지 확인
7. 기존 업무용 Android/iOS 자료를 외부 백업한 후에만 별도 실기기 데이터 유지 검증

**CI의 Swift 컴파일 통과는 위 장애 주입·실기기 QA의 대체가 아니다.**
실패 또는 취소된 테스트가 있는 동안 Beta/Stable을 출하하지 않는다.

## 변경 파일

- \`ios/StudyOne/StudyFourStoreImport.swift\`: 검증, 원본 스냅샷, journal, 재시작 복구
- \`ios/StudyOne/StudyOneApp.swift\`: 앱 시작시 복구 후 화면 진입
- \`ios/StudyOne/ContentView.swift\`: JSON 파일 선택·명시 승인
- \`ios/StudyOne/ModelsAndStore.swift\`, \`StudyProgress.swift\`,
  \`StudyTaskDetail.swift\`, \`StudyWrongAnswers.swift\`: 복구 후 메모리 재로딩
- \`scripts/tests/check_phase23_fourstore.py\`: 안전성 정적 규약 검사

## 명확한 제한

- 이 기능은 사용자 승인형 **파일 복원**이다. 세 플랫폼 자동 동기화 또는 ChatGPT OAuth 연결이 아니다.
- 기존 4종을 일괄 교체하므로 독립적인 외부 사본을 반드시 남겨야 한다.
- iOS unsigned IPA는 실제 iPhone 설치 파일로 서명된 것이 아니다.
- 실제 4단계 실패 주입 E2E, 성능, 공개 계정 서버, App Store 서명은 여전히 별도 개발·검증 대상이다.
