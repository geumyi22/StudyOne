# StudyOne private HTTPS sync Canary — 계정·기기 인증 설계

이 개발은 기존 단일 사용자 로컬 서버와 별도의 HTTPS 서버입니다. 기존 Beta/Stable 및 main에는 자동 배포되지 않습니다.

## 이번에 구현된 기능

- SQLite 계정 레지스트리: 계정 UUID, 기기 UUID, read/read-write 권한, 만료시각, 폐기 상태.
- 사용자 UUID별 별도의 SQLite 데이터베이스: 일정·교재 진도·수정 로그·삭제 tombstone 및 요청 멱등성.
- 48바이트 난수 기반 장치 토큰은 생성 직후 1번만 표시. 디스크에는 원본 토큰 대신 SHA-256 검증용 해시만 저장.
- Phase24: MCP 전용 `audience=mcp` 토큰은 `read` 권한만 가능하며, **`POST /mcp`에서만** 사용할 수 있습니다. 일반 `/v2/snapshot`, `/v2/changes`, 계획 승인·거부 API는 **403**을 반환합니다.
- 기기 토큰의 일회성 로테이션: 새 난수를 발급하고 기존 해시를 하나의 SQL 트랜잭션에서 교체합니다. 새 토큰이 노출되는 곳은 발급 당시 운영자 터미널뿐입니다.
- 계정별 장치 조회는 기기 ID/라벨/범위/발급일/만료일/폐기 여부만 출력하고, 원본 토큰이나 토큰 해시는 공개하지 않습니다.
- 기존 계정 DB는 `audience=device` 컬럼을 안전하게 추가하며, **기존 기기 토큰의 접근 권한이 사라지지 않습니다**.
- 기기 토큰 폐기, 계정 비활성화, 만료·권한 분리.
- TLS 인증서와 개인키 없이는 서버 시작 차단. 외부 바인딩은 별도 명시 플래그 필요.
- HTTPS 헤더(HSTS 등), Origin/CORS 제한, 원본 학습 기록 및 인증정보의 로그 비출력.
- 서버 변경 API: GET /v2/health (공개 상태 확인), **GET /v2/session (실제 기기 인증·권한·만료 점검)**, GET /v2/snapshot, GET /v2/changes?after=0, POST /v2/changes.
- `/v2/session`은 디바이스 전용 토큰만 허용하며 계정 UUID·기기 UUID·권한·토큰 만료 시각만 돌려줍니다. 토큰 본문/토큰 해시/계정 라벨은 응답하지 않습니다. `/v2/health`는 공개 헬스 체크이므로 로그인 성공의 증거로 사용하지 마세요.
- 이전 단일 사용자 서버의 revision/409 충돌/원자성/idempotency를 계정별 데이터에 재사용.

## 로컬 개발용 운영 절차

Python 3.12+ 필요. 각 명령은 프로젝트 루트에서 개발 서버 소유자가 직접 실행합니다. 사용자 개인 학습 정보나 실제 계정은 자동 생성되지 않습니다.

    python server/private_sync.py create-account --label personal
    python server/private_sync.py issue-device --account YOUR_ACCOUNT_UUID --label windows --scope read-write --days 30
    python server/private_sync.py revoke-device --device YOUR_DEVICE_UUID
    python server/private_sync.py disable-account --account YOUR_ACCOUNT_UUID

Phase24 로컬 운영자용 명령 (아래 명령은 실제 학습 데이터를 업로드하지 않음):

    python server/private_sync.py issue-assistant --account YOUR_ACCOUNT_UUID --label chatgpt-canary --days 7
    python server/private_sync.py list-devices --account YOUR_ACCOUNT_UUID
    python server/private_sync.py rotate-device --device YOUR_DEVICE_UUID --days 30
    python server/private_sync.py revoke-device --device YOUR_DEVICE_UUID

- `issue-assistant`는 MCP 전용 읽기/계획 제안 토큰을 발급합니다. **ChatGPT 실제 연동 OAuth 토큰이나 사용자 로그인 자격증명은 아닙니다**.
- `rotate-device`를 실행하면 기존 토큰이 즉시 무효화됩니다. 새 토큰을 안전하게 보관한 뒤 기기 설정을 업데이트하세요. 기존 클라이언트는 재인증 전까지 연결이 끊깁니다.
- 이미 폐기·만료된 기기 또는 비활성화한 계정의 토큰은 로테이션으로 되살리지 않습니다. 새 기기를 명시적으로 발급하세요.
- 토큰은 채팅, GitHub, 로그, 스크린샷, 공유 문서에 붙여 넣지 마세요.

