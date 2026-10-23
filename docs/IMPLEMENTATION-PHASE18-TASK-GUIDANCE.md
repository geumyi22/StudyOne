# Phase 18 - Optional study instructions (Android Canary)

Each Android task can have optional estimatedMinutes (1-600), steps (at most 20, each 1-200 characters), and completionCriteria (up to 500 characters). These details are editable from the local task card and appear beneath the task and in the explicitly copied daily reminder checklist.

These fields live in separate app-private preferences keyed by task ID. The existing task JSON schema 1, GitHub release packages, sync mutation formats, and stored task dates/categories/IDs remain unchanged. Device-to-device transfer of details requires the separate, explicitly initiated `studyone-task-details` JSON file (schema 1). This local-only extension does not claim ChatGPT sync.

Export uses the Android document picker. Import validates the entire bounded JSON before presenting a replacement confirmation, writes an app-private before-import recovery copy, and then commits replacement of detail records only. Existing school, tasks, progress, secrets, and sync data are unaffected. Old or missing details remain valid. Task details are not automatically created from the ChatGPT conversation.

Tests: strict model limits, immutable steps, detailed Samsung Reminder formatting, Android UI/export/import contract checks, Android APK/Lint/emulator CI. This Canary draft must not be promoted until CI passes and separate device verification confirms migration safety.

Follow-up: compatible detail storage and UI on iOS/Windows; explicit conflict-aware account transport only after server schema and full migration testing.
