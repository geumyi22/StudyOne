# StudyOne iOS (v3.0.0-beta.1)

Native SwiftUI iOS port of geumyi22/StudyOne. iOS 16+; Android code and workflows are unchanged.

Implemented in this initial port:
- Real NEIS school search and identity-filtered weekly timetable/lunch; no fake results and successful-result cache
- Tasks, exam and performance-assessment schedule, priority recommendations
- OpenAI Responses API, personal API key in Keychain and opt-in task sharing with full per-call review
- Manual Android-compatible schema-1 JSON export/import, excluding API keys

Limitations:
- This is not a 1:1 feature-parity claim. Android widgets, local reminders, APK updater and automatic Android/iOS synchronization are not ported.
- Successful unsigned IPA compilation is not equivalent to signing, installing or verifying network services on a physical iPhone.
- Personal keys are sent over HTTPS directly to NEIS/OpenAI, not embedded in the app; centrally managed secrets should use a server-side proxy.
- An unsigned IPA needs iOS signing (such as AltStore/SideStore). No App Store distribution is implied.

Build on macOS with Xcode and XcodeGen:

```sh
brew install xcodegen
bash ios/scripts/build_unsigned_ipa.sh
```

Generated: ios/dist/StudyOne-v3.0.0-beta.1-unsigned.ipa and matching .sha256.
