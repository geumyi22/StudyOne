import Foundation
import SwiftUI

/// Optional per-task guidance kept outside the historical schema-1 task JSON and sync API.
/// Keys and backup envelope intentionally match Android StudyTaskDetailStorage.
struct StudyTaskDetail: Codable, Identifiable, Equatable {
    let taskId: Int64
    let estimatedMinutes: Int?
    let steps: [String]
    let completionCriteria: String

    var id: Int64 { taskId }
    var isEmpty: Bool { estimatedMinutes == nil && steps.isEmpty && completionCriteria.isEmpty }

    init(taskId: Int64, estimatedMinutes: Int?, steps: [String],
         completionCriteria: String) throws {
        guard taskId > 0 else { throw StudyError.message("학습 일정 ID가 올바르지 않습니다.") }
        if let duration = estimatedMinutes {
            guard (1...600).contains(duration) else {
                throw StudyError.message("예상 시간은 1~600분이어야 합니다.")
            }
        }
        guard steps.count <= 20 else { throw StudyError.message("공부 단계는 최대 20개입니다.") }
        let normalized = steps.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard normalized.allSatisfy({ !$0.isEmpty && $0.count <= 200 }) else {
            throw StudyError.message("공부 단계는 각각 1~200자로 입력하세요.")
        }
        let criteria = completionCriteria.trimmingCharacters(in: .whitespacesAndNewlines)
        guard criteria.count <= 500 else {
            throw StudyError.message("완료 기준은 최대 500자입니다.")
        }
        self.taskId = taskId
        self.estimatedMinutes = estimatedMinutes
        self.steps = normalized
        self.completionCriteria = criteria
    }

    var displayText: String {
        var lines: [String] = []
        if let estimatedMinutes { lines.append("예상 \(estimatedMinutes)분") }
        lines += steps.enumerated().map { "\($0.offset + 1). \($0.element)" }
        if !completionCriteria.isEmpty { lines.append("완료 기준: \(completionCriteria)") }
        return lines.joined(separator: "\n")
    }

    enum CodingKeys: String, CodingKey {
        case taskId, estimatedMinutes, steps, completionCriteria
    }
    func encode(to encoder: Encoder) throws {
        var fields = encoder.container(keyedBy: CodingKeys.self)
        try fields.encode(taskId, forKey: .taskId)
        if let estimatedMinutes {
            try fields.encode(estimatedMinutes, forKey: .estimatedMinutes)
        } else {
            try fields.encodeNil(forKey: .estimatedMinutes)
        }
        try fields.encode(steps, forKey: .steps)
        try fields.encode(completionCriteria, forKey: .completionCriteria)
    }
}

struct StudyTaskDetailsTransfer: Codable {
    let format: String
    let schema: Int
    let items: [StudyTaskDetail]
}

/// Isolated local data: does not alter StudyOneStore.tasks, progress, accounts or sync payloads.
@MainActor
final class StudyTaskDetailStore: ObservableObject {
    @Published private(set) var items: [Int64: StudyTaskDetail] = [:]
    @Published private(set) var loadError: String?

    private let maximum = 5000
    private let maximumBytes = 2_000_000
    private let fileURL = FileManager.default.urls(for: .applicationSupportDirectory,
                                                    in: .userDomainMask)[0]
        .appendingPathComponent("StudyOne", isDirectory: true)
        .appendingPathComponent("TaskDetails-v1.json")

    init() {
        do {
            if let raw = try currentData() { items = try Self.parseBackup(raw) }
        } catch {
            // Never replace a corrupt/unknown original with an empty set.
            loadError = "학습 상세 원본을 읽을 수 없습니다. 기존 파일을 보존하고 변경을 차단했습니다."
        }
    }

    func detail(for taskId: Int64) -> StudyTaskDetail? { items[taskId] }

