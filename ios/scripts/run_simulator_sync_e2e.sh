#!/usr/bin/env bash
# Phase31: actual iOS Simulator app process -> actual localhost v1 SQLite API.
# A fresh disposable DB and synthetic token; NO user study records or Keychain.
set -euo pipefail
cd "$(dirname "$0")/../.."
APP="ios/build/DerivedData-Debug/Build/Products/Debug-iphonesimulator/StudyOne.app"
test -d "$APP" || { echo "Debug iOS simulator .app not built"; exit 1; }
command -v xcrun >/dev/null
command -v curl >/dev/null
TMP="$(mktemp -d)"
DEVICE=""
PID=""
cleanup() {
  if [[ -n "$PID" ]]; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  if [[ -n "$DEVICE" ]]; then xcrun simctl terminate "$DEVICE" com.studyone.ios 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT
export STUDYONE_SYNC_TOKEN
STUDYONE_SYNC_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
python3 server/studyone_sync.py --db "$TMP/synthetic.sqlite3" --host 127.0.0.1 --port 8765 > "$TMP/server.log" 2>&1 &
PID="$!"
for attempt in $(seq 1 30); do
  if [[ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/snapshot || true)" == "401" ]]; then
    break
  fi
  sleep 1
done
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/snapshot)" = "401" || {
  echo "Synthetic local server not ready"; cat "$TMP/server.log"; exit 1;
}

DEVICE="$(xcrun simctl list -j devices available | python3 -c '
import json,sys
doc=json.load(sys.stdin)
for runtime, devices in doc.get("devices", {}).items():
    if "iOS" not in runtime: continue
    for device in devices:
        if "iPhone" in device.get("name", "") and device.get("isAvailable", True):
            print(device["udid"]); sys.exit(0)
sys.exit("No available iOS Simulator iPhone")
')"
echo "Simulator selected: $DEVICE"
if ! xcrun simctl list devices | grep -F "$DEVICE" | grep -Fq "(Booted)"; then
  xcrun simctl boot "$DEVICE"
fi
xcrun simctl bootstatus "$DEVICE" -b
xcrun simctl install "$DEVICE" "$APP"
DATA_DIR="$(xcrun simctl get_app_container "$DEVICE" com.studyone.ios data)"
rm -f "$DATA_DIR/tmp/studyone-ci-ios-network-result.txt"
SIMCTL_CHILD_STUDYONE_RUN_APP_NETWORK_E2E=loopback-v1-synthetic \
SIMCTL_CHILD_STUDYONE_E2E_TOKEN="$STUDYONE_SYNC_TOKEN" \
  xcrun simctl launch "$DEVICE" com.studyone.ios
REPORT="$DATA_DIR/tmp/studyone-ci-ios-network-result.txt"
for attempt in $(seq 1 75); do
  if [[ -s "$REPORT" ]]; then
    break
  fi
  sleep 2
done
if [[ ! -s "$REPORT" ]]; then
  echo "iOS app did not deliver network-E2E result; possible ATS, app crash or startup error"
  xcrun simctl spawn "$DEVICE" log show --last 4m --style compact \
    --predicate 'process == "StudyOne"' 2>/dev/null | tail -n 80 || true
  exit 1
fi
cat "$REPORT"
grep -Fq 'IOS_APP_V1_NETWORK_E2E_PASSED' "$REPORT" || {
  xcrun simctl spawn "$DEVICE" log show --last 3m --style compact \
    --predicate 'process == "StudyOne"' 2>/dev/null | tail -n 50 || true
  exit 1
}
# Independent external observer: SQLite-backed HTTP server has both records,
# while no real device identity/account or personal data was ever accessed.
python3 - <<'PY'
import json,os,urllib.request
url="http://127.0.0.1:8765/v1/snapshot"
request=urllib.request.Request(url,headers={
    "Authorization":"Bearer "+os.environ["STUDYONE_SYNC_TOKEN"]})
with urllib.request.urlopen(request, timeout=5) as response:
    result=json.load(response)
assert result["cursor"]==2, result["cursor"]
assert len(result["records"])==2
assert {r["kind"] for r in result["records"]}=={"task","progress"}
assert {r["id"] for r in result["records"] if r["kind"]=="task"}=={"1790093110001"}
print("IOS_APP_SERVER_PERSISTENCE_VERIFIED synthetic_records=2 cursor=2")
PY
echo "IOS_ACTUAL_SIMULATOR_SYNC_CLIENT_E2E_PASSED"
