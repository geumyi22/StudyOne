# v4 learning tools — 2026-11-13 development increment

Base: v4 draft PR #60, commit `b3e4b97cb4af22e623043e7b04a8fe3d2a31c041`.
Development branch: `feature/studyone-v4-learning-ux-20261113`.
This increment does **not** merge main, publish a release, install on a user device,
change signing, migrate real data, or enable production OAuth/automatic sync.

## Implemented scope

### Windows generic paper planner

The existing task header gains **시험지 · 학습지 배분**. The native dialog accepts
subject, start/end dates, daily limit, skipped dates, first review source, and editable
unit/paper rows. Unlike the existing mobile fixed 18-paper template, Windows accepts
1–8 units and 1–20 paper types per unit. Counts remain limited to 60 per paper type,
240 outstanding papers, 120 calendar days and 12 papers/day.

The pure scheduler distributes units and paper types round-robin, excludes only
user-declared consecutive completed rounds, alternates review sources on scheduled
work days and rejects insufficient capacity rather than dropping work. Nothing is
saved by preview or copy. Any input edit invalidates the old preview and disables
copy/add until recalculation. No daily user data or inferred progress is seeded.

**검토 후 숙제 추가…** is a distinct confirmation. It appends only paper tasks,
not review hints, and never marks progress or existing tasks complete. The whole batch
is rejected for duplicate/overlapping/aggregate homework, changed source bytes,
invalid records, task-count/size limits, pending four-store recovery, or no consent.
A byte-exact recovery copy is saved before the atomic task-file replacement. Unknown
JSON properties and existing school/task fields are preserved by the append operation.
The fresh-store recovery copy represents a valid empty store; it cannot recreate file
absence. A failed final write can leave an extra recovery file, never a partial batch.

Snapshot comparison is not an OS-wide transactional compare-and-swap. The session-local
single-instance guard prevents ordinary double launching in the same Windows session;
other sessions and noncooperating editors must not edit the store concurrently.

### Windows task navigation

Transient AND search across subject/title/category/date, completion status and category
filters are added without changing the existing Today/Tomorrow/All semantics. Filter
reset does not modify dates or tasks. The empty-result message distinguishes a filtered
view from missing data. Today checklist copy continues to include all date-eligible
tasks, independent of visual filters. Summary updates are queued once per dispatcher
cycle to avoid rescanning after every item in a bulk refresh.

### Calendar and label consistency

Android and iOS reject hidden control/format/separator characters and use an 80-Unicode-
scalar label limit, matching the existing Python engine and the new Windows engine.
Android dates are restricted to years 1–9999; its iteration never advances beyond the
requested final date. Windows date views also avoid `MaxValue + 1`.

iOS uses proleptic Gregorian integer day arithmetic inside this isolated calculator,
removing locale/DST and the 1582 calendar-cutover discrepancy. No device date settings,
stored dates or unrelated date utilities are modified.

## Evidence and reproducibility

`python3 scripts/tests/check_schedule_runtime.py` executes the real Python, Java and
Swift schedulers. Its 114 synthetic cases include calendar boundaries/leap years,
1582 dates, skipped/all-complete days, over-capacity, duplicate input, invisible
characters, emoji scalar limits, round numbers >20 and 80 seeded scenarios.
The fixture generator's digest is pinned; expected outputs cannot silently change
when the reference implementation changes.

Windows pure tests:

```sh
python scripts/tests/schedule_cases.py /tmp/studyone-schedule.json
dotnet run --project windows/StudyOne.LearningTools.Tests/StudyOne.LearningTools.Tests.csproj -c Release -- /tmp/studyone-schedule.json
```

Windows tests cover the same 114 fixtures plus filtering, explicit approval, exact
original/unknown-field preservation, repeat/stale/aggregate/capacity rejection,
recovery-copy failure, new-store creation and synthetic temporary-file cleanup.
Existing Windows tests and WPF build/publish remain in `windows.yml`. New
`learning-tools.yml` runs native Swift/Java/Python parity on macOS. Artifacts are
nonpublishing development candidates, not signed Stable packages.

## Status at authoring

- Local Python/Java/Swift golden runtime checks: **passed, 114 cases per engine**.
- Baseline v4 commit: eight prior workflows succeeded (not evidence for this increment).
- New Windows compilation, legacy regression, Android/iOS builds: awaiting this
  branch's CI evidence; do not infer them from source review or baseline CI.
- Real Windows visual interaction, Android/iPhone hardware, installation upgrades,
  performance/accessibility and production account acceptance: **not performed**.
- Public Beta/main/installed user apps: unchanged by this branch.

## Remaining work

Mobile arbitrary-subject template UI, stable-ID progress linking for arbitrary templates,
real-device visual/keyboard/screen-reader QA, durable task provenance, robust multi-session
storage arbitration, full cross-device safe automatic synchronization and separately
approved release/signing/installation checks remain. Existing v4 release gates apply.
