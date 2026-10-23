# StudyOne private HTTPS sync Canary — 계정·기기 인증 설계

이 개발은 기존 단일 사용자 로컬 서버와 별도의 HTTPS 서버입니다. 기존 Beta/Stable 및 main에는 자동 배포되지 않습니다.

## 이번에 구현된 기능

- SQLite 계정 레지스트리: 계정 UUID, 기기 UUID, read/read-write 권한, 만료시각, 폐기 상태.
- 사용자 UUID별 별도의 SQLite 데이터베이스: 일정·교재 진도·수정 로그·삭제 tombstone 및 요청 멱등성.
- 48바이트 난수 기반 장치 토큰은 생성 직후 1번만 표시. 디스크에는 원본 토큰 대신 SHA-256 검증용 해시만 저장.
- 기기 토큰 폐기, 계정 비활성화, 만료·권한 분리.
- TLS 인증서와 개인키 없이는 서버 시작 차단. 외부 바인딩은 별도 명시 플래그 필요.
- HTTPS 헤더(HSTS 등), Origin/CORS 제한, 원본 학습 기록 및 인증정보의 로그 비출력.
- 서버 변경 API: GET /v2/health, GET /v2/snapshot, GET /v2/changes?after=0, POST /v2/changes.
- 이전 단일 사용자 서버의 revision/409 충돌/원자성/idempotency를 계정별 데이터에 재사용.

## 로컬 개발용 운영 절차

Python 3.12+ 필요. 각 명령은 프로젝트 루트에서 개발 서버 소유자가 직접 실행합니다. 사용자 개인 학습 정보나 실제 계정은 자동 생성되지 않습니다.

    python server/private_sync.py create-account --label personal
    python server/private_sync.py issue-device --account YOUR_ACCOUNT_UUID --label windows --scope read-write --days 30
    python server/private_sync.py revoke-device --device YOUR_DEVICE_UUID
    python server/private_sync.py disable-account --account YOUR_ACCOUNT_UUID

기본 인증 DB는 홈 디렉터리의 .studyone/accounts.sqlite3이며 사용자별 데이터는 .studyone/accounts/UUID.sqlite3입니다.
발급된 토큰은 한 번만 출력됩니다. 채팅, GitHub, 스크린샷에 공유하지 마세요.

공인 CA의 인증서 및 안전한 서버 보안 설정이 준비된 경우의 로컬 HTTPS 실행:

    python server/private_sync.py serve --cert /secure/fullchain.pem --key /secure/privkey.pem

기본 주소는 127.0.0.1:9443. 원격 바인딩에는 별도 명시 허용 및 네트워크 보안 정책이 필요합니다.
코드를 만들었다고 실제 공개 HTTPS 서비스가 배포되거나 도메인·인증서가 생성된 것은 아닙니다.

## 배포 차단 조건

- 실제 사용자 본인 인증/계정 복구/토큰 로테이션 UX, 분실 대응, 인증 권한 관리
- Windows DPAPI, iOS Keychain, Android Keystore 등 안전한 클라이언트 비밀 저장
- TLS 인증서 자동 갱신, 인증/요청 속도 제한, DDoS 대응, 감사/운영 지표 및 정기 백업
- 실기기 클라이언트의 v2 API, 서버 변경사항 로컬 적용, 오프라인 큐, 충돌 UI
- 실제 개인정보 처리·계정 보안 심사, 복원·재해 복구 실험, 앱 릴리즈 전 데이터 검증
- ChatGPT용 읽기/쓰기 OAuth 권한 승인, MCP 연결 및 사용자 데이터 분리
- UI/UX 및 기기별 실기기 테스트, 기존 버전 호환 마이그레이션

현재 구현은 서버 측 계정 격리를 검증하는 Canary 기반이며 Android·iOS·Windows 자동 동기화 완료 상태가 아닙니다. 기존 앱/기기 데이터와 GitHub 공개 Beta 릴리즈는 보존합니다.
