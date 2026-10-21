# StudyOne Canary 운영 전 백업·복원 점검 절차

> **아직 실서버 설치 또는 개인 데이터 이전을 승인하는 문서가 아닙니다.** 복구 명령은 실제 서비스 프로세스를 멈춘 상태에서 운영자만 실행합니다. 사용자 파일/세계/OneDrive/Google Drive 등을 자동으로 읽거나 변경하지 않습니다.

## 구현된 기능

- 서비스 종료 확인을 운영자가 명시적으로 전달한 경우에만 새로운 백업 폴더 생성.
- 인증 레지스트리와 계정별 학습 기록·수정 로그·AI 제안 이력 SQLite DB를 sqlite3.backup()으로 독립적인 안전 복사본 생성.
- 모든 백업 파일의 SHA-256·바이트 크기를 manifest.json에 기록.
- 백업을 다시 읽어 SQLite integrity_check / 계정 목록 대조 / 외부 임시 폴더 격리 복원 리허설을 수행.
- 백업 원본을 덮어쓰거나 라이브 데이터 파일에 복원하지 않음. 백업 폴더 내 무결성 손상·ID 누락·경로 탈출을 거부.
- Windows 및 Linux CI에서 합성 SQLite 데이터로 복구·변조 차단 자동 시험.

## 중요한 보안 한계

**백업은 평문 SQLite입니다.** 계정 토큰의 원본은 포함되지 않지만 학습 제목·교재·개인 일정을 포함할 수 있습니다. 암호화된 디스크 또는 OS 암호화 대상에만 보관하고, GitHub/공개 클라우드/채팅으로 업로드하지 마세요. 외부 저장소로 복사하기 전 승인된 암호화·키관리·보존 기간 계획이 필요합니다.

## 사용자 운영 환경이 준비된 후의 명령

첫 단계로 StudyOne 서버를 정상 중지하고 작업 중인 요청이 없음을 확인하세요. 백업 폴더의 부모 디렉터리는 미리 생성·접근 제한하며, 최종 백업 디렉터리는 **기존에 존재하지 않는 이름**이어야 합니다. 서버가 가동 중인 상태에서는 이 명령을 실행하면 안 됩니다.

    python server/recovery.py create --registry /secure/StudyOne/accounts.sqlite3 --data-root /secure/StudyOne/accounts --destination /private_encrypted_disk/StudyOne-backup-YYYYMMDD --ack-server-stopped

복원 리허설(실제 서비스 DB 덮어쓰기 없음):

    python server/recovery.py verify --folder /private_encrypted_disk/StudyOne-backup-YYYYMMDD

결과에서 integrity=PASS, restoreRehearsal=PASS를 확인해야 합니다. 이 도구는 **격리 복원 연습**이지 라이브 서버로 복원을 적용하는 도구가 아닙니다.

## 운영 전에 사용자 선택과 외부 준비가 필요한 사항

1. 어디서 StudyOne 개인 데이터 서버를 운영할지: 관리형 클라우드와 집의 별도 서버 중 결정. 마인크래프트 게임 서버와 데이터·프로세스·방화벽을 섞지 않는 것을 권장.
2. 공개 도메인, 운영 인증서(TLS), 개인 계정 생성/로그인·복구·2단계 인증과 장치 토큰 관리.
3. 유료 과금 허용 범위 및 개인정보 저장 지역·백업 정책.
4. 초기 연결을 위한 테스트 계정만 먼저 생성하고 Android·iOS·Windows 기기에서 데이터 왕복 검증.
5. ChatGPT OAuth/연결 앱 등록 및 읽기·제안/승인 권한 동의.
6. 서버 인증·앱별 토큰 비밀 보관, 모바일 완전 자동 양방향 동기화 및 충돌 해결·삭제 전파는 후속 작업.

현재 PR의 HTTPS 테스트는 **CI 내부에서만 만든 일회성 자체 서명 인증서**로 실제 TLS 핸드셰이크를 검증합니다. 운영 인증서 발급이나 실제 도메인 개설을 의미하지 않습니다. 기존 main/Stable/Beta, 사용자 로컬 학습 기록 불변.
