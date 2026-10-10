#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${UPGRADE_BASE_APK:-}" ]]; then
  test -f "$UPGRADE_BASE_APK"
  adb install -r "$UPGRADE_BASE_APK"
  PREVIOUS=$(adb shell dumpsys package com.studyone.app | grep -m1 firstInstallTime | tr -d "\r")
fi
adb install -r "${1:-apk/app-debug.apk}"
if [[ -n "${UPGRADE_BASE_APK:-}" ]]; then
  CURRENT=$(adb shell dumpsys package com.studyone.app | grep -m1 firstInstallTime | tr -d "\r")
  test "$PREVIOUS" = "$CURRENT" || { echo "Package was reinstalled instead of updated"; exit 1; }
  echo "SIGNED_IN_PLACE_UPGRADE_PASSED"
fi
adb logcat -c
adb shell am start -W -n com.studyone.app/.MainActivity
sleep 4
adb shell am force-stop com.studyone.app
adb shell am start -W -n com.studyone.app/.MainActivity --ez studyone_ai_self_test true
sleep 2
adb logcat -d -s StudyOneAI:I | grep -q 'AI_OFFLINE_FIXTURE_TEST_PASSED'
echo 'AI_OFFLINE_FIXTURE_TEST_PASSED'
adb shell pidof com.studyone.app | grep -E '[0-9]+'
SIZE="$(adb shell wm size | sed -n 's/.*: //p' | tr -d '\r' | head -n1)"
WIDTH="${SIZE%x*}"
HEIGHT="${SIZE#*x}"
if [[ "$WIDTH" =~ ^[0-9]+$ && "$HEIGHT" =~ ^[0-9]+$ ]]; then
  Y=$((HEIGHT-130))
  for INDEX in 0 1 2 3 4 5; do
    X=$((WIDTH*(2*INDEX+1)/12))
    adb shell input tap "$X" "$Y"
    sleep 1
    adb shell pidof com.studyone.app | grep -E '[0-9]+'
    adb shell dumpsys activity activities | grep -q 'com.studyone.app/.MainActivity'
  done
else
  echo "Cannot parse emulator screen size"; exit 1
fi
if adb logcat -d -b crash | grep -q 'com.studyone.app'; then
  adb logcat -d -b crash; exit 1
fi
echo 'StudyOne startup and six-tab smoke passed'
