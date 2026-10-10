#!/usr/bin/env bash
set -euo pipefail
adb install -r "${1:-apk/app-debug.apk}"
adb logcat -c
adb shell am start -W -n com.studyone.app/.MainActivity
sleep 4
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
