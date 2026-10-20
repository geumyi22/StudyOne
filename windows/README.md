# StudyOne Windows Canary — WPF .NET 8

> 이 앱은 **개발 중인 Windows x64 프리뷰**입니다. 기존 Android/iOS StudyOne Beta 2 및 Stable 릴리즈와 다릅니다.

## 현재 실제 기능

- Windows WPF 데스크톱 앱의 일정·숙제 목록과 편집/추가/삭제/완료 체크
- 5개 학습 역할과 기존 과제·수행평가·시험·준비물 분류 보존
- 교재별 시험범위·진도 기록, 4가지 진행 상태(확인 필요/미완료/진행 중/완료), 시험범위 완료율
- 로컬 JSON 저장(%LOCALAPPDATA%/StudyOne), 임시 파일을 통한 저장 후 교체, 파일 읽기 오류 시 기존 원본 덮어쓰기 차단
- 기존 Android/iOS StudyOne 학교·일정 JSON schema-1 및 별도 진도 JSON으로 수동 내보내기/가져오기
- 가져오기 전 전체 형식 검증과 사용자 교체 승인
- API 키를 저장하거나 OpenAI/NEIS 네트워크 호출을 수행하지 않음

## 현재 제한

- Android·iOS·Windows 간 실시간 동기화가 **아닙니다**. 파일 이동은 수동입니다.
- 기존 모바일의 시간표/급식, AI 도우미, 알림/위젯은 이 Windows 개발 프리뷰에 **아직 없습니다**.
- 빌드 산출물은 **서명되지 않은 win-x64 단일 EXE 프리뷰**입니다. 별도 Windows 설치 관리자, 코드 서명, 자동 업데이트는 아직 미구현입니다.
- Windows 실기기 UI/배포 시험 및 32비트/ARM 호환성 검증은 별도로 필요합니다.
- 하나의 앱에서 여러 동시 실행 프로세스가 동일 JSON 파일을 수정할 때 완전한 충돌 방지는 아직 미구현이므로 단독 실행을 권장합니다.
- 기존 전체 일정 JSON과 별도 진도 JSON을 모두 백업해야 합니다.
- 백업 파일에는 개인 학습 정보가 포함될 수 있으므로 외부 공개 금지.

## 빌드

Windows 10/11, .NET 8 SDK 사용:

    dotnet build windows/StudyOne.Desktop/StudyOne.Desktop.csproj -c Release
    dotnet run --project windows/StudyOne.Desktop.Tests/StudyOne.Desktop.Tests.csproj -c Release
    dotnet publish windows/StudyOne.Desktop/StudyOne.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true

GitHub Actions: StudyOne Windows Canary EXE. 빌드가 통과하면 14일간 임시 개발 artifact를 제공합니다. 기존 공개 GitHub Releases에 자동 게시하지 않습니다.

## Stable 이전 필수 항목

- 모바일/Windows 사용자 데이터의 양방향 동기화·오프라인 충돌 해결
- 통합 UI/UX 및 접근성·성능 점검
- Windows 실행/업데이트/데이터 보존 실환경 시험 및 적절한 코드 서명·설치 관리자
- ChatGPT MCP 읽기·쓰기 인증과 사용자 승인 기능
- 각 플랫폼의 기능 지원 범위와 릴리즈 기준 확정

개발 로그: docs/IMPLEMENTATION-PHASE4-WINDOWS.md
