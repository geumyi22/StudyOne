import Foundation

/// Progress belongs to a textbook/test scope, not to a particular day's tasks.
enum StudyProgressStatus: String, Codable, CaseIterable, Identifiable {
    case unknown = "확인 필요"
    case todo = "미완료"
    case inProgress = "진행 중"
    case done = "완료"

    var id: String { rawValue }
}

struct StudyProgressItem: Codable, Identifiable, Equatable {
    let id: String
    let subject: String
    let material: String
    let unit: String
    let status: StudyProgressStatus
    let inExamScope: Bool
    let revision: Int64
    let updatedAt: Int64

    var valid: Bool {
        UUID(uuidString: id) != nil &&
        !subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && subject.count <= 60 &&
        !material.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && material.count <= 120 &&
        !unit.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && unit.count <= 200 &&
        revision > 0 && updatedAt >= 0
    }

    private static func checked(_ value: String, maximum: Int, label: String) throws -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty && trimmed.count <= maximum else {
            throw StudyError.message("\(label)을(를) 1~\(maximum)자로 입력하세요.")
        }
        return trimmed
    }

    static func create(subject: String, material: String, unit: String,
                       status: StudyProgressStatus, inExamScope: Bool) throws -> StudyProgressItem {
        StudyProgressItem(
            id: UUID().uuidString,
            subject: try checked(subject, maximum: 60, label: "과목"),
            material: try checked(material, maximum: 120, label: "교재"),
            unit: try checked(unit, maximum: 200, label: "진도 항목"),
            status: status, inExamScope: inExamScope, revision: 1,
            updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    func edited(subject: String, material: String, unit: String,
                status: StudyProgressStatus, inExamScope: Bool) throws -> StudyProgressItem {
        guard revision < Int64.max, updatedAt < Int64.max else {
            throw StudyError.message("진도 수정 기록이 한도를 초과했습니다.")
        }
        return StudyProgressItem(
            id: id,
            subject: try Self.checked(subject, maximum: 60, label: "과목"),
            material: try Self.checked(material, maximum: 120, label: "교재"),
            unit: try Self.checked(unit, maximum: 200, label: "진도 항목"),
            status: status, inExamScope: inExamScope, revision: revision + 1,
            updatedAt: max(Int64(Date().timeIntervalSince1970 * 1000), updatedAt + 1)
        )
    }
}

struct StudyProgressEnvelope: Codable {
    let schema: Int
    let items: [StudyProgressItem]
}

/// Explicit progress-only transfer file; not an old StudyOne daily-task backup.
struct StudyProgressTransfer: Codable {
    let format: String
    let schema: Int
    let items: [StudyProgressItem]
}

@MainActor
final class StudyProgressStore: ObservableObject {
    @Published private(set) var items: [StudyProgressItem] = []
    @Published private(set) var loadError: String?
    private let storeKey = "studyone_ios_progress_v1"
    private let limit = 2000

    init() {
        guard let data = UserDefaults.standard.data(forKey: storeKey) else { return }
        do {
            let saved = try JSONDecoder().decode(StudyProgressEnvelope.self, from: data)
            let ids = saved.items.map(\.id)
            guard saved.schema == 1, saved.items.count <= limit,
                  Set(ids).count == ids.count, saved.items.allSatisfy(\.valid) else {
                throw StudyError.message("지원되지 않거나 손상된 진도 저장 형식입니다.")
            }
            items = saved.items
        } catch {
            // Do not erase or overwrite bytes that might be recoverable.
            loadError = "진도 데이터를 읽지 못했습니다. 기존 데이터를 보존하고 수정을 차단합니다."
        }
    }

    private func persist(_ entries: [StudyProgressItem]) throws {
        guard loadError == nil else {
            throw StudyError.message(loadError ?? "저장소를 읽을 수 없습니다.")
        }
        guard entries.count <= limit else {
            throw StudyError.message("진도 항목은 최대 2000개입니다.")
        }
        let blob = try JSONEncoder().encode(StudyProgressEnvelope(schema: 1, items: entries))
        UserDefaults.standard.set(blob, forKey: storeKey)
        items = entries
    }

    /// Save a protected local copy and append only server IDs not already present.
    func mergeNewProgressFromServer(_ additions: [StudyProgressItem],
                                    original expected: [StudyProgressItem]) throws -> URL {
        guard loadError == nil else {
            throw StudyError.message(loadError ?? "진도 저장소를 읽을 수 없습니다.")
        }
        // Avoid overwriting independently edited progress from another tab.
        guard items == expected else {
            throw StudyError.message("승인 도중 iPhone 진도가 변경됐습니다.")
        }
        let disk = UserDefaults.standard.data(forKey: storeKey)
        if let disk {
            let decoded = try JSONDecoder().decode(StudyProgressEnvelope.self, from: disk)
            guard decoded.schema == 1 && decoded.items == expected else {
                throw StudyError.message("기존 진도 데이터가 변경되어 병합을 중단했습니다.")
            }
        } else if !items.isEmpty {
            throw StudyError.message("저장된 진도 원본을 확인하지 못했습니다.")
        }
        guard !additions.isEmpty, expected.count + additions.count <= limit else {
            throw StudyError.message("추가할 진도가 없거나 한도를 초과했습니다.")
        }
        var ids = Set(expected.map { $0.id.lowercased() })
        for item in additions {
            guard item.valid, ids.insert(item.id.lowercased()).inserted else {
                throw StudyError.message("중복 또는 손상된 진도 ID를 발견했습니다.")
            }
        }
        let next = expected + additions
        let data = try JSONEncoder().encode(
            StudyProgressEnvelope(schema: 1, items: next)
        )
        let backup = try StudySyncBackup.save(disk, kind: "progress")
        UserDefaults.standard.set(data, forKey: storeKey)
        guard UserDefaults.standard.data(forKey: storeKey) == data else {
            throw StudyError.message("진도 저장 실패. 기존 진도 백업은 보존됐습니다.")
        }
        items = next
        return backup
    }

    /// Files can be shared through Files/iCloud; tokens and existing tasks are excluded.
    func exportBackup() throws -> URL {
        guard loadError == nil else {
            throw StudyError.message(loadError ?? "진도 데이터를 읽을 수 없습니다.")
        }
        let document = StudyProgressTransfer(format: "studyone-progress",
                                             schema: 1, items: items)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let bytes = try encoder.encode(document)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("StudyOne-progress-\(Date().studyString()).json")
        try bytes.write(to: url, options: .atomic)
        return url
    }

    /// Fully validate before replacing locally stored progress.
    func importBackup(data: Data) throws {
        guard data.count <= 2_000_000 else {
            throw StudyError.message("진도 백업은 최대 2MB입니다.")
        }
        let document = try JSONDecoder().decode(StudyProgressTransfer.self, from: data)
        let ids = document.items.map(\.id)
        guard document.format == "studyone-progress",
              document.schema == 1,
              document.items.count <= limit,
              Set(ids).count == ids.count,
              document.items.allSatisfy(\.valid) else {
            throw StudyError.message("지원되지 않거나 손상된 StudyOne 진도 백업입니다.")
        }
        try persist(document.items)
    }

    func add(subject: String, material: String, unit: String,
             status: StudyProgressStatus, inExamScope: Bool) throws {
        let item = try StudyProgressItem.create(
            subject: subject, material: material, unit: unit,
            status: status, inExamScope: inExamScope
        )
        try persist(items + [item])
    }

    func update(_ item: StudyProgressItem, subject: String, material: String, unit: String,
                status: StudyProgressStatus, inExamScope: Bool) throws {
        guard let index = items.firstIndex(where: { $0.id == item.id }) else {
            throw StudyError.message("진도 항목을 찾을 수 없습니다.")
        }
        guard items[index].revision == item.revision else {
            throw StudyError.message("진도가 변경되었습니다. 최신 상태를 다시 확인하세요.")
        }
        var next = items
        next[index] = try next[index].edited(
            subject: subject, material: material, unit: unit,
            status: status, inExamScope: inExamScope
        )
        try persist(next)
    }

    func remove(_ item: StudyProgressItem) throws {
        guard let current = items.first(where: { $0.id == item.id }) else {
            throw StudyError.message("진도 항목을 찾을 수 없습니다.")
        }
        guard current.revision == item.revision else {
            throw StudyError.message("수정된 진도 항목은 오래된 화면에서 삭제할 수 없습니다.")
        }
        try persist(items.filter { $0.id != item.id })
    }
}
