# StudyOne Windows Canary — WPF .NET 8

> 이 앱은 **개발 중인 Windows x64 프리뷰**입니다. 기존 Android/iOS StudyOne Beta 2 및 Stable 릴리즈와 다릅니다.

> **2026-10-24 개발 상태:** WPF Canary.6 후보 및 아래 로컬 동기화 실험 코드는 [통합 PR #43](https://github.com/geumyi22/StudyOne/pull/43)으로 `main`에 포함됐습니다. 후속 계정별 HTTPS 수동 동기화 코드도 실험적으로 존재하지만 **실시간 자동 동기화나 정식 설치 프로그램은 아닙니다**. 아래 루프백 절차는 초기 개발 예시이므로 [현황 보고](../docs/REPOSITORY-STATUS-2026-10-24.md) 및 [서버 HTTPS 안내](../server/PRIVATE-SYNC-RUNBOOK.md)와 함께 확인하세요.

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

## 로컬 동기화 개발 실험 (초기 PR #10, 현재 #43 통합 완료)

- 앱 상단 **동기화 실험** 탭을 선택합니다.
- **같은 Windows PC**에서 개발 서버를 먼저 실행해야 합니다. 안내: ../server/README.md.
- 서버 실행에 사용한 테스트 토큰(32자 이상)을 앱의 임시 비밀번호 입력칸에 입력합니다. 앱은 토큰을 파일에 보관하지 않습니다.
- **서버 기록 읽기**는 서버에 저장된 목록/개수만 조회하고 PC나 서버 데이터를 수정하지 않습니다.
- **새 항목만 서버로 전송**은 다른 기기에 아직 없는 UUID/일정 ID에 한해 전송 목록을 계산한 뒤 사용자 승인을 요청합니다. 기존 서버 ID는 수정·삭제하지 않습니다.
- 같은 PC의 루프백 테스트뿐이므로 **다른 Android/iOS/Windows 기기에서 자동 동기화되는 것이 아닙니다**.
- 토큰, 교재·과목·개인 학습 기록은 GitHub에 게시하지 마세요. 실제 클라우드 운영/개인계정 연동은 아직 미지원입니다.
