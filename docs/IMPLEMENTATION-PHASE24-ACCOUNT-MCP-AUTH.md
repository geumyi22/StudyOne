# StudyOne Phase24 — 계정 보안·MCP 권한 분리 (Canary)

> 이 변경은 **실제 공개 ChatGPT 앱 연결이나 OAuth 로그인을 완성하는 작업이 아닙니다.** 서버 개발용 보안 기반으로, 운영자 수동 발급 토큰·기존 TLS 서버를 강화합니다. 실제 학습 데이터와 GitHub 릴리즈는 그대로 유지합니다.

## 구현된 기능

1. **MCP 전용 토큰**: 운영자 CLI `issue-assistant`로 `audience=mcp`, `scope=read`인 짧은 기한(기본 7일) 토큰을 만들 수 있습니다. `POST /mcp`에서 읽기·공부 계획 **제안** 기능만 사용합니다. `/v2`의 전체 자료/변경/승인 API는 거부됩니다. AI가 작성한 계획은 여전히 **인증된 기기에서 별도로 승인**해야 활성 일정에 반영됩니다.
2. **기기 토큰 교체**: `rotate-device`가 난수 토큰을 새로 만들고 SQLite 트랜잭션 내 해시를 교체합니다. 예전 토큰은 즉시 만료됩니다. 실제 토큰은 1회 CLI 출력만 허용하고 DB에는 SHA-256 해시만 남깁니다. 폐기·만료된 기기나 비활성 계정은 자동 복구하지 않습니다.
3. **안전한 장치 목록**: `list-devices`는 관리자용 장치 ID·라벨·권한·audience·만료시각·폐기 상태만 출력합니다. 토큰과 토큰 해시는 포함하지 않습니다.
4. **기존 DB 호환**: 과거 `devices` 테이블에 `audience`가 없어도 안전하게 `device`로 마이그레이션하고 기존 사용자 기기 토큰의 권한을 유지합니다. `audience`가 MCP 전용인 토큰은 일반 동기화 요청에 사용할 수 없습니다.
5. **인증된 연결 확인**: `GET /v2/session`은 기기 토큰의 권한·만료·계정/기기 UUID·서버 시간을 반환하되 학습 기록·자격증명·개인 라벨을 반환하지 않습니다. 공개 `GET /v2/health`는 로그인 성공을 증명하지 않습니다.
6. **OAuth 정적 사전 점검**: `python -m server.oauth_preflight`는 외부 인증 제공자의 공개 JSON 메타데이터에 대해 HTTPS, issuer, PKCE S256, code+refresh grants, offline_access 등을 오프라인 검증합니다. 검증 통과는 OAuth 배포나 로그인 완료를 뜻하지 않습니다.

## 합성 E2E 및 CI

- 옛 SQLite 스키마에서 기존 토큰을 유지하는 마이그레이션 시험
- HTTP/MCP 계정 간 격리, 일반 API에 대한 assistant 토큰 접근 차단, AI 제안만 생성 후 기기 승인
- 실제 TLS 서버 테스트에서 MCP-only 토큰 교체·폐기·권한 분리 및 HSTS/no-store 검증
- 운영자 CLI 전체 명령(create/issue/list/rotate/revoke) 통합 테스트
- OAuth 가짜 HTTPS 주소, 사용자정보 포함 URL, 사설 IP, issuer 불일치 및 PKCE/refresh 메타데이터 누락 거부
- 기존 SQLite 백업/복구, 서버 제안 원자성, 앱 CI는 원래 워크플로에서 계속 검사

## 실제 서비스 연결 전 필수 작업

- 공개 운영 HTTPS 도메인/TLS/갱신, 호스팅 및 운영자·비밀키 관리
- 제3자 OAuth/OIDC 인증 제공자 또는 안전한 독립 인증 서비스 준비, **개별 사용자 본인 확인 및 동의**
- MCP resource server의 `WWW-Authenticate` 및 RFC9728 보호 리소스 메타데이터
- access token의 발급자·서명(JWKS)/내성 검사·사용 대상(audience)·scope·만료 검증, PKCE state, refresh/revoke, 사용자 계정 매핑
- OpenAI/ChatGPT 계정과 실제 StudyOne의 연결 등록, 사용자별 동의 및 격리 실기기 E2E
- 토큰 발급·교체 UI, Windows DPAPI / Android Keystore / iOS Keychain·토큰 보안
- 요청 속도 제한, 서버 모니터링·감사 기록 및 장애 대응, 침투 테스트, 계정 복구/기기 분실
- 실사용 Cloud 동기화의 충돌 처리, Offline 큐와 데이터 복원

**보안 경계:** 현재 `issue-assistant`가 발급하는 것은 개발용 서버 관리자가 만든 bearer credential입니다. **OAuth access/refresh token이 아니며, ChatGPT가 직접 앱에 연결된 것이 아닙니다.** 기존 `device` audience 읽기 토큰의 MCP 호환 동작은 보존하지만, 새 AI 연결에는 반드시 `mcp` audience를 사용하세요. Android/iOS/Windows 정식 배포와 사용자 기기 업데이트는 수행하지 않았습니다.
