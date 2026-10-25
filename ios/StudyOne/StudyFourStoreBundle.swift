import Foundation
import CryptoKit

/// Read-only iOS exporter for Android's SHA-256 four-section wire format.
/// Deliberately does not provide a destructive 4-store import until an
/// interruption-safe coordinated recovery path is implemented and tested.
@MainActor
enum StudyFourStoreBundle {
    private static let kind = "studyone-transfer-bundle"
    private static let keys = ["tasks", "progress", "details", "wrongAnswers"]
    private static let limit = 8_200_000

    private struct Envelope: Codable {
        let format: String
        let schema: Int
        let sections: [String: String]
        let sha256: [String: String]
    }

    private static func sha(_ value: Data) -> String {
        SHA256.hash(data: value).map { String(format: "%02x", $0) }.joined()
    }

    private static func validate(_ blobs: [Data]) throws -> (Int, Int, Int, Int) {
        guard blobs.count == 4, blobs.allSatisfy({ $0.count <= 2_000_000 }) else {
            throw StudyError.message("4종 백업 데이터 크기 제한을 초과했습니다.")
        }
        let tasks = try JSONDecoder().decode(StudyOneBackup.self, from: blobs[0])
        var taskIds = Set<Int64>()
        guard tasks.format == "studyone-backup", tasks.schema == 1,
              (1...6).contains(tasks.grade), !tasks.className.isEmpty,
              tasks.className.count <= 3,
              tasks.className.allSatisfy({ $0 >= "0" && $0 <= "9" }),
              tasks.tasks.count <= 5000,
              tasks.school.map({ !$0.eduCode.isEmpty && !$0.schoolCode.isEmpty }) ?? true,
              tasks.tasks.allSatisfy({
                  !$0.title.isEmpty && $0.title.count <= 500 &&
                  (1...3).contains($0.importance) &&
                  $0.dueDate.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil &&
                  taskIds.insert($0.id).inserted
              }) else {
            throw StudyError.message("통합 백업의 일정 형식 또는 ID가 손상됐습니다.")
        }

        let progress = try JSONDecoder().decode(StudyProgressTransfer.self, from: blobs[1])
        let ids = progress.items.map(\.id)
        guard progress.format == "studyone-progress", progress.schema == 1,
              progress.items.count <= 2000,
              Set(ids.map { $0.lowercased() }).count == ids.count,
              progress.items.allSatisfy(\.valid) else {
            throw StudyError.message("통합 백업의 진도가 손상됐습니다.")
        }
        let details = try StudyTaskDetailStore.parseBackup(blobs[2])
        let wrong = try StudyWrongAnswersStore.parse(blobs[3])
        return (tasks.tasks.count, progress.items.count, details.count, wrong.count)
    }

    /// Validates the exact wire envelope and section SHA-256 without writing data.
    static func inspect(_ data: Data) throws -> String {
        guard data.count <= limit else {
            throw StudyError.message("4종 통합 백업은 8.2MB 이하이어야 합니다.")
        }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(root.keys) == Set(["format", "schema", "sections", "sha256"]),
              let sections = root["sections"] as? [String: String],
              let digests = root["sha256"] as? [String: String],
              Set(sections.keys) == Set(keys), Set(digests.keys) == Set(keys) else {
            throw StudyError.message("지원하지 않는 통합 백업 구조입니다.")
        }
        let document = try JSONDecoder().decode(Envelope.self, from: data)
        guard document.format == kind, document.schema == 1 else {
            throw StudyError.message("지원하지 않는 통합 백업 버전입니다.")
        }
        let blobs = try keys.map { key -> Data in
            guard let value = document.sections[key], let digest = document.sha256[key] else {
                throw StudyError.message("통합 백업 구성 요소가 누락됐습니다.")
            }
            let bytes = Data(value.utf8)
            guard sha(bytes) == digest else {
                throw StudyError.message("통합 백업 체크섬이 일치하지 않습니다: \(key)")
            }
            return bytes
        }
        let sizes = try validate(blobs)
        return "일정 \(sizes.0) · 진도 \(sizes.1) · 학습 상세 \(sizes.2) · 오답 \(sizes.3)"
    }

    /// App-generated, separate, already validated JSON export files.
    /// This never reads Keychain/NEIS/OpenAI secrets, and never modifies stores.
    static func export(tasks: URL, progress: URL, details: URL, wrong: URL) throws -> URL {
        let urls = [tasks, progress, details, wrong]
        let blobs = try urls.map { try Data(contentsOf: $0) }
        _ = try validate(blobs)
        var sections: [String: String] = [:]
        var digests: [String: String] = [:]
        for (i, key) in keys.enumerated() {
            sections[key] = String(decoding: blobs[i], as: UTF8.self)
            digests[key] = sha(blobs[i])
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(Envelope(format: kind, schema: 1,
                                                sections: sections, sha256: digests))
        _ = try inspect(data)
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("StudyOne-fourstore-\(UUID().uuidString).json")
        try data.write(to: destination, options: .atomic)
        return destination
    }
}
