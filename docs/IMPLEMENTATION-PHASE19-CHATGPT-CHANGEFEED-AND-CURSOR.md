# StudyOne 19단계 — ChatGPT 안전 변경 이력 조회 및 기준 revision 계획 승인

## 범위와 현재 상태

이 구현은 **백엔드/인증된 MCP 프로토타입**입니다. 아직 실제 ChatGPT 대화와 StudyOne 스마트폰의 실시간 데이터가 연결된 것이 아닙니다. 정상 사용자 계정이 있는 공용 HTTPS 서버, OAuth 2.1 연결, ChatGPT 앱/커넥터 등록, 모바일 실사용 동기화가 별도로 필요합니다.

기존 Stable/Beta 릴리즈와 서명된 StudyOne Canary.6 `com.studyone.app` 앱/학습 데이터는 변경하지 않습니다.

### 새 MCP 도구 — `studyone_read_changes`

```json
{
  "jsonrpc": "2.0",
  "id": 20,
  "method": "tools/call",
  "params": {
    "name": "studyone_read_changes",
    "arguments": {"after": 0, "limit": 100}
  }
}
```

- `after`: 마지막으로 사용자가 확인한 서버 cursor, 0 이상 정수.
- `limit`: 1~100, 기본 100. 서버는 `head`, `nextCursor`, `hasMore`, `changes`를 반환합니다. 새 변경을 모두 확인하려면 `hasMore`가 false가 될 때까지 다음 cursor로 조회합니다.
- 레코드의 `deleted` tombstone을 포함하므로 완료/삭제를 임의 추정하지 않습니다.
- 로그인한 **해당 계정의 서버 변경 이력**만 제공됩니다. 서버에 전송하지 않은 Android 로컬 데이터는 보이지 않습니다. 이를 '자동으로 인지했다'고 주장해서는 안 됩니다.
- 데이터 조회용 read-only 토큰으로 접근 가능. 토큰 폐기/만료·계정 분리·TLS 운영 요건은 기존 private sync 구현에 따릅니다.

### 변경된 MCP 도구 — `studyone_propose_study_plan`

```json
{
  "jsonrpc": "2.0",
  "id": 21,
  "method": "tools/call",
  "params": {
    "name": "studyone_propose_study_plan",
    "arguments": {
      "requestId": "561a59a0-dd5d-49de-80c5-3160f4b7682c",
      "baseCursor": 0,
      "title": "오늘 학습 계획",
      "tasks": [{
        "type": "숙제 항목",
        "subject": "과학",
        "title": "오투 7-2 오답 확인",
        "dueDate": "2026-10-23",
        "importance": 2
      }]
    }
  }
}
```

- `baseCursor`는 반드시 **계정의 최신 `studyone_read_snapshot` 결과 cursor**를 사용합니다. `false`나 문자열 등은 거부합니다.
- 서버 커서가 초안 생성 시 달라졌다면 `STALE_PLAN_CURSOR`로 실패하며, 초안은 만들어지지 않습니다.
- 생성 후 앱 사용자가 일정·진도를 수정한 경우, **승인 시점**에 다시 커서를 검증합니다. 바뀌었다면 `STALE_PLAN_CURSOR`로 실패, 기존 앱 데이터를 덮어쓰지 않습니다.
- 승인 전 초안은 활성 일정 변경이 아닙니다. 승인에는 앱의 read-write 장치 토큰이 필요하며 MCP의 read-only 토큰으로는 승인할 수 없습니다.
- 요청 ID UUID를 재사용한 동일 페이로드는 멱등, 다른 페이로드는 오류 처리. 7일 초안 만료, 30개 일정 한도는 그대로입니다.
- 기존 `POST /v2/proposals`를 쓰는 클라이언트의 오래된 초안 구조는 계속 지원합니다. 새 cursor 필드는 선택적으로 확장되고, 기존 DB는 `base_cursor`를 삭제 없이 이관합니다. 그러나 **MCP 호출에는 필수**로 강제합니다.
- 승인한 뒤 Android 기기에 자동 저장되는 것은 아닙니다. 서버와 단말의 실제 동기화가 별도로 필요합니다.

## 검증과 운영 경계

`python -m unittest discover -s server/tests -p 'test_*.py' -v`

테스트는 변경 이력 pagination 및 tombstone, 계정 분리, 잘못된 인수 거부, 초안 전과 승인 전의 stale cursor 감지, 승인 시 원자적 반영, 읽기 전용 토큰의 직접 수정 차단을 포함합니다. 실제 API 키, 계정, 학습 데이터, HTTPS 인증서를 CI에 사용하지 않습니다.

## 실사용 연결에 아직 필요한 것

1. 운영 HTTPS 서버와 계정 가입·복구, 장치별 인증키 및 토큰 갱신.
2. ChatGPT용 OAuth 기반 MCP 배포·등록 및 사용자의 명시적 연결 동의.
3. Android/iOS/Windows에서 신규 변경 사항 푸시 및 안전한 Pull 경로.
4. 실기기 백업/충돌 복구, 앱 측 초안 승인과 최소 권한 검증.
5. 네트워크·권한·로그 보안 감사. 개인 ChatGPT Plus 구독은 OpenAI API 사용 요금을 포함하지 않습니다.

'연결 완료'라고 표시하거나 앱을 삭제/초기화하지 마세요. 실제 동기화가 아직 배포되지 않았기 때문입니다.
