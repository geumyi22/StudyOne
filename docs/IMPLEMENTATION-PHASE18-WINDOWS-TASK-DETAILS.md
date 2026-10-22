# StudyOne 개발 18단계 — Windows 학습 상세정보 Canary

## 구현 범위
- Android PR #25와 iOS PR #26의 별도 학습 상세정보(예상 시간·단계·완료 기준)를 Windows WPF에 추가합니다.
- 사용자가 일정 표에서 항목을 선택한 후 별도의 '학습 상세 저장' 버튼으로 저장하며, 기존 일정 추가/수정·완료 상태 저장은 그대로 유지합니다.
- `%LOCALAPPDATA%/StudyOne/task-details.json`은 기존 `tasks.json`, `progress.json` 및 계정 동기화 API와 독립됩니다.
- Android/iOS `studyone-task-details` schema 1 형식·필드·2MB/5000개 제한과 같은 검증 규칙을 적용합니다.
- 일별 5분류 클립보드 복사에 기존 과목명과 함께 상세정보를 선택적으로 포함합니다.
- 가져오기는 내용/중복 ID/범위를 검증하고 명시적인 사용자 확인을 받으며, stale-read를 검사하고 이전 바이트를 `BeforeTaskDetailsImport`에 백업한 후에만 교체합니다.

## 수용 기준
- [ ] Windows .NET 테스트 및 WPF 빌드 통과
- [ ] win-x64 단일 EXE 생성과 SHA256 확인
- [ ] Windows PC 실제 실행·입력·복사·백업/복원 테스트
- [ ] Android/iOS/Windows 교차 기기 JSON 왕복 검증
- [ ] 별도 승인 이후에만 Beta/Stable 승격

## 주의
- 테스트 CI가 성공해도 사용자 PC에 설치하거나 기존 데이터가 실제 동기화된 것은 아닙니다.
- 사용자 일정이 삭제되어도 관련 상세정보는 자동 삭제하지 않습니다(데이터 손실 방지).
- 타 기기의 일정 ID와 상세 JSON의 taskId가 일치할 때에만 기존 일정에서 상세를 확인할 수 있습니다.
- `main`, 공개 릴리즈, 사용자 기기 데이터, 비밀키는 변경하지 않습니다.
