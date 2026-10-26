import Foundation

#if DEBUG && targetEnvironment(simulator)
/// Runs inside the real disposable iOS Simulator app process, with synthetic
/// records only, when explicitly enabled by GitHub Actions. Never on devices.
@MainActor
enum StudyFourStoreRuntimeE2E {
    private static let requestedMode = "synthetic-fourstore-rollback"
    private static let report = "studyone-ci-ios-fourstore-result.txt"

    private static func snapshot(details: StudyTaskDetailStore,
                                 wrong: StudyWrongAnswersStore) throws -> [Data?] {
        [
            UserDefaults.standard.data(forKey: "studyone_ios_settings_v1"),
            UserDefaults.standard.data(forKey: "studyone_ios_progress_v1"),
            try details.currentData(),
            try wrong.originalBytes()
        ]
    }

    private static func transfer(tasks: StudyOneStore,
                                 progress: StudyProgressStore,
                                 details: StudyTaskDetailStore,
                                 wrong: StudyWrongAnswersStore) throws -> Data {
        let file = try StudyFourStoreBundle.export(
            tasks: tasks.exportBackup(),
            progress: progress.exportBackup(),
            details: details.exportBackup(),
            wrong: wrong.exportBackup()
        )
        return try Data(contentsOf: file)
    }

    private static func require(_ passed: Bool, _ problem: String) throws {
        guard passed else { throw StudyError.message(problem) }
    }

