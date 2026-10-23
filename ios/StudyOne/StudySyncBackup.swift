import Foundation

/// A private on-device copy of original bytes before an explicit incoming sync.
enum StudySyncBackup {
    static func save(_ original: Data?, kind: String) throws -> URL {
        guard kind == "tasks" || kind == "progress" else {
            throw StudyError.message("지원하지 않는 백업 유형입니다.")
        }
        let manager = FileManager.default
        let folder = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("StudyOne", isDirectory: true)
            .appendingPathComponent("BeforeSync", isDirectory: true)
        try manager.createDirectory(at: folder, withIntermediateDirectories: true)
        let filename = "StudyOne-\(kind)-before-sync-\(UUID().uuidString).bak"
        let target = folder.appendingPathComponent(filename)
        // Empty bytes mean no original UserDefaults key existed.
        try (original ?? Data()).write(to: target, options: .atomic)
        try manager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: target.path
        )
        return target
    }
}