    func currentData() throws -> Data? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try Data(contentsOf: fileURL)
    }

    static func parseBackup(_ data: Data) throws -> [Int64: StudyTaskDetail] {
        guard data.count <= 2_000_000 else {
            throw StudyError.message("학습 상세 백업은 최대 2MB입니다.")
        }
        // Fail closed for extra/missing keys, as the Android importer does.
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(root.keys) == Set(["format", "schema", "items"]),
              let objects = root["items"] as? [[String: Any]],
              objects.count <= 5000,
              objects.allSatisfy({ Set($0.keys) == Set(["taskId", "estimatedMinutes", "steps", "completionCriteria"]) }) else {
            throw StudyError.message("지원되지 않거나 손상된 상세 백업입니다.")
        }
        let document = try JSONDecoder().decode(StudyTaskDetailsTransfer.self, from: data)
        guard document.format == "studyone-task-details", document.schema == 1,
              document.items.count == objects.count else {
            throw StudyError.message("학습 상세 백업의 버전이 올바르지 않습니다.")
        }
        var checked: [Int64: StudyTaskDetail] = [:]
        for item in document.items {
            let valid = try StudyTaskDetail(taskId: item.taskId,
                                            estimatedMinutes: item.estimatedMinutes,
                                            steps: item.steps,
                                            completionCriteria: item.completionCriteria)
            guard !valid.isEmpty, valid == item, checked[item.taskId] == nil else {
                throw StudyError.message("중복되거나 손상된 학습 상세 ID입니다.")
            }
            checked[item.taskId] = valid
        }
        return checked
    }

    private func encode(_ records: [Int64: StudyTaskDetail]) throws -> Data {
        guard records.count <= maximum else { throw StudyError.message("상세 항목은 최대 5000개입니다.") }
        let document = StudyTaskDetailsTransfer(
            format: "studyone-task-details", schema: 1,
            items: records.values.sorted { $0.taskId < $1.taskId })
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let result = try encoder.encode(document)
        guard result.count <= maximumBytes else {
            throw StudyError.message("학습 상세 백업은 최대 2MB입니다.")
        }
        return result
    }

    private func persist(_ records: [Int64: StudyTaskDetail]) throws {
        guard loadError == nil else {
            throw StudyError.message(loadError ?? "상세 저장소를 읽지 못했습니다.")
        }
        let bytes = try encode(records)
        let manager = FileManager.default
        try manager.createDirectory(at: fileURL.deletingLastPathComponent(),
                                    withIntermediateDirectories: true)
        try bytes.write(to: fileURL, options: .atomic)
        try manager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: fileURL.path)
        guard try Data(contentsOf: fileURL) == bytes else {
            throw StudyError.message("상세 정보 저장 검증에 실패했습니다.")
        }
        items = records
    }

    func save(taskId: Int64, minutes: Int?, steps: [String], criteria: String) throws {
        let item = try StudyTaskDetail(taskId: taskId, estimatedMinutes: minutes,
                                       steps: steps, completionCriteria: criteria)
        var next = items
        if item.isEmpty { next.removeValue(forKey: taskId) }
        else { next[taskId] = item }
        try persist(next)
    }

    func exportBackup() throws -> URL {
        guard loadError == nil else {
            throw StudyError.message(loadError ?? "상세 저장소 오류")
        }
        let bytes = try encode(items)
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("StudyOne-task-details-\(UUID().uuidString).json")
        try bytes.write(to: path, options: .atomic)
        return path
    }

    /// Explicit replace only after confirmation, original-byte backup, and stale-read check.
    @discardableResult
    func importBackup(_ data: Data, expectedOriginal: Data?) throws -> URL {
        guard loadError == nil else {
            throw StudyError.message(loadError ?? "상세 저장소 오류")
        }
        let verified = try Self.parseBackup(data)
        let original = try currentData()
        guard original == expectedOriginal else {
            throw StudyError.message("확인하는 동안 상세 정보가 변경됐습니다. 다시 가져오세요.")
        }
        let backupDir = fileURL.deletingLastPathComponent()
            .appendingPathComponent("BeforeTaskDetailsImport", isDirectory: true)
        let manager = FileManager.default
        try manager.createDirectory(at: backupDir, withIntermediateDirectories: true)
        let backupURL = backupDir.appendingPathComponent(
            "StudyOne-details-before-import-\(UUID().uuidString).bak")
        // A zero-byte backup records that no previous detail file existed.
        try (original ?? Data()).write(to: backupURL, options: .atomic)
        try manager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: backupURL.path)
        try persist(verified)
        return backupURL
    }
}

/// Editor is deliberately separate from the schema-1 task editor.
struct StudyTaskDetailEditor: View {
    @Environment(\.dismiss) private var dismiss
    let task: StudyTask
    let onSave: (Int?, [String], String) throws -> Void
    @State private var minutes: String
    @State private var steps: String
    @State private var criteria: String
    @State private var errorText = ""
    @State private var showError = false

    init(task: StudyTask, existing: StudyTaskDetail?,
         onSave: @escaping (Int?, [String], String) throws -> Void) {
        self.task = task
        self.onSave = onSave
        _minutes = State(initialValue: existing?.estimatedMinutes.map(String.init) ?? "")
        _steps = State(initialValue: existing?.steps.joined(separator: "\n") ?? "")
        _criteria = State(initialValue: existing?.completionCriteria ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("일정") {
                    Text("[\(task.subject.isEmpty ? "기타" : task.subject)] \(task.title)")
                }
                Section("예상 공부 시간") {
                    TextField("분 · 미입력 가능 (1~600)", text: $minutes)
                        .keyboardType(.numberPad)
                }
                Section("공부 단계 · 한 줄에 하나씩") {
                    TextEditor(text: $steps).frame(minHeight: 130)
                    Text("최대 20개, 각 200자").font(.caption).foregroundStyle(.secondary)
                }
                Section("완료 기준") {
                    TextEditor(text: $criteria).frame(minHeight: 90)
                    Text("최대 500자 · 모두 비우면 이 일정의 상세 정보만 삭제")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("학습 상세")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        do {
                            let raw = minutes.trimmingCharacters(in: .whitespacesAndNewlines)
                            let duration: Int?
                            if raw.isEmpty { duration = nil }
                            else if let n = Int(raw) { duration = n }
                            else { throw StudyError.message("예상 시간은 1~600 사이의 정수로 입력하세요.") }
                            let separated = steps.components(separatedBy: .newlines)
                                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                                .filter { !$0.isEmpty }
                            try onSave(duration, separated, criteria)
                            dismiss()
                        } catch {
                            errorText = error.localizedDescription
                            showError = true
                        }
                    }
                }
            }
            .alert("상세 정보 오류", isPresented: $showError) {
                Button("확인", role: .cancel) {}
            } message: { Text(errorText) }
        }
    }
}
