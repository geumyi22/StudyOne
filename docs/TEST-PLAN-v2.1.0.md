# StudyOne v2.1.0-beta.1 검증 항목

## CI가 자동 확인하는 부분
1. Android 17 SDK/AGP로 apk 컴파일, Lint
2. ZIP 무결성, zipalign, APK v2 서명, package / targetSDK 확인
3. Android 에뮬레이터 실제 설치/실행/크래시 로그 검사

## 수동 확인이 필요한 부분
- 실제 학교 시간표와 NEIS 응답 일치 여부 (학교·학년·반·날짜)
- 실제 급식과 NEIS 중식 일치 여부
- 빈 학사일정/네트워크 오류 상황에서 10분 백오프 적용
- 과제 추가 → 수정 → 완료/미완료 → 삭제
- 날짜 선택, 시험 D-day, 내일 준비물
- 이번 주/다음 주 전환 시 이전 캐시 미노출
- Google Play 업데이트는 미지원. GitHub Actions 디버그 APK는 빌드마다 서명이 달라질 수 있어 기존 APK 위 업데이트 보장 안 됨.

## 보안
인증키는 APK/CI에 직접 포함하지 않음.
앱 설정에 입력한 인증키를 GitHub Issues나 로그로 공유하지 말 것.
