import Foundation
import CryptoKit

/// Coordinated, opt-in import of an already validated four-section transfer.
///
/// A durable write-ahead journal holds EXACT original local bytes before the
/// first store is modified. Any crash before the journal is removed restores
/// all four original stores during StudyOneApp startup, before views load.
/// This never reads or changes Keychain credentials.
@MainActor
enum StudyFourStoreImport {
    private static let keys = ["tasks", "progress", "details", "wrongAnswers"]
    private static let maxSize = 8_200_000
    private static let format = "studyone-fourstore-recovery"
    private static var approvedTransactionActive = false

    /// Called by every local writer to block edits after failed recovery.
    /// Only the synchronous, user-approved transaction can write with a marker.
    static func requireSafeWrite() throws {
        if FileManager.default.fileExists(atPath: pendingURL.path)
            && !approvedTransactionActive {
            throw StudyError.message("4종 복원이 중단돼 원본 복구가 필요합니다. 앱을 재실행해 주세요.")
        }
    }

    #if DEBUG && targetEnvironment(simulator)
    /// Synthetic simulator QA only. Never compiled into an iPhone release.
    static var syntheticFailureAfterSection: Int?
    #endif

    private static func checkInjectedFailure(after section: Int) throws {
        #if DEBUG && targetEnvironment(simulator)
        if syntheticFailureAfterSection == section {
            throw StudyError.message("Synthetic section-\(section) failure")
        }
        #endif
    }

    private struct Transfer: Decodable {
        let format: String
        let schema: Int
        let sections: [String: String]
        let sha256: [String: String]
    }

    fileprivate struct Original: Codable, Equatable {
        let existed: Bool
        let bytes: Data

        init(_ data: Data?) {
            existed = data != nil
            bytes = data ?? Data()
        }
    }

    private struct Journal: Codable {
        let format: String
        let schema: Int
        let originals: [String: Original]
        let digests: [String: String]
    }

    struct Prepared {
        let summary: String
        fileprivate let source: Data
        fileprivate let originals: [String: Original]
    }

