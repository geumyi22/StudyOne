#!/usr/bin/env bash
set -euo pipefail
APP_PACKAGE="${STUDYONE_TEST_PACKAGE:-com.studyone.app}"
if [[ -n "${UPGRADE_BASE_APK:-}" ]]; then
  test -f "$UPGRADE_BASE_APK"
  adb install -r "$UPGRADE_BASE_APK"
  PREVIOUS=$(adb shell dumpsys package "$APP_PACKAGE" | grep -m1 firstInstallTime | tr -d "\r")
fi
adb install -r "${1:-apk/app-debug.apk}"
if [[ -n "${UPGRADE_BASE_APK:-}" ]]; then
  CURRENT=$(adb shell dumpsys package "$APP_PACKAGE" | grep -m1 firstInstallTime | tr -d "\r")
  test "$PREVIOUS" = "$CURRENT" || { echo "Package was reinstalled instead of updated"; exit 1; }
  echo "SIGNED_IN_PLACE_UPGRADE_PASSED"
fi
adb logcat -c
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity"
sleep 4
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_ai_self_test true
sleep 2
adb logcat -d -s StudyOneAI:I | grep -q 'AI_OFFLINE_FIXTURE_TEST_PASSED'
echo 'AI_OFFLINE_FIXTURE_TEST_PASSED'
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_progress_self_test true
sleep 2
adb logcat -d -s StudyOneProgress:I | grep -q 'PROGRESS_BACKUP_ROUNDTRIP_PASSED'
# Cold-start is required: am start may deliver extras to an existing Activity
# without calling onCreate(), silently skipping the synthetic recovery E2E.
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_progress_recovery_self_test true
adb logcat -d -s StudyOneProgress:I | grep -q 'PROGRESS_ISOLATED_RECOVERY_E2E_PASSED'
echo 'PROGRESS_ISOLATED_RECOVERY_E2E_PASSED'
echo 'PROGRESS_BACKUP_ROUNDTRIP_PASSED'
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_nav_self_test true
sleep 2
adb logcat -d -s StudyOneNav:I | grep -q 'NAVIGATION_STATE_SYNC_PASSED'
echo 'NAVIGATION_STATE_SYNC_PASSED'
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_planner_hub_self_test true
sleep 2
adb logcat -d -s StudyOnePlanner:I | grep -q 'PLANNER_HUB_SEVEN_PAGES_PASSED'
echo 'PLANNER_HUB_SEVEN_PAGES_PASSED'
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_bundle_self_test true
sleep 2
adb logcat -d -s StudyOneBundle:I | grep -q 'TRANSFER_BUNDLE_VALIDATION_PASSED'
echo 'TRANSFER_BUNDLE_VALIDATION_PASSED'
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" --ez studyone_sync_self_test true
sleep 2
adb logcat -d -s StudyOneSync:I | grep -q 'SYNC_CREATE_ONLY_GUARDS_PASSED'
echo 'SYNC_CREATE_ONLY_GUARDS_PASSED'

# Spawn an isolated same-run server with a synthetic token and disposable DB.
SYNC_TMP_DIR="$(mktemp -d)"
export STUDYONE_SYNC_TOKEN='StudyOneEmulatorContractFixtureTokenONLY2026'
python3 server/studyone_sync.py --db "$SYNC_TMP_DIR/sync.sqlite3" --host 127.0.0.1 --port 8765 &
SYNC_PID=$!
cleanup_sync_fixture() {
  kill "$SYNC_PID" 2>/dev/null || true
  wait "$SYNC_PID" 2>/dev/null || true
  rm -rf "$SYNC_TMP_DIR"
}
trap cleanup_sync_fixture EXIT
for attempt in $(seq 1 15); do
  if [[ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/snapshot || true)" == '401' ]]; then
    break
  fi
  sleep 1
done
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/snapshot)" = '401'
adb reverse tcp:8765 tcp:8765
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" \
  --ez studyone_sync_network_self_test true \
  --es studyone_sync_test_token "$STUDYONE_SYNC_TOKEN"
for attempt in $(seq 1 16); do
  if adb logcat -d -s StudyOneSync:I StudyOneSync:E | grep -q 'LOCAL_SYNC_HTTP_ROUNDTRIP_PASSED'; then
    break
  fi
  if adb logcat -d -s StudyOneSync:I StudyOneSync:E | grep -q 'LOCAL_SYNC_HTTP_ROUNDTRIP_FAILED'; then
    adb logcat -d -s StudyOneSync:I StudyOneSync:E
    exit 1
  fi
  sleep 1
done
adb logcat -d -s StudyOneSync:I | grep -q 'LOCAL_SYNC_HTTP_ROUNDTRIP_PASSED'
echo 'LOCAL_SYNC_HTTP_ROUNDTRIP_PASSED'

