# StudyOne 개발 17단계 — iOS 학습 상세정보 Canary

## 변경 범위
- Android PR #25의 로컬 전용 일정 상세정보(예상 시간·공부 단계·완료 기준)를 iOS에 추가합니다.
- 기존 `StudyTask` schema 1, 진도 저장소, NEIS, 계정·동기화 API, 인증 및 공개 릴리즈는 변경하지 않습니다.
- 별도의 Application Support 파일 `StudyOne/TaskDetails-v1.json`에 원자적 쓰기와 파일 보호를 적용합니다.
- Android `studyone-task-details` schema 1 JSON과 필드·수량 제한을 일치시킵니다.
- 사용자가 선택한 JSON 파일은 2MB·5000개 제한과 필드/중복/빈 항목을 확인한 뒤 명시적으로 교체할 때만 반영합니다.
- 가져오기 직전 로컬 원본 바이트를 앱 내부 `BeforeTaskDetailsImport`에 별도 저장하고, 승인 중 원본이 달라지면 중단합니다.
- 기존 일정 삭제 시 이 파일의 상세정보는 그대로 남아 있을 수 있으나 기존 데이터 보존을 위해 자동 삭제하지 않습니다.

## 검증 게이트
- [ ] Planner Contract의 iOS 정적 계약 검사 통과
- [ ] iOS unsigned IPA 및 시뮬레이터 Debug 컴파일 통과
- [ ] 실제 iPhone에서 입력·편집·복사·백업·복원 UI 검증
- [ ] 별도 사용자 승인 이후에만 Beta/Stable 승격

## 보안 및 한계
- 데이터는 기기 로컬에 있으며 서버/ChatGPT와 자동 동기화되지 않습니다.
- Android의 기존 기본 일정 JSON에는 학습 상세 필드가 추가되지 않습니다.
- 다른 기기에서 같은 taskId가 없는 경우 가져온 상세정보는 매칭되는 일정이 생길 때까지 보이지 않을 수 있습니다.
- iOS unsigned IPA를 바로 설치할 수 있다는 의미가 아닙니다.
- 아직 Canary Draft 단계이며 공개 Beta/Stable을 변경하지 않습니다.