    private static var folder: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("StudyOne", isDirectory: true)
    }

    private static var pendingURL: URL {
        folder.appendingPathComponent("FourStorePendingImport-v1.json")
    }

    private static func localFile(_ kind: String) -> URL {
        folder.appendingPathComponent(kind == "details"
            ? "TaskDetails-v1.json" : "WrongAnswers-v1.json")
    }

    private static func userDefaultsKey(_ kind: String) -> String {
        kind == "tasks" ? "studyone_ios_settings_v1" : "studyone_ios_progress_v1"
    }

    private static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func digest(_ original: Original) -> String {
        hash(Data((original.existed ? "present:" : "absent:").utf8) + original.bytes)
    }

    private static func current() throws -> [String: Original] {
        let defaults = UserDefaults.standard
        var saved: [String: Original] = [:]
        for kind in keys {
            if kind == "tasks" || kind == "progress" {
                let key = userDefaultsKey(kind)
                let object = defaults.object(forKey: key)
                let bytes = defaults.data(forKey: key)
                guard object == nil || bytes != nil else {
                    throw StudyError.message("기존 \(kind) 저장 형식을 확인하지 못했습니다.")
                }
                saved[kind] = Original(bytes)
            } else {
                let path = localFile(kind)
                let bytes = FileManager.default.fileExists(atPath: path.path)
                    ? try Data(contentsOf: path) : nil
                saved[kind] = Original(bytes)
            }
        }
        return saved
    }

    private static func sections(from data: Data) throws -> [String: Data] {
        // inspect rejects unknown keys, incompatible schema, tampered digests,
        // duplicate IDs, invalid dates and broken section payloads.
        _ = try StudyFourStoreBundle.inspect(data)
        let transfer = try JSONDecoder().decode(Transfer.self, from: data)
        guard transfer.format == "studyone-transfer-bundle", transfer.schema == 1,
              Set(transfer.sections.keys) == Set(keys),
              Set(transfer.sha256.keys) == Set(keys) else {
            throw StudyError.message("4종 통합 백업 버전이 올바르지 않습니다.")
        }
        return Dictionary(uniqueKeysWithValues: keys.map {
            ($0, Data(transfer.sections[$0]!.utf8))
        })
    }

    /// Safe preview. Never writes a user store or recovery marker.
    static func prepare(_ source: Data,
                        tasks: StudyOneStore,
                        progress: StudyProgressStore,
                        details: StudyTaskDetailStore,
                        wrong: StudyWrongAnswersStore) throws -> Prepared {
        guard !FileManager.default.fileExists(atPath: pendingURL.path) else {
            throw StudyError.message("이전 복원이 미완료입니다. 복구를 먼저 확인하세요.")
        }
        let summary = try StudyFourStoreBundle.inspect(source)
        guard source.count <= maxSize else {
            throw StudyError.message("통합 파일 크기가 제한을 초과했습니다.")
        }
        _ = try tasks.checkedTasksForSync()
        // Export methods reject stale memory and unreadable on-disk originals.
        // Output goes to temporary files, never to the user's original stores.
        _ = try tasks.exportBackup()
        _ = try progress.exportBackup()
        _ = try details.exportBackup()
        _ = try wrong.exportBackup()
        return Prepared(summary: summary, source: source, originals: try current())
    }

    private static func saveJournal(_ originals: [String: Original]) throws {
        guard Set(originals.keys) == Set(keys) else {
            throw StudyError.message("복구 원본이 누락됐습니다.")
        }
        let journal = Journal(format: format, schema: 1, originals: originals,
                              digests: originals.mapValues { digest($0) })
        let bytes = try JSONEncoder().encode(journal)
        let manager = FileManager.default
        try manager.createDirectory(at: folder, withIntermediateDirectories: true)
        // Marker must exist and be readable before any destructive mutation.
        try bytes.write(to: pendingURL, options: .atomic)
        try manager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: pendingURL.path)
        guard try Data(contentsOf: pendingURL) == bytes else {
            throw StudyError.message("복구 원본 보관을 검증하지 못했습니다.")
        }
    }

    private static func restore(_ kind: String, from original: Original) throws {
        if kind == "tasks" || kind == "progress" {
            let key = userDefaultsKey(kind)
            if original.existed {
                UserDefaults.standard.set(original.bytes, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
            guard UserDefaults.standard.data(forKey: key) ==
                (original.existed ? original.bytes : nil) else {
                throw StudyError.message("원본 \(kind) 복구 검증 실패")
            }
        } else {
            let path = localFile(kind)
            if original.existed {
                try FileManager.default.createDirectory(at: folder,
                                                        withIntermediateDirectories: true)
                try original.bytes.write(to: path, options: .atomic)
                try FileManager.default.setAttributes(
                    [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                    ofItemAtPath: path.path)
            } else if FileManager.default.fileExists(atPath: path.path) {
                try FileManager.default.removeItem(at: path)
            }
            let after = FileManager.default.fileExists(atPath: path.path)
                ? try Data(contentsOf: path) : nil
            guard after == (original.existed ? original.bytes : nil) else {
                throw StudyError.message("원본 \(kind) 복구 검증 실패")
            }
        }
    }

    /// Called before normal stores start. Leave the marker intact on any
    /// failure so the app can block further mutations and retry recovery.
    @discardableResult
    static func recoverIfNeeded() throws -> Bool {
        guard FileManager.default.fileExists(atPath: pendingURL.path) else {
            return false
        }
        let data = try Data(contentsOf: pendingURL)
        let journal = try JSONDecoder().decode(Journal.self, from: data)
        guard journal.format == format, journal.schema == 1,
              Set(journal.originals.keys) == Set(keys),
              Set(journal.digests.keys) == Set(keys),
              keys.allSatisfy({ key in
                  guard let original = journal.originals[key] else { return false }
                  return original.existed || original.bytes.isEmpty
              }),
              journal.digests.allSatisfy({ key, checksum in
                  guard let original = journal.originals[key] else { return false }
                  return digest(original) == checksum
              }) else {
            throw StudyError.message("중단된 통합 복원의 원본 검증 실패. 기존 파일은 변경하지 않았습니다.")
        }
        // Restore every store; keep the journal if any individual write fails.
        for kind in keys { try restore(kind, from: journal.originals[kind]!) }
        guard UserDefaults.standard.synchronize() else {
            throw StudyError.message("복구된 일정·진도 설정이 디스크에 저장되지 않았습니다.")
        }
        guard try current() == journal.originals else {
            throw StudyError.message("원본 4종 재검증 실패. 복구 기록은 보존됐습니다.")
        }
        try FileManager.default.removeItem(at: pendingURL)
        return true
    }

    /// User-approved coordinated replace with stale-data check, crash journal,
    /// post-write verification and immediate rollback after ordinary errors.
    static func apply(_ approved: Prepared,
                      tasks: StudyOneStore,
                      progress: StudyProgressStore,
                      details: StudyTaskDetailStore,
                      wrong: StudyWrongAnswersStore) throws {
        guard !FileManager.default.fileExists(atPath: pendingURL.path) else {
            throw StudyError.message("이전 복구가 끝나지 않았습니다.")
        }
        let incoming = try sections(from: approved.source)
        guard try current() == approved.originals else {
            throw StudyError.message("미리보기 이후 로컬 기록이 변경됐습니다. 다시 확인하세요.")
        }
        // Refuse to replace a store whose memory has changed since preview.
        _ = try tasks.checkedTasksForSync()
        _ = try progress.exportBackup()
        _ = try details.exportBackup()
        _ = try wrong.exportBackup()
        guard try current() == approved.originals else {
            throw StudyError.message("복원 승인 중 로컬 기록이 변경됐습니다.")
        }
        try saveJournal(approved.originals)
        approvedTransactionActive = true
        defer { approvedTransactionActive = false }
        do {
            try tasks.importBackup(from: incoming["tasks"]!)
            try checkInjectedFailure(after: 1)
            _ = try progress.importBackup(data: incoming["progress"]!,
                                          original: progress.items)
            try checkInjectedFailure(after: 2)
            try details.importBackup(incoming["details"]!,
                                     expectedOriginal: approved.originals["details"]!.existed
                                        ? approved.originals["details"]!.bytes : nil)
            try checkInjectedFailure(after: 3)
            try wrong.importBackup(incoming["wrongAnswers"]!,
                                   expectedOriginal: approved.originals["wrongAnswers"]!.existed
                                        ? approved.originals["wrongAnswers"]!.bytes : nil)
            try checkInjectedFailure(after: 4)
            let expectedTasks = try JSONDecoder().decode(
                StudyOneBackup.self, from: incoming["tasks"]!)
            let expectedProgress = try JSONDecoder().decode(
                StudyProgressTransfer.self, from: incoming["progress"]!)
            guard UserDefaults.standard.synchronize() else {
                throw StudyError.message("일정·진도 저장을 디스크에 기록하지 못했습니다.")
            }
            guard try tasks.checkedTasksForSync() == expectedTasks.tasks,
                  tasks.grade == expectedTasks.grade,
                  tasks.className == expectedTasks.className,
                  progress.items == expectedProgress.items,
                  details.items == (try StudyTaskDetailStore.parseBackup(incoming["details"]!)),
                  wrong.entries == (try StudyWrongAnswersStore.parse(incoming["wrongAnswers"]!)) else {
                throw StudyError.message("복원 후 4종 데이터 검증에 실패했습니다.")
            }
            try FileManager.default.removeItem(at: pendingURL)
        } catch {
            do {
                try recoverIfNeeded()
                try tasks.reloadAfterFourStoreRecovery()
                try progress.reloadAfterFourStoreRecovery()
                try details.reloadAfterFourStoreRecovery()
                try wrong.reloadAfterFourStoreRecovery()
            } catch {
                // The pending marker survives when recovery fails. The App
                // entry point will block normal use until recovery succeeds.
                throw StudyError.message("복원 실패 후 원본 복구를 완료하지 못했습니다. 앱을 재실행해 복구 상태를 확인하세요: \(error.localizedDescription)")
            }
            throw StudyError.message("4종 통합 가져오기가 실패하여 원래 데이터를 복구했습니다: \(error.localizedDescription)")
        }
    }
}
