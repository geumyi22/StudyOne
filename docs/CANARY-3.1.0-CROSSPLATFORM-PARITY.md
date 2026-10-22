# StudyOne 3.1.0-canary.6 — Android/iOS/Windows 날짜별 학습 계획 동등화

## 구현 범위
- **Android:** 기존 7개 플래너 세부 메뉴(오늘/내일/전체/진도/오답/쓰기/관리)와 내일 숙제 미리 하기, 통합 4종 백업·원본 복구, 개발자용 로컬 동기화 실험 분리 모두 보존
- **iOS:** 학습 일정 화면에 오늘/내일/전체 날짜 선택 메뉴 추가. 오늘은 미완료 이월 항목을 포함하고, 내일 숙제를 '미리 하기' 목록에 별도 표시, 체크/수정/삭제는 원본 `StudyTask` ID 사용. 분류 중 항목이 없는 섹션 숨김
- **Windows:** 일정 탭에 오늘+미완료 이월+내일 숙제/내일 마감/전체 표시 선택 메뉴 추가. `StudyTaskViews.Select`의 불변 원본 ID 참조 방식을 테스트 프로젝트에서도 활용하여 완료 연동과 기존 데이터 순서를 검증
- 버전: 3.1.0-canary.6, Android Debug ID `com.studyone.app.canary6`, iOS `CFBundleVersion=30106` 검증. Windows는 unsigned EXE preview

## 검증과 위험 구분
- Android CI 빌드·Lint·에뮬레이터 + 기존 7탭 회귀 및 4종 번들 JSON 변조 검사
- macOS iOS IPA 빌드 및 Debug Simulator 컴파일 (서명 없는 IPA는 실제 아이폰에 바로 설치할 수 없음)
- Windows .NET 8 x64 WPF 빌드, portable dataset unit tests, self-contained unsigned EXE
- Ubuntu Planner Contract의 세 플랫폼 일정 필터 정적 검사
- **실기기 Android Canary.3의 사용자 확인 7개 항목은 과거 증빙**으로 유지; 새 Canary.6/iOS/Windows에 실기기 검사했다고 쓰지 않음

## 안전·배포
- 앱 내부 저장 원본과 `main`, 기존 Stable/Beta Release는 변경하지 않음
- Android Canary 테스트 APK들은 별도 패키지명과 CI 기본 디버그 키를 사용하며 서로의 기록을 자동 이전하지 않음. 기존 설치본을 삭제하지 말고, **플래너 → 관리 → 4종 학습 데이터 통합 백업 저장**을 먼저 이용해 외부 파일에 보관
- Android 4종 통합 JSON의 iOS/Windows 직접 가져오기는 아직 미구현이므로 플랫폼 간 이동은 기존 개별 JSON 도구 사용
- 계정 동기화·ChatGPT `할꺼` / `변경사항 인지해` 직접 연결은 인증·서버·권한 및 실제 사용자 연결이 없으므로 이번에 출시하지 않음
- 공개 Stable으로 승격하려면 지속 서명키, 실제 기기별 업그레이드/복원, 인증된 서버, 사용자 수락 시험이 필요

## 사용자 검증 체크
1. Android Canary.5의 기록이 남아 있다면 **삭제 전** 4종 백업을 기기 바깥으로 내보내기
2. Canary.6을 별도 설치 후 7탭·내일 숙제·오답·진도 기능을 검증
3. iOS(별도 서명·설치 필요): 날짜 선택 3모드, 내일 숙제 조기 완료/동일 ID, 기존 오답·쓰기 동작
4. Windows: 오늘/내일/전체 필터, 실시간 완료 체크 및 데이터 유지 확인
5. 연결된 계정 동기화라고 오해하지 말고 각 기기 백업 경로 별도 점검