    static func runIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard env["STUDYONE_RUN_FOURSTORE_E2E"] == requestedMode else { return }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent(report)
        var stage = "initialize"
        do {
            stage = "offline_paper_schedule_self_test"
            try require(StudyPaperSchedule.selfTest(),
                        "read-only study paper schedule validation failed")
            // The simulator must have no preexisting study records. Refuse to
            // change any nonempty app data, even on this debug-only path.
            let tasks = StudyOneStore()
            let progress = StudyProgressStore()
            let details = StudyTaskDetailStore()
            let wrong = StudyWrongAnswersStore()
            try require(tasks.tasks.isEmpty && progress.items.isEmpty &&
                        details.items.isEmpty && wrong.entries.isEmpty &&
                        progress.loadError == nil && details.loadError == nil &&
                        wrong.loadError == nil, "simulator fixture is not empty")
            stage = "export_empty"
            let emptyBundle = try transfer(tasks: tasks, progress: progress,
                                           details: details, wrong: wrong)

            stage = "write_synthetic"
            let syntheticID: Int64 = 1793112600001
            tasks.add(StudyTask(id: syntheticID, type: "숙제",
                                subject: "과학", title: "합성 안전복구 시험",
                                dueDate: "2026-11-02", importance: 2,
                                completed: false))
            try progress.add(subject: "국어", material: "합성 교재",
                             unit: "2-1 합성 평가", status: .inProgress,
                             inExamScope: true)
            try details.save(taskId: syntheticID, minutes: 20,
                             steps: ["합성 교재 검토"], criteria: "2문제 확인")
            try wrong.save(StudyWrongAnswer(subject: "수학",
                                             material: "합성 교재",
                                             topic: "합성 계산 문제",
                                             correction: "합성 풀이 이유",
                                             nextReview: "2026-11-02"))
            let fullBundle = try transfer(tasks: tasks, progress: progress,
                                          details: details, wrong: wrong)
            try require(tasks.tasks.count == 1 && progress.items.count == 1 &&
                        details.items.count == 1 && wrong.entries.count == 1,
                        "synthetic setup mismatch")

            stage = "reject_tampered_checksum"
            var tampered = try JSONSerialization.jsonObject(with: fullBundle)
            guard var document = tampered as? [String: Any],
                  var sections = document["sections"] as? [String: String],
                  let taskSection = sections["tasks"] else {
                throw StudyError.message("bundle test input format invalid")
            }
            sections["tasks"] = taskSection + " "
            document["sections"] = sections
            tampered = document
            let brokenData = try JSONSerialization.data(withJSONObject: tampered)
            let beforeRejection = try snapshot(details: details, wrong: wrong)
            do {
                _ = try StudyFourStoreImport.prepare(
                    brokenData, tasks: tasks, progress: progress,
                    details: details, wrong: wrong)
                throw StudyError.message("tampered SHA-256 was accepted")
            } catch let error as StudyError {
                if case .message(let reason) = error,
                   reason == "tampered SHA-256 was accepted" { throw error }
            }
            try require(try snapshot(details: details, wrong: wrong) == beforeRejection,
                        "invalid backup changed original bytes")

            stage = "restore_empty"
            let empty = try StudyFourStoreImport.prepare(
                emptyBundle, tasks: tasks, progress: progress,
                details: details, wrong: wrong)
            try StudyFourStoreImport.apply(
                empty, tasks: tasks, progress: progress,
                details: details, wrong: wrong)
            try require(tasks.tasks.isEmpty && progress.items.isEmpty &&
                        details.items.isEmpty && wrong.entries.isEmpty,
                        "empty restore did not clear all four stores")
            let baseline = try snapshot(details: details, wrong: wrong)

            // Simulate a failure AFTER each real write, using the actual
            // Swift persistence implementations and their recovery journal.
            for step in 1...4 {
                stage = "synthetic_failure_after_\(step)"
                let candidate = try StudyFourStoreImport.prepare(
                    fullBundle, tasks: tasks, progress: progress,
                    details: details, wrong: wrong)
                StudyFourStoreImport.syntheticFailureAfterSection = step
                var rejected = false
                do {
                    try StudyFourStoreImport.apply(
                        candidate, tasks: tasks, progress: progress,
                        details: details, wrong: wrong)
                } catch {
                    rejected = error.localizedDescription.contains(
                        "Synthetic section-\(step) failure")
                }
                StudyFourStoreImport.syntheticFailureAfterSection = nil
                try require(rejected, "fault \(step) was not correctly rolled back")
                try require(try snapshot(details: details, wrong: wrong) == baseline,
                            "fault \(step) changed original disk bytes")
                try require(tasks.tasks.isEmpty && progress.items.isEmpty &&
                            details.items.isEmpty && wrong.entries.isEmpty,
                            "fault \(step) left stale in-memory data")
            }

            stage = "restore_full"
            let complete = try StudyFourStoreImport.prepare(
                fullBundle, tasks: tasks, progress: progress,
                details: details, wrong: wrong)
            try StudyFourStoreImport.apply(
                complete, tasks: tasks, progress: progress,
                details: details, wrong: wrong)
            try require(tasks.tasks.count == 1 && progress.items.count == 1 &&
                        details.items.count == 1 && wrong.entries.count == 1,
                        "successful restore lost a synthetic section")

            stage = "cleanup_synthetic"
            let cleanup = try StudyFourStoreImport.prepare(
                emptyBundle, tasks: tasks, progress: progress,
                details: details, wrong: wrong)
            try StudyFourStoreImport.apply(
                cleanup, tasks: tasks, progress: progress,
                details: details, wrong: wrong)
            try require(tasks.tasks.isEmpty && progress.items.isEmpty &&
                        details.items.isEmpty && wrong.entries.isEmpty,
                        "failed to remove test records")
            try "IOS_FOURSTORE_RUNTIME_E2E_PASSED sha_reject=1 rollback_stages=4 full_roundtrip=1\n"
                .write(to: output, atomically: true, encoding: .utf8)
        } catch {
            StudyFourStoreImport.syntheticFailureAfterSection = nil
            let message = "IOS_FOURSTORE_RUNTIME_E2E_FAILED stage=\(stage) reason="
                + String(error.localizedDescription.prefix(150)) + "\n"
            try? message.write(to: output, atomically: true, encoding: .utf8)
        }
    }
}
#endif
