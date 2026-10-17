# StudyOne CHANGELOG

## 3.0.0-beta.2 — 2026-10-17
- Android와 iOS의 버전 명칭을 beta.2 / build 30001로 통일.
- 기존 Android V3 AI 기능·저장소·고정 릴리즈 서명 인증서를 유지하며 iOS SwiftUI 프리뷰 빌드를 배포 채널에 포함.
- iOS 미서명 IPA 및 SHA-256 산출물의 버전·무결성 확인, 동일 커밋에 대한 GitHub Releases 첨부 자동화.
- 실패 원인이었던 기존 릴리즈 태그 재발행을 건너뛰도록 Android 배포 워크플로 수정.
- 기능 지원 매트릭스, 보안·실기기 검증 한계, 설치 및 데이터 이동 주의사항 문서 정리.
- 신규 사용자 기능은 beta.1 Android 대비 추가하지 않음. iOS 포트 실기기 검증은 별도.

## 3.0.0-beta.1 — 2026-10-11
- Android OpenAI Responses API 개인 키 방식 학습 도우미.
- 동의형 과제 공유, AES-GCM API 키 저장, 설명/퀴즈/공부 계획.
- 고정 서명 APK, CI 에뮬레이터 업그레이드 검증.
- iOS SwiftUI 초기 포트는 이후 main에 병합되었으나 beta.1 GitHub Release에는 IPA가 첨부되지 않았음.

## 2.8.0-beta.1 — 2026-10-11
- Android 검증형 앱 내부 업데이트 및 서명 관리.

## 2.7.0-beta.1 — 2026-10-11
- NEIS, 학습 일정·알림·위젯, 로컬 JSON 백업 등 기존 Android 기능 통합.

변경 이력은 릴리즈 관련 핵심 사항만 요약합니다. 기존 버전의 자세한 기록은 docs/RELEASE-*.md를 참고하세요.