기본 인증 DB는 홈 디렉터리의 .studyone/accounts.sqlite3이며 사용자별 데이터는 .studyone/accounts/UUID.sqlite3입니다.
발급된 토큰은 한 번만 출력됩니다. 채팅, GitHub, 스크린샷에 공유하지 마세요.

공인 CA의 인증서 및 안전한 서버 보안 설정이 준비된 경우의 로컬 HTTPS 실행:

    python server/private_sync.py serve --cert /secure/fullchain.pem --key /secure/privkey.pem

기본 주소는 127.0.0.1:9443. 원격 바인딩에는 별도 명시 허용 및 네트워크 보안 정책이 필요합니다.
코드를 만들었다고 실제 공개 HTTPS 서비스가 배포되거나 도메인·인증서가 생성된 것은 아닙니다.

## OAuth 연결 준비 사항 및 오프라인 사전 점검

제3자 인증 제공자(Azure AD, Auth0, Keycloak 등) 도입 전에 **로그인/동의 페이지가 갖춰졌는지, 토큰의 사용 대상이 MCP 서버인지, 실제 JWT 서명·issuer·audience·scope를 확인할 수 있는지**를 검증해야 합니다.

운영자가 별도로 획득한 공개 OIDC/OAuth 메타데이터를 JSON 파일로 저장한 후 아래의 오프라인 *사전 점검*을 실행할 수 있습니다.

    python -m server.oauth_preflight \
      --issuer https://identity.example.org/tenant \
      --resource https://studyone.example.org/mcp \
      --metadata /path/to/provider-metadata.json

이는 **공개 HTTPS URL, 정확한 issuer, OAuth authorization-code+PKCE S256, refresh token, offline_access 지원의 형식 검사만** 수행합니다. 토큰을 발급하거나 네트워크에 접근하지 않으며 공개 MCP 서버를 시작하지도 않습니다. 성공하더라도 OAuth 인증이 구현·연결·검증된 것은 아닙니다. 예시 주소는 실제 도메인이 아닙니다.

MCP 인증 사양과 RFC 9728에 맞게 향후 구현할 때에는 올바른 보호 리소스 메타데이터, `WWW-Authenticate` 챌린지, 외부 인증 서버의 코드 교환 및 사용자 승인, 권한 분리, 토큰 갱신·폐기 등을 추가하고 실제 ChatGPT 워크스페이스 연결 및 사용자별 데이터 격리를 검증해야 합니다.

## 배포 차단 조건

- 실제 사용자 본인 인증/계정 복구/토큰 로테이션 UX, 분실 대응, 인증 권한 관리
- Windows DPAPI, iOS Keychain, Android Keystore 등 안전한 클라이언트 비밀 저장
- TLS 인증서 자동 갱신, 인증/요청 속도 제한, DDoS 대응, 감사/운영 지표 및 정기 백업
- 실기기 클라이언트의 v2 API, 서버 변경사항 로컬 적용, 오프라인 큐, 충돌 UI
- 실제 개인정보 처리·계정 보안 심사, 복원·재해 복구 실험, 앱 릴리즈 전 데이터 검증
- **실제 OAuth 2.1/OpenID Connect 로그인, PKCE, 승인 화면, 토큰 발급·갱신 및 MCP 보호 리소스 메타데이터(RFC 9728)는 미구현**입니다. 현재 `issue-assistant`는 운영자 수동 발급용 Canary MCP 토큰이며 OAuth라고 부르지 않습니다.
- 외부 OpenID Connect/OAuth 제공자를 사용할 경우 발급자·수신자(audience)·만료·권한·사용자 소유 관계 검증 및 `/.well-known/oauth-protected-resource`와 WWW-Authenticate 인증 방식 구현이 선행되어야 합니다.
- MCP 연결 공개 전 사용자별 동의, 본인 인증/계정 복구, 최소 권한 정책, 토큰 갱신·폐기 및 실제 ChatGPT 연결 테스트가 필요합니다.
- UI/UX 및 기기별 실기기 테스트, 기존 버전 호환 마이그레이션

현재 구현은 서버 측 계정 격리를 검증하는 Canary 기반이며 Android·iOS·Windows 자동 동기화 완료 상태가 아닙니다. 기존 앱/기기 데이터와 GitHub 공개 Beta 릴리즈는 보존합니다.
