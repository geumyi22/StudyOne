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
# One server, two REAL application transport libraries:
# the iOS Simulator just wrote two records; Windows .NET's actual SyncClient
# must read both and write one additional task using the same loopback server.
export STUDYONE_PHASE31_MAC_CROSSCLIENT=1
export STUDYONE_E2E_BASE="http://127.0.0.1:8765/"
dotnet run --project windows/StudyOne.Desktop.Tests/StudyOne.Desktop.Tests.csproj \
  --configuration Release > "$TMP/dotnet.log" 2>&1 || {
    tail -n 90 "$TMP/dotnet.log"; exit 1;
  }
grep -F 'DOTNET_ACTUAL_CLIENT_CROSS_IOS_E2E_PASSED' "$TMP/dotnet.log"

# Cold restart of the real iOS app: it now sees the newly sent Windows task.
xcrun simctl terminate "$DEVICE" com.studyone.ios
CROSS_REPORT="$DATA_DIR/tmp/studyone-ci-ios-crossclient-result.txt"
rm -f "$CROSS_REPORT"
SIMCTL_CHILD_STUDYONE_RUN_APP_NETWORK_E2E=verify-dotnet-crossclient \
SIMCTL_CHILD_STUDYONE_E2E_TOKEN="$STUDYONE_SYNC_TOKEN" \
  xcrun simctl launch "$DEVICE" com.studyone.ios
for attempt in $(seq 1 50); do
  if [[ -s "$CROSS_REPORT" ]]; then break; fi
  sleep 2
done
if [[ ! -s "$CROSS_REPORT" ]]; then
  echo "iOS app did not read back the Windows .NET change"
  exit 1
fi
cat "$CROSS_REPORT"
grep -Fq 'IOS_APP_WINDOWS_CROSSCLIENT_E2E_PASSED' "$CROSS_REPORT"

# Independent external observer: SQLite-backed server persisted THREE rows.
# No real personal data, account identity or app study store is touched.

python3 - <<'PY'
import json,os,urllib.request
url="http://127.0.0.1:8765/v1/snapshot"
request=urllib.request.Request(url,headers={
    "Authorization":"Bearer "+os.environ["STUDYONE_SYNC_TOKEN"]})
with urllib.request.urlopen(request, timeout=5) as response:
    result=json.load(response)
assert result["cursor"]==3, result["cursor"]
assert len(result["records"])==3
assert {r["kind"] for r in result["records"]}=={"task","progress"}
assert {r["id"] for r in result["records"] if r["kind"]=="task"}=={
    "1790093110001", "1790093110002"}
print("IOS_AND_DOTNET_SERVER_PERSISTENCE_VERIFIED synthetic_records=3 cursor=3")
PY
echo "IOS_WINDOWS_ACTUAL_CROSSCLIENT_NETWORK_E2E_PASSED"

# The same disposable simulator now tests the ACTUAL four-store file importer:
# checksum rejection, full roundtrip, and rollback after each of 4 writes.
# The in-app fixture refuses nonempty user records and is DEBUG/simulator-only.
xcrun simctl terminate "$DEVICE" com.studyone.ios
FOURSTORE_REPORT="$DATA_DIR/tmp/studyone-ci-ios-fourstore-result.txt"
rm -f "$FOURSTORE_REPORT"
SIMCTL_CHILD_STUDYONE_RUN_FOURSTORE_E2E=synthetic-fourstore-rollback \
  xcrun simctl launch "$DEVICE" com.studyone.ios
for attempt in $(seq 1 50); do
  if [[ -s "$FOURSTORE_REPORT" ]]; then break; fi
  sleep 2
done
if [[ ! -s "$FOURSTORE_REPORT" ]]; then
  echo "iOS four-store runtime fixture did not produce a result"
  exit 1
fi
cat "$FOURSTORE_REPORT"
grep -Fq 'IOS_FOURSTORE_RUNTIME_E2E_PASSED' "$FOURSTORE_REPORT"
echo "IOS_SYNTHETIC_FOURSTORE_RESTORE_AND_FAULT_E2E_PASSED"