# The Android application PROCESS has written two synthetic records. Launch
# StudyOne's actual portable Windows .NET SyncClient implementation (not a
# Python mock) against the very same ephemeral SQLite-backed loopback server.
export STUDYONE_PHASE31_ANDROID_CROSSCLIENT=1
export STUDYONE_E2E_BASE='http://127.0.0.1:8765/'
if ! dotnet run --project windows/StudyOne.Desktop.Tests/StudyOne.Desktop.Tests.csproj \
    --configuration Release > "$SYNC_TMP_DIR/dotnet-crossclient.log" 2>&1; then
  tail -n 90 "$SYNC_TMP_DIR/dotnet-crossclient.log"
  exit 1
fi
grep -F 'DOTNET_ACTUAL_CLIENT_CROSS_ANDROID_E2E_PASSED' "$SYNC_TMP_DIR/dotnet-crossclient.log"
unset STUDYONE_PHASE31_ANDROID_CROSSCLIENT STUDYONE_E2E_BASE

# Cold-start Android again: read the .NET-origin task through the real Java
# StudySyncClient parser, detect a same-ID divergent local record as conflict,
# and explicitly reject a bad bearer without changing app study stores.
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" \
  --ez studyone_sync_crossclient_self_test true \
  --es studyone_sync_test_token "$STUDYONE_SYNC_TOKEN"
for attempt in $(seq 1 20); do
  if adb logcat -d -s StudyOneSync:I StudyOneSync:E |
      grep -q 'ANDROID_DOTNET_CROSSCLIENT_E2E_PASSED'; then
    break
  fi
  if adb logcat -d -s StudyOneSync:I StudyOneSync:E |
      grep -q 'ANDROID_DOTNET_CROSSCLIENT_E2E_FAILED'; then
    echo 'Android app rejected cross-client fixture'
    adb logcat -d -s StudyOneSync:I StudyOneSync:E
    exit 1
  fi
  sleep 1
done
adb logcat -d -s StudyOneSync:I |
    grep -q 'ANDROID_DOTNET_CROSSCLIENT_E2E_PASSED'
echo 'ANDROID_DOTNET_CROSSCLIENT_E2E_PASSED'

# Independent server observer checks both actual clients left exactly
# two tasks and one progress item, and the SQLite cursor has no extra writes.
python3 - <<'PY'
import json,os,urllib.request
request=urllib.request.Request(
    'http://127.0.0.1:8765/v1/snapshot',
    headers={'Authorization': 'Bearer '+os.environ['STUDYONE_SYNC_TOKEN']})
with urllib.request.urlopen(request, timeout=5) as response:
    result=json.load(response)
assert result['cursor']==3, result['cursor']
assert len(result['records'])==3, len(result['records'])
assert {r['id'] for r in result['records'] if r['kind']=='task'}=={
    '1730000001234','1730000005678'
}
assert len([r for r in result['records'] if r['kind']=='progress'])==1
print('ANDROID_DOTNET_SERVER_PERSISTENCE_VERIFIED cursor=3 synthetic_records=3')
PY
echo 'ANDROID_DOTNET_ACTUAL_CROSSCLIENT_NETWORK_E2E_PASSED'

# The offline outbox QA uses an isolated synthetic app-private file only.
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity" \
  --ez studyone_outbox_self_test true
adb logcat -d -s StudyOneOutbox:I | grep -q 'ANDROID_OUTBOX_ISOLATED_E2E_PASSED'
echo 'ANDROID_OUTBOX_ISOLATED_E2E_PASSED'
adb shell am force-stop "$APP_PACKAGE"
adb shell am start -W -n "$APP_PACKAGE/com.studyone.app.MainActivity"
adb shell pidof "$APP_PACKAGE" | grep -E '[0-9]+'
SIZE="$(adb shell wm size | sed -n 's/.*: //p' | tr -d '\r' | head -n1)"
WIDTH="${SIZE%x*}"
HEIGHT="${SIZE#*x}"
if [[ "$WIDTH" =~ ^[0-9]+$ && "$HEIGHT" =~ ^[0-9]+$ ]]; then
  Y=$((HEIGHT-130))
  for INDEX in 0 1 2 3 4 5; do
    X=$((WIDTH*(2*INDEX+1)/12))
    adb shell input tap "$X" "$Y"
    sleep 1
    adb shell pidof "$APP_PACKAGE" | grep -E '[0-9]+'
    adb shell dumpsys activity activities | grep -Fq "$APP_PACKAGE/com.studyone.app.MainActivity"
  done
else
  echo "Cannot parse emulator screen size"; exit 1
fi
if adb logcat -d -b crash | grep -Fq "$APP_PACKAGE"; then
  adb logcat -d -b crash; exit 1
fi
echo 'StudyOne startup and six-tab smoke passed'
