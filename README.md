# StudyOne

StudyOne은 시간표, 급식, 과제, 수행평가, 시험과 공부 우선순위를 한 앱에서 관리하는 Android 학생 생활 앱입니다.

## v2 현재 구현
- Android 네이티브 앱 — WebView 미사용
- Android 17 / API 37 대상
- NEIS 학교 검색 및 정확한 학교 선택
- 학교코드 + 학년 + 반을 다시 검증한 뒤 시간표 표시
- 실제 NEIS 중식 데이터 및 학교코드 검증
- API 실패 시 가짜 시간표/급식 표시 금지
- 마지막 정상 데이터 캐시 유지
- 과제 / 수행평가 / 시험 로컬 관리
- 마감일 + 중요도 + 일정 종류 기반 추천
- 책 + 체크 모티프 StudyOne 아이콘

## 빌드
- Android Gradle Plugin 9.1.1
- Gradle 9.3.1
- JDK 17
- compileSdk / targetSdk 37
- minSdk 26

main에 push되면 GitHub Actions의 **Android APK** 워크플로가 debug APK를 실제 빌드하고 ZIP 무결성까지 검사합니다.

## NEIS
앱 설정에서 개인 NEIS Open API 인증키를 입력합니다. 인증키는 Android 기기 로컬 SharedPreferences에만 저장되며 저장소에 커밋하지 않습니다.

## 버전
2.0.0-dev.1
