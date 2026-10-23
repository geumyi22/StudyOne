# StudyOne Sync Core — Canary developer preview

**상태: 첫 동기화 서버 구현/검증 단계. 실제 앱 기기 간 자동 동기화 서비스는 아직 아닙니다.**

## 이번에 구현한 기술 기반

- Python 3.12 표준 라이브러리 및 SQLite 기반 **단일 사용자(소유자 1명) 로컬 서버**.
- 기본 주소는 127.0.0.1:8765(루프백만 허용). **인터넷 노출/포트포워딩/공인 IP 직접 바인딩 금지**.
- 최소 32자 이상의 로컬 비밀 토큰을 Bearer 헤더에 전달해야 읽기·쓰기가 가능.
- 과제(task)와 교재 진도(progress)의 기존 Android·iOS·Windows JSON 필드를 그대로 받음. 예상치 못한 개인정보/API 키 필드는 업로드 차단.
- 서버 부여 revision/updatedAt, 조건부 수정(baseRevision), UUID 요청 ID 통한 **중복 요청 방지**.
- SQLite 트랜잭션으로 일괄 요청을 전부 성공시키거나 충돌 시 전부 거부. 이전 클라이언트의 수정 덮어쓰기 방지.
- 삭제는 tombstone 이벤트로 보존. 변경 로그 cursor를 이용한 누적 변경 조회/페이지 처리 제공.
- 서버 DB 파일에 암호화는 아직 없으며 OS 사용자별 파일 접근권한으로만 보호(가능하면 0600). 컴퓨터/백업 암호화는 따로 필요.

## 로컬 개발 테스트

Windows PowerShell 또는 Linux/macOS 터미널에서 Python 3.12 이상을 준비합니다.

1. 무작위 테스트용 토큰 생성(사용자 개인 계정 토큰을 GitHub/채팅에 공유하지 마세요):

       python -c "import secrets; print(secrets.token_urlsafe(48))"

2. 해당 값을 로컬 환경 변수 STUDYONE_SYNC_TOKEN에 설정합니다. 환경 변수 값은 화면 공유나 저장소에 포함하지 마세요.
3. 프로젝트 루트에서 로컬 서버 실행:

       python server/studyone_sync.py --host 127.0.0.1 --port 8765

4. 공개 데이터 없이 유닛·HTTP 테스트 실행:

       python -m unittest discover -s server/tests -v

서버 기본 DB 경로: 현재 사용자 홈 디렉터리의 .studyone/sync.sqlite3.

**앱에서 비밀번호나 학교 데이터를 자동 수집하지 않으며, 서버 실행만으로 기존 모바일/PC 앱 데이터가 전송되지 않습니다.**

## 프로토콜 (개발 초안, 실제 호스팅 전 변경 가능)

모든 요청: Authorization 헤더에 Bearer 토큰. JSON POST는 Content-Type: application/json.

| 메서드 | 경로 | 의미 |
| --- | --- | --- |
| GET | /v1/snapshot | 현재 삭제되지 않은 task/progress 목록과 기준 cursor 읽기 |
| GET | /v1/changes?after=0&limit=200 | cursor 이후의 변경 이력과 삭제 tombstone 조회(1~500개) |
| POST | /v1/changes | 최대 50개 일괄 수정, expected revision 검증 및 중복요청 차단 |

POST body 형식(합성 테스트 값만):
    
    {
      "requestId": "461569c4-8a17-4ae6-bfc4-cd6b349f6000",
      "mutations": [
        {
          "kind": "task",
          "id": "1234",
          "baseRevision": 0,
          "deleted": false,
          "payload": {
            "id": 1234,
            "type": "숙제 항목",
            "subject": "과학",
            "title": "진도 확인",
            "dueDate": "2026-10-22",
            "importance": 2,
            "completed": false
          }
        }
      ]
    }

응답의 cursor/change_log를 클라이언트가 저장해야 합니다. 동일 requestId 재시도는 **동일 요청 내용**에 대해서만 idempotent입니다. baseRevision=0은 신규 생성만 허용합니다. 수정/삭제에는 서버에서 확인한 현재 revision이 필요하고, 충돌 시 HTTP 409 / REVISION_CONFLICT로 실패합니다. 이 경우 새 snapshot/changes 조회 후 사용자가 결정해야 합니다. 서버는 마지막 쓰기를 임의로 승리시키지 않습니다.

진도(progress)는 모바일과 동일한 8개 필드(id/subject/material/unit/status/inExamScope/revision/updatedAt)를 갖습니다. revision 및 updatedAt은 서버에서 다시 지정합니다.

## 릴리즈/보안 경계

- 임의 공인 IP 접속을 위한 0.0.0.0 바인딩은 코드 수준에서 차단합니다.
- 현재 프로토콜은 **루프백 개발용 HTTP**입니다. 다른 기기·클라우드로 확대하려면 HTTPS/TLS, 계정별 인증 및 토큰 로테이션, 데이터 분리, 레이트 리밋, 운영 백업 및 실제 보안 심사가 필요합니다.
- 유효한 API 토큰을 몰래 앱에 하드코딩하거나 GitHub에 커밋하지 마세요.
- 실제 Android/iOS/Windows 앱과 연동하는 **클라이언트 및 인증 설정 UI는 미구현**입니다.
- 실사용자 데이터 import/migration, 자동 동기화, ChatGPT MCP 읽기/쓰기 도구는 아직 없습니다.
- 백엔드 테스트 성공을 3개 플랫폼의 실기기 자동 동기화 성공으로 해석하면 안 됩니다.
- 사용자 비밀·NEIS API 키·OpenAI API 키·개인 일정은 GitHub CI fixture에 사용하지 않습니다.

관련: ../docs/NEXT-STABLE-ROADMAP.md · ../docs/CHATGPT-SYNC-DESIGN.md.
