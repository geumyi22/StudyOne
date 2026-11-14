# StudyOne v4 P5 — 실기기·운영 QA 실행 계획 (출시 전)

> **상태: 기존 설치본의 수동 읽기 전용 화면 기본 스모크 5/5 사용자 보고 PASS. v4 후보 P5 출시 게이트는 미검증·미합격.** 기준: 2026-11-15. 사용자 학습 기록과 기존 설치본을 지우거나 GitHub/채팅에 업로드하지 않습니다. `main` 또는 공개 Beta에서 개발용 패키지로 덮어쓰기를 시도하지 마세요.

- 제품 범위: **Android + iOS + Windows 통합 v4** (사용자 선택)
- 운영 방향: **월 0원 무료 범위만 허용**. 별도 클라우드 + 외부 OAuth 제공자 검토 중 (가입·카드 인증·도메인·유료 서비스·데이터 이전 모두 별도 승인)
- 실기기 QA 환경(사용자 직접 확인): **Galaxy S24 Ultra / Android 17 / One UI 9**, **iPhone**, **Mac/Xcode 접근 불가**, **Apple 무료 Personal Team**. iPhone 모델·iOS 버전·실제 설치 결과 및 S24 Ultra 설치본의 서명은 미확인. S24 Ultra의 Android 기본 사용자 `0`에서 정식 패키지 `com.studyone.app`의 `versionCode:30106`만 ADB 조회로 확인. Windows 11 테스트 환경 기록은 있으나 QA 시 재확인
- 개발 기준: [Draft #60](https://github.com/geumyi22/StudyOne/pull/60) → [#61](https://github.com/geumyi22/StudyOne/pull/61) → [#62](https://github.com/geumyi22/StudyOne/pull/62), 최종 검증 대상 SHA는 실행 직전에 재확인
- 추적: [P5 이슈 #63](https://github.com/geumyi22/StudyOne/issues/63), [출하 수용 기준](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/docs/RELEASE-4.0.0-ACCEPTANCE.md)

## 임시 CI 테스트 산출물 (서명 Stable 아님)

아래는 2026-11-15 현재 **만료 전인 GitHub Actions 보관 파일**입니다. GitHub 로그인 상태에서 해당 실행 페이지의 Artifacts에서 받으며, 14일 보관 정책에 따라 추후 삭제될 수 있습니다. **최종 v4 통합 출시 패키지가 아닙니다.**

| 테스트 용도 | Actions 실행 | 파일 특성 / 제한 |
| --- | --- | --- |
| Android UI 기능 체험 | [#62 이전 HEAD Android Actions #43603434709](https://github.com/geumyi22/StudyOne/actions/runs/43603434709) | `StudyOne-v2-debug-apk` 아티팩트 이름(레거시 명칭). 별도 `com.studyone.app.canary7` 디버그 앱. **공식 Android 앱에 업그레이드 설치하지 말 것** |
| iOS 코드·시뮬레이터 검증 자료 | [#62 이전 HEAD iOS Actions #43603434760](https://github.com/geumyi22/StudyOne/actions/runs/43603434760) | `StudyOne-iOS-unsigned`, **기기 직접 설치 불가**. Xcode 서명 및 기기 별도 빌드 필요 |
| Windows 격리 테스트 | [#61 HEAD Windows Actions #43406023882](https://github.com/geumyi22/StudyOne/actions/runs/43406023882) | `StudyOne-Windows-Canary-win-x64-unsigned`. #62와 다른 SHA의 **미서명 EXE**, 별도 가상머신에서만 우선 사용. 설치 관리자·업그레이드 테스트 대체 불가 |

개인 학습 자료가 있는 기존 앱의 삭제/대체·테스트 바이너리 덮어쓰기는 금지합니다. 수동 시험에서 기능이 동작해도 정식 서명·기존 사용자 원본 보존 게이트는 별개입니다.

## P5 무료 클라우드 후보 — 비용 0원 우선, 실제 운영 미승인

2026-11-15 공식 문서 확인. **현재 서버는 Python + 계정별 SQLite 파일을 로컬 디스크에 저장**하므로 저장소 지속성이 최우선. 무료 개발 환경이 최종 Stable 운영을 보장하지는 않습니다.

- **Render Free 제외:** 15분 유휴 시 중단되며 SQLite 로컬 파일은 재배포·재시작/중단 시 사라짐. [Render 무료 운영 제한](https://render.com/docs/free)
- **Oracle Always Free 조건부 후보:** VM + 무료 블록 볼륨은 기존 SQLite 서버를 상대적으로 적게 변경하여 테스트할 수 있으나, 무료 자원 회수/계정 휴면·용량 제약, 계정/카드 본인 인증, 시스템 운영·방화벽·백업 관리 부담이 있음. **가입·인스턴스 생성·카드 입력은 사용자 승인 전 진행하지 않음**. [무료 자원 및 제약](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), [한국어 가입 FAQ](https://www.oracle.com/kr/cloud/free/faq/)
- **Supabase Free 추가 개발 필요:** PostgreSQL 기반으로 현재 서버 저장 구조를 재설계해야 하며, 적은 활동으로 무료 프로젝트가 일시 중단될 수 있음. [프로젝트 일시 중단 정책](https://supabase.com/docs/guides/platform/free-project-pausing)
- 운영 안정성, 백업 보유 및 개인정보 보호 조건을 충족하지 못하면 **무료만을 이유로 실제 학습 기록을 서버에 이전하지 않음**. 처음에는 합성 데이터만 사용.

## Galaxy S24 Ultra / Android 17 실기기 읽기 전용 사전 점검 (개발 PR #62)

사용자가 **Android 17 / One UI 9**를 보고했습니다. 공식 Android 17의 API level은 **37**입니다. 다음 도구는 테스트 기기 OS·모델·StudyOne 정식/Canary 설치 **메타데이터만 읽고**, 앱 설치·업데이트·삭제·백업·개인 파일 전송을 하지 않습니다. ADB 연결 승인으로 디버깅 통신은 가능해지므로 본인 PC·본인 휴대전화에서만 실행하고, 테스트 후 **USB 디버깅을 끄거나 승인된 PC 권한을 해제**하세요.

- 선행 조건: Windows PC에 공식 [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), 데이터 통신 가능한 USB 케이블, Galaxy 설정에서 개발자 옵션/USB 디버깅 사용 및 본인 PC 연결 승인. [ADB 공식 안내](https://developer.android.com/tools/adb)
- 실행 (GitHub의 해당 PR 소스를 사용자 PC에 받은 후, 루트 폴더에서):

```powershell
py -3 scripts/p5_android_device_preflight.py
```

- 기기가 여러 개 연결된 경우 `--serial`로 지정할 수 있지만 **전체 휴대전화 일련번호를 GitHub/채팅에 공유하지 마세요**. 출력에도 일련번호를 포함하지 않습니다.
- `READ_ONLY_DEVICE_METADATA_INSPECTED`: **S24 Ultra·Android 17(API 37)·설치된 StudyOne 패키지/버전코드만 조회**했다는 의미입니다. `officialInstalledVersionCode`가 `null`이면 정식 패키지를 확인하지 못한 것입니다. `CANARY_ONLY`는 별도 디버그 앱이므로 정식 설치본과 혼동하지 마세요.
- `oneUiRawProperty`는 삼성 시스템 속성의 원본 값으로, **One UI 9 인증이 아닙니다**. 사용자가 휴대전화 설정에서 직접 확인한 9 버전을 현재 기준으로 유지합니다.
- **미검증:** 기존 앱 설치 서명 인증서, 학습 기록 4종, 백업/복구, 실제 v4 서명 후보 덮어쓰기 및 UI E2E. 이들은 이슈 #63의 Android 항목이 모두 체크되기 전까지 미완료입니다.

[스크립트 원문](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/scripts/p5_android_device_preflight.py) · [모의 테스트 9개](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/scripts/tests/test_p5_android_device_preflight.py) · [Android 17 공식 발표](https://developer.android.com/blog/posts/android-17-is-here)

### 2026-11-15 실제 S24 Ultra 연결 중 보호된 사용자 영역 권한 오류 — 수정 반영

사용자 본인 ADB 출력에서 `samsung / SM-S928N / Android 17 / API 37 / ro.build.version.oneui=90000` 확인. **정식 StudyOne `versionCode:30106`이 출력됐지만** 동일 명령이 별도 보호 사용자 영역 조회의 `SecurityException`을 출력해 조회 전체는 실패했습니다. 기기 일련번호/다른 앱/실제 학습 원본은 기록하지 않습니다.

따라서 공식 [ADB 패키지 목록의 `--user` 옵션](https://developer.android.com/tools/adb)을 사용해 **기본 사용자 영역 0만** 읽도록 후속 명령을 한정했습니다.

```powershell
.\adb.exe shell pm list packages --user 0 --show-versioncode com.studyone.app
```

[수정 커밋 `3a0a3c6`](https://github.com/geumyi22/StudyOne/commit/3a0a3c6f26c776961c1bde53191893ad80bd9883)은 같은 한정을 Python 기기 점검 스크립트에도 추가했습니다. 출력 일부가 성공했더라도 `SecurityException`을 포함하면 실패 처리하는 합성 회귀 테스트 포함 **10개 PASS**, 이전 APK 검사 7개 및 v4 계약 4개 PASS. 이후 사용자 PC에서 기본 사용자 `0`으로 재실행한 결과 `package:com.studyone.app versionCode:30106`이 추가 오류 없이 확인됐습니다. **기본 사용자 0 패키지·버전 조회는 PASS**이나 설치된 앱 서명·학습 데이터·UI 기능·업데이트 검증은 아직 미실시입니다. [동일 개발 SHA의 GitHub CI 7개](https://github.com/geumyi22/StudyOne/commit/3a0a3c6f26c776961c1bde53191893ad80bd9883/checks)는 모두 완료·성공했습니다. 권한 부여·타 프로필 우회·앱 삭제/설치는 시도하지 않습니다.

### 2026-11-15 기존 설치본 화면 기본 스모크 5/5 PASS (사용자 직접 보고)

**대상:** 기존 Galaxy S24 Ultra(Android 17/One UI 9)의 `com.studyone.app`, 기본 사용자 0 ADB 확인 `versionCode:30106`. 이는 **기존 설치본의 화면 읽기 전용 수동 확인**이며, v4 개발 후보·새 버전 업데이트의 실기기 테스트가 아닙니다. 사용자 진술에 따르면 앱 업데이트·삭제·데이터 수정 없이 진행했습니다.

- [x] 기존 앱 실행 — 사용자 보고 PASS
- [x] 학습 화면 전환 — 사용자 보고 PASS
- [x] 기존 기록 표시 — 사용자 보고 PASS (원본 정합성 비교 아님)
- [x] 화면 표시 — 사용자 보고 PASS (대상 화면 외 전체 접근성 검증 아님)
- [x] 종료 후 재실행 — 사용자 보고 PASS

이 5개는 **별도 간이 스모크 기록**입니다. 기존 P5 A4(모든 과제·시험·오답·AI·NEIS·알림·위젯·체크/이월 동작)·A7(4종 데이터 무변경·오프라인 유지 등) 및 서명/원본 복구/업데이트 항목의 체크박스는 계속 미완료입니다. 실데이터·화면 이미지·일련번호는 공유/기록하지 않음. [GitHub 추적 #63](https://github.com/geumyi22/StudyOne/issues/63).

### 2026-11-15 Canary.7 별도 실기기 설치 승인 — 설치 실행은 아직 대기

**사용자 승인:** S24 Ultra에 **별도 Canary 테스트 앱 설치**를 진행해도 된다고 답변함. 허용 범위는 `com.studyone.app.canary7` 디버그 앱의 분리 설치와 합성 테스트만 해당. 기존 `com.studyone.app` 삭제·덮어쓰기·기존 기록 변경, 학습 원본 이전·백업/복원, 유료 서비스·클라우드 설정은 별도 승인 전 진행하지 않음.

- GitHub CI [Android 빌드 #43626023846](https://github.com/geumyi22/StudyOne/actions/runs/43626023846)의 개발 HEAD `3a0a3c6f26c776961c1bde53191893ad80bd9883`; 빌드와 에뮬레이터 smoke 모두 성공. [다운로드 artifact #14125729661](https://github.com/geumyi22/StudyOne/actions/runs/43626023846/artifacts/14125729661). 파일 이름은 `StudyOne-v2-debug-apk.zip`(레거시)이지만 실제 `aapt2 dump badging` CI 출력은 `com.studyone.app.canary7`/`versionCode=30107`/`versionName=3.1.0-canary.7-debug`/`StudyOne 개발판` 확인.
- artifact ZIP SHA-256: `c40837755be17421c0a3f3e033256492e8392a434d7b410e1146cb577f735c8c`; 내부 `app-debug.apk` SHA-256: `2aa2705a9e6716676a857f51ec5a9849fa6a5795fce3c8e72967b86fb7615a7e`. GitHub 빌드 로그에서 직접 확인했으며 사용자 PC에서 재계산해 일치해야만 설치.
- **사전 검증:** ADB 연결된 본인 단말 1대, `--user 0`의 공식 패키지 `com.studyone.app versionCode:30106` 확인. 동일 사용자 영역에 Canary 패키지가 이미 설치됐다면 **덮어쓰기 없이 중단**. ZIP·APK 해시 불일치, 예상 패키지 불일치, ADB 보안 오류가 나면 설치 금지.
- **설치 시:** 검증된 `app-debug.apk`만 `adb install`(**`-r` 금지**)로 새 설치. 사후 두 패키지가 동시에 각 버전코드 `30106`/`30107`로 표시되는지 확인 후, 개발판에는 **합성 데이터만 사용**. 설치·앱 실행/기능 수동 QA 결과는 아직 사용자 회신 없음. 기존 앱 5/5 기본 스모크 PASS와 구분.

## Android 4.0 서명 APK 읽기 전용 사전 검사 (개발 PR #62)

[v4 Android 사전 검사 스크립트](https://github.com/geumyi22/StudyOne/blob/feature/studyone-v4-p0-p4-20261114/scripts/p5_android_apk_preflight.py)는 공식 패키지 ID, 고정 공개 인증서 지문, Android v2 서명, 베타/Stable 버전쌍, 로컬 파일 해시, 지정한 설치본 버전보다 신규 코드가 큰지를 검사하며 **어떠한 APK도 설치/삭제/업로드하지 않습니다**.

PC에 Android SDK의 `aapt2`와 `apksigner`가 준비되고 진짜 4.0 후보 APK를 확보한 뒤, 사용자 본인의 기존 앱 버전코드를 확인하여 아래 `숫자`와 `해시`를 정확히 교체해야 합니다. 아직 배포 APK가 없어 **진짜 APK 검사는 미실시**입니다.

```bash
python3 scripts/p5_android_apk_preflight.py \
  --apk /path/to/StudyOne-v4-signed.apk \
  --installed-version-code 30001 \
  --expected-sha256 ACTUAL_VERIFIED_64_HEX_DIGEST
```

위 `30001`은 공개 v3 베타의 **과거 예시**입니다. **S24 Ultra의 현재 사용자 0 설치본은 `30106`으로 실제 확인**됐으므로, 해당 기기에 대한 향후 *읽기 전용* APK 후보 검사를 진행할 때는 `--installed-version-code 30106`을 사용해야 합니다(서명된 v4 후보 APK/해시 확보 전에는 검사 불가). 출력 `CANDIDATE_METADATA_VERIFIED_ONLY`는 **실기기 서명 확인, 기존 기록 백업, 인플레이스 업그레이드 또는 P5 합격을 뜻하지 않음**. 진짜 APK나 S24 Ultra를 점검한 증거는 아직 없습니다. 합성 APK 검증 7개·S24 Ultra 모의 ADB 검증 **10개**·버전 계약 4개 PASS. [개발 HEAD `0f55b92`의 비배포 계약 CI](https://github.com/geumyi22/StudyOne/actions/runs/43624198048)를 포함하여 [동일 SHA의 CI 7개 모두 성공](https://github.com/geumyi22/StudyOne/commit/0f55b923c0aca8e408a0105c024681efa0c71aae/checks)했으나 실제 기기 QA와는 구분합니다.

## 0. 실행 전 공통 조건

- [ ] 검증할 정확한 commit SHA, 앱 버전, Android package ID/iOS bundle ID/Windows 실행 파일 SHA-256을 기록
- [ ] 테스트용 기기/계정과 실제 사용 기기를 구분; 개발/디버그 패키지를 기존 공식 앱의 업그레이드로 오인하지 않음
- [ ] 모든 대상 기기에서 **앱 내부 내보내기 → 복사본을 사용자 관리 저장소에 이중 보관 → 파일 존재/무결성 확인**. 실제 개인 원본을 강제로 추출하지 않음
- [ ] `tasks / progress / details / wrongAnswers` 4종의 현재 데이터 존재·기록 수·완료 상태를 **기기 내부에서** 확인; 이 대화/GitHub에는 실제 내용·이름·계정·토큰을 적지 않음
- [ ] 복구 연습은 처음에 <b>합성 데이터가 있는 격리된 테스트 환경</b>에서 수행; 실제 원본 변경·복원은 사용자 별도 동의 후
- [ ] 실패 시 즉시 중단할 기준(서명 불일치, 데이터 개수/상태 감소, 복원 실패, 미확인 저장 위치, 계정 간 기록 노출)을 정하고 롤백/기존 설치 유지 경로 확보

기록 양식: `플랫폼 | 기기/OS | 이전 앱 버전 | 후보 SHA | 패키지/서명 또는 EXE 해시 | 시나리오 ID | PASS/FAIL/BLOCKED | 안전한 증거 링크/설명 | 날짜`. 개인 학습 데이터·API 키·토큰·민감한 화면 캡처를 증거로 업로드하지 마세요.

## 1. Android — 반드시 공식 서명·기존 데이터 보존 확인

**현재 한계:** PR #62의 CI debug 앱은 별도 `com.studyone.app.canary7` 테스트 패키지입니다. 공식 `com.studyone.app` 설치본의 데이터 유지 업그레이드 합격 근거가 아닙니다.

- [ ] A1. Android 기종·OS/SDK, 현재 공식 앱 package ID, 버전 코드, 설치 서명 인증서 지문을 기기에서 확인
- [ ] A2. 공식 서명 업그레이드 후보 APK의 패키지 ID·인증서 지문·증가한 versionCode가 기존 설치본과 정확히 맞는지 먼저 검증; 불일치 시 **설치 금지**
- [ ] A3. 기존 `tasks/progress/details/wrongAnswers` 내보내기·외부 보관·복구 확인 후, 별도 시험 기기/프로필에서 동일 서명 덮어쓰기와 재부팅 진행
- [ ] A4. 5분류 플래너, 오늘/내일/전체, 체크/이월, 과제·시험·수행·진도·오답·AI·NEIS·알림·위젯 실제 동작 확인
- [ ] A5. 범용 시험지(임의 과목/교재/단원/기간/제외일/회차) 미리보기는 읽기 전용인지, 신규 숙제는 별도 승인 없이는 추가되지 않는지 확인
- [ ] A6. 완료 회차 자동 채움은 동일한 UUID/과목/교재/회차 매핑에만 적용되는지, 누락·중복·변경된 원본에서는 저장 거절되는지 검증
- [ ] A7. 앱 종료·강제 중단·재시작·오프라인 전환 후 4종 데이터와 기존 완료 상태가 동일한지 확인. 실패 시 추가 수정 중단, 백업 사본 보존

## 2. iOS / iPadOS — 서명과 물리 iPhone 또는 iPad 테스트 선행

**현재 한계:** CI가 만든 `unsigned IPA`는 iPhone/iPad에 바로 설치할 수 있는 배포 앱이 아닙니다. Apple 서명/프로비저닝 방법 및 실제 iPhone/iPad 사용 가능 여부부터 확인합니다.

**Apple 계정 구분(2026-11-15 공식 문서 확인):** 무료 Apple Developer 등록만으로도 Mac의 Xcode에 **Personal Team**으로 로그인해 본인 iPhone의 개발 빌드를 시험할 수 있습니다. 다만 무료 개인 팀 프로비저닝은 7일 만료/재설치 제한이 있어 사용자에게 배포하는 정식 Stable과 다릅니다. TestFlight/App Store 배포에는 별도 Apple Developer Program 멤버십/배포 권한이 필요합니다. [Apple Developer 계정 개요](https://developer.apple.com/kr/help/account/basics/about-your-developer-account) · [Xcode 실기기 실행](https://developer.apple.com/documentation/xcode/building-and-running-an-app) · [개발자 모드](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)

**연령/배포 계정 조건:** Apple의 무료 개발자 등록은 대한민국에서 만 14세 이상 가능하지만, 유료 Apple Developer Program에 직접 가입하려면 해당 지역의 성년이어야 합니다. 따라서 본인 기기 무료 개발 테스트와 유료 프로그램/공개 배포 자격은 구분합니다. 법적 자격·팀 권한 확인 없이 가족/타인 계정 또는 서명 자격을 임의로 사용하지 않습니다. [무료 개발자 등록](https://developer.apple.com/register/) · [배포 프로그램 가입 조건](https://developer.apple.com/help/account/membership/program-enrollment/)

**2026-11-15 확인된 장애 조건:** Apple 무료 Personal Team이고 **사용 가능한 Mac/Xcode가 없습니다**. 따라서 현재는 Xcode를 통한 iPhone 개발용 서명·프로비저닝·실기기 설치가 불가능합니다. GitHub Actions의 macOS **unsigned iOS 빌드와 시뮬레이터 테스트**는 계속할 수 있지만, 개인 실기기 QA·TestFlight/App Store·Stable 설치를 통과한 것으로 판단하지 않습니다. 비공식 서명, 가족/타인의 계정 사용, 유료 개발자 가입은 임의 진행하지 않습니다. 처음에는 기존 앱과 분리된 합성 테스트 환경에서 진행하고, 동일 bundle ID 재설치를 통한 기존 원본 덮어쓰기·삭제는 보류합니다.

- [ ] I1. 실제 iPhone 또는 iPad 모델·iOS/iPadOS 버전·테스트 Apple 계정의 서명 권한·만료/갱신 방식을 준비. 기존 사용자 `com.studyone.ios` 데이터 보존 여부를 확인하지 않고 번들 ID/팀 ID를 변경하지 않음
- [ ] I2. 개발용 서명 빌드를 테스트용 기기에 별도로 설치하고 앱 첫 실행·재시작·재부팅 확인
- [ ] I3. 4종 합성 데이터 내보내기·가져오기·부분 실패·SHA-256 불일치 거부·복구 저널 중단 회복을 테스트 기기/시뮬레이터에서 검증
- [ ] I4. Android와 동일 범용 시험지 입력의 계산 결과·진도 UUID 연계/미리보기/별도 승인/중복 차단 점검
- [ ] I5. NEIS, 과제·시험·오답·AI·한글·다크 모드·접근성·오프라인·화면 회전/재개 등 실제 기능 확인
- [ ] I6. 실제 데이터/서명/기기 이전 관련 이슈를 해결할 때까지 iOS Stable 설치 가능으로 표기하지 않음

## 3. Windows — VM 스냅샷부터 검증

**현재 한계:** WPF .NET 8 앱은 미서명 `win-x64` 프리뷰입니다. 공식 설치 관리자 및 코드 서명/업그레이드 정책이 미확정입니다.

- [ ] W1. Windows 11 격리된 VM/테스트 사용자 프로필, 체크포인트와 복구 경로 마련
- [ ] W2. 기존 앱 설치본·저장 경로(예: `%LOCALAPPDATA%\StudyOne`, Canary 별도 저장소)·원본 4종을 먼저 확인하고 안전한 별도 복사본 보관
- [ ] W3. .NET 빌드·WPF 실행·창 크기/축소·키보드/화면 읽기·한글/다크 모드·일반 권한으로 시작 검증
- [ ] W4. 5분류 플래너, 일정 검색/필터, 범용 학습지 배분·미리보기·별도 승인, 기존 완료/미완료/미지 필드 보존 확인
- [ ] W5. 중복 실행·다른 세션/외부 편집기 파일 경쟁 상황에서 기존 기록을 잘못 덮어쓰지 않는지 확인
- [ ] W6. 설치·업그레이드·제거·재설치 동안 사용자 데이터/설정이 남는지, 롤백·오류 처리·배포 서명과 해시 검증
- [ ] W7. UI 조작 증거는 합성 테스트 과제만 사용. 사용자 실제 숙제·교재/시험 내용 스크린샷 공유 금지

## 4. 서버 · 3기기 · ChatGPT OAuth (별도 클라우드 스테이징)

- [ ] S1. 공급자·리전·유료/무료 한계·스토리지 지속성·백업·복원·호스팅 비용과 사용자의 명시 승인 확보
- [ ] S2. TLS 공개 도메인, HTTPS 리디렉션, 인증서 갱신, 보안 헤더, 감사/비밀 관리/속도 제한 검증
- [ ] S3. 외부 OAuth 제공자의 OIDC/OAuth 메타데이터, authorization code + PKCE S256, `resource` 대상 audience·scope, 토큰 갱신/철회 및 ChatGPT 클라이언트 등록 지원 검증
- [ ] S4. StudyOne의 read-only `studyone_read_snapshot`/변경 로그·`studyone_read_plan_status`와 승인형 제안, 별도의 앱 승인 전 원본 미변경을 실제 테스트 계정에서 확인
- [ ] S5. 권한 부족·다른 계정·만료·철회·로그아웃/연결 해제/CSRF·잘못된 JWT를 차단하고 기록 유출/정합성 손상 없음
- [ ] S6. Android/iOS/Windows **3개 실제 앱**이 같은 테스트 계정에 로그인해 동시 수정, revision CAS 충돌, 오프라인 큐 재시도, 중복 requestId, tombstone, 복원 후 재연결 동작 확인
- [ ] S7. 앱 데이터/서버 백업 모두 격리된 합성 기록으로 복원 리허설; 실제 학생 데이터·계정 비밀은 GitHub/ChatGPT 채팅에 전송하지 않음

## 5. 최종 출하 판정

| 체크 | 최소 합격 근거 |
| --- | --- |
| Android | 정식 서명 인플레이스 업그레이드 + 4종 데이터 전후 불변 + 기능 E2E |
| iOS | 실제 서명 iPhone/iPad 설치 + 기존 데이터 복원/보존 + 기능 E2E |
| Windows | 실제 설치/업그레이드/제거 + 저장소/복원 + 입력/레이아웃 E2E |
| 운영 | 외부 HTTPS/OAuth 실계정 + 3기기 충돌/철회/복원 + 보안 검증 |
| QA | 최종 후보 **같은 commit SHA**의 모든 필수 CI 성공 + BLOCKER 0 |
| 릴리스 | 아티팩트 서명·SHA-256·버전·앱 ID·노트·기존 업데이트 호환 + 사용자 명시적 출하 승인 |

한 항목이라도 미확인되면 `NOT READY`를 유지합니다. **`v4.0.0-beta.1` 1회 공개 후 테스트 증거가 쌓였을 때 `v4.0.0` Stable**을 별도 승인·새 태그로 발행합니다. 기존 태그/서명/파일을 수정하거나 공개 자산을 덮어쓰지 않습니다.

이 문서는 실행 절차·증거 템플릿이며, 체크박스는 최초 작성 시 모두 미완료입니다. 실제 P5 상태는 [이슈 #63](https://github.com/geumyi22/StudyOne/issues/63)에서 최신 증거와 함께 갱신합니다.
