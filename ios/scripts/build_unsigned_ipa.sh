#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${STUDYONE_VERSION:=$(python3 -c 'import json; print(json.load(open("version.json"))["versionName"])')}"
command -v xcodegen >/dev/null || { echo "Install XcodeGen first: brew install xcodegen" >&2; exit 1; }
command -v xcodebuild >/dev/null || { echo "Requires macOS Xcode" >&2; exit 1; }
xcodegen generate --spec project.yml
xcodebuild -project StudyOne.xcodeproj -scheme StudyOne -configuration Release -sdk iphoneos -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
APP="build/DerivedData/Build/Products/Release-iphoneos/StudyOne.app"
test -d "$APP"
test -f "$APP/Info.plist"
/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$APP/Info.plist" | grep -Fx com.studyone.ios
EXPECTED_BUILD="$(python3 -c 'import json; print(json.load(open("version.json"))["versionCode"])')"
/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$APP/Info.plist" | grep -Fx "$EXPECTED_BUILD"
rm -rf dist
mkdir -p dist/Payload
ditto "$APP" dist/Payload/StudyOne.app
( cd dist; FILE="StudyOne-v${STUDYONE_VERSION}-unsigned.ipa"; /usr/bin/zip -qry "$FILE" Payload; unzip -t "$FILE" >/dev/null; /usr/bin/shasum -a 256 "$FILE" > "$FILE.sha256" )
rm -rf dist/Payload
ls -lah dist
