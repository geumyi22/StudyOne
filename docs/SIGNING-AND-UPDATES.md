# StudyOne 릴리즈 서명 유지 가이드

StudyOne의 설치 및 향후 업데이트 호환성은 **매번 동일한 Android signing certificate**에 달려 있습니다.

1. 서명키 StudyOne-release.jks를 안전하게 보관합니다. 공개 GitHub 저장소에 업로드하지 마세요.
2. `Settings → Secrets and variables → Actions` 에 다음 네 개의 Secrets를 등록합니다.
   - `STUDYONE_KEYSTORE_BASE64`: JKS 바이너리의 Base64
   - `STUDYONE_STORE_PASSWORD`: keystore 비밀번호
   - `STUDYONE_KEY_ALIAS`: studyone-release
   - `STUDYONE_KEY_PASSWORD`: key 비밀번호
3. 대화에서 전달한 비공개 서명키 설정 파일에 제공된 PowerShell 설정 스크립트를 사용할 수 있습니다.
4. `Actions → Signed In-App Update Release → Run workflow` 에서 confirmation에 `RELEASE`를 입력합니다.
5. 워크플로는 실제 JKS SHA256 cert fingerprint `3F:95:E9:AA:EC:B3:2A:E6:6C:86:DB:D6:79:5B:0A:49:11:8F:8D:AC:F5:15:F7:FB:E3:17:95:97:0B:16:81:ED`를 검사합니다.
6. Android 서명키가 달라지면 기존 설치판에 덮어쓰기 업데이트가 불가능합니다. 반드시 영구 보관하세요.

`update.json`만 수정해서는 업데이트 불가능합니다. 새 버전은 더 높은 `versionCode`, 같은 `applicationId`, 동일 서명키로 빌드하고, 릴리즈 자산 `StudyOne-vX.apk`와 `studyone-update.json`에 올려야 합니다.

**GSCM과의 공통점:** 앱 내부의 업데이트 탐색, 다운로드 검증, 배포 채널 관리.

**Android와 다른 점:** 무인/무음 설치 불가. Android 시스템 설치 확인 과정이 반드시 필요합니다.

**제외사항:** 외부 자동 동기화, Play Store 자동 업데이트, 스토어 게시 인증은 별도의 작업입니다.
