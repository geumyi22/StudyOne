# StudyOne 개발 4단계 — Windows 설치형 앱 기반 (개발 프리뷰)

> **상태: Canary Draft PR, Stable 배포 전.**

## 구현

- 별도 Windows WPF/.NET 8 UI를 추가하여 기존 Android(Java), iOS(SwiftUI) 코드를 재작성하지 않고 세 번째 플랫폼 기반을 마련했습니다.
- 날짜별 일정/숙제/수행과 교재 진도를 직접 등록·수정·삭제 가능.
- 모바일과 같은 5개 분류 및 4개 진도 상태 사용.
- Android/iOS의 StudyOne 전체 일정 schema-1 JSON과 진도 전용 studyone-progress schema-1 JSON을 읽고 쓰는 수동 데이터 이동 기능.
- Windows 로컬 파일은 %LOCALAPPDATA%/StudyOne에 저장, 임시 파일 후 교체, 파일 파손 시 자동 초기화/덮어쓰기 금지.
- 가져오기 전 형식/크기/중복 ID/상태/날짜 검증 및 사용자 승인.
- GitHub Actions Windows x64 단일 EXE 빌드와 플랫폼 간 JSON 호환성 테스트, SHA-256 확인 및 CI artifact 업로드.

## 안전 및 비목표

- 기존 Beta 2 릴리즈/태그, Android·iOS 앱 버전과 서명, 사용자의 로컬 공부 기록은 수정하지 않습니다.
- Windows 프리뷰는 **미서명**이며, Windows Installer/MSIX가 아닌 직접 실행형 EXE 산출물입니다.
- Windows NEIS/AI/알림, 실시간 동기화, 서버형 MCP, 자동 업데이트는 구현하지 않았습니다.
- 앱의 기본 UI가 갖춰졌어도 전면 UI/UX 통합이 완료됐다고 주장하지 않습니다.
- 실환경 바이너리 서명/바이러스 오탐/Windows 10/11 테스트는 아직 별도 증빙 필요.
- CI 성공은 Windows 실기기에서 사용자 작업 흐름이 모두 정상이라는 뜻이 아닙니다.

## 후속

1. 계정·클라우드 데이터·변경 로그와 사용자 승인 방식 확정, 모바일/Windows 동기화.
2. AI 계획과 앱 진도·숙제 DB 간 양방향 ChatGPT MCP 통합.
3. 사용성/접근성/성능·보안 검증, 필요 기능의 플랫폼 동등성 보완.
4. Canary/Beta/Stable 출시 파이프라인과 안전한 데이터 마이그레이션·릴리즈 테스트.
