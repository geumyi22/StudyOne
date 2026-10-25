import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// A portable, offline-only record, outside StudyTask schema 1 and all sync payloads.
struct StudyWrongAnswer: Codable, Identifiable, Equatable {
    let id: String
    let subject: String
    let material: String
    let topic: String
    let correction: String
    let nextReview: String
    let retries: Int
    let mastered: Bool

    init(id: String = UUID().uuidString.lowercased(), subject: String, material: String,
         topic: String, correction: String, nextReview: String, retries: Int = 0,
         mastered: Bool = false) throws {
        guard UUID(uuidString: id) != nil, id == id.lowercased() else {
            throw StudyError.message("오답 ID가 올바르지 않습니다.")
        }
        func clean(_ value: String, max: Int, label: String) throws -> String {
            guard !value.isEmpty && value == value.trimmingCharacters(in: .whitespacesAndNewlines),
                  value.count <= max else {
                throw StudyError.message("\(label)은(는) 1~\(max)자로 입력하세요.")
            }
            return value
        }
        guard (0...50).contains(retries) else {
            throw StudyError.message("재풀이 횟수는 0~50회입니다.")
        }
        let d = ISO8601DateFormatter()
        d.formatOptions = [.withFullDate]
        d.timeZone = TimeZone(secondsFromGMT: 0)
        guard let parsed = d.date(from: nextReview),
              d.string(from: parsed) == nextReview else {
            throw StudyError.message("복습 날짜 형식은 YYYY-MM-DD입니다.")
        }
        self.id = id
        self.subject = try clean(subject, max: 60, label: "과목")
        self.material = try clean(material, max: 120, label: "교재")
        self.topic = try clean(topic, max: 500, label: "틀린 문제")
        self.correction = try clean(correction, max: 1000, label: "틀린 이유")
        self.nextReview = nextReview
        self.retries = retries
        self.mastered = mastered
    }

    func changed(retries: Int? = nil, nextReview: String? = nil,
                 mastered: Bool? = nil) throws -> StudyWrongAnswer {
        try StudyWrongAnswer(id: id, subject: subject, material: material,
                             topic: topic, correction: correction,
                             nextReview: nextReview ?? self.nextReview,
                             retries: retries ?? self.retries,
                             mastered: mastered ?? self.mastered)
    }
}

private struct WrongAnswersTransfer: Codable {
    let format: String
    let schema: Int
    let items: [StudyWrongAnswer]
}

@MainActor
final class StudyWrongAnswersStore: ObservableObject {
    @Published private(set) var entries: [StudyWrongAnswer] = []
    @Published private(set) var loadError: String?

    private let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("StudyOne", isDirectory: true)
        .appendingPathComponent("WrongAnswers-v1.json")

    init() {
        do { if let data = try originalBytes() { entries = try Self.parse(data) } }
        catch { loadError = "오답 원본을 읽지 못했습니다. 기존 파일을 보존하고 쓰기를 차단합니다." }
    }

    func originalBytes() throws -> Data? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try Data(contentsOf: url)
    }

    static func parse(_ data: Data) throws -> [StudyWrongAnswer] {
        guard data.count <= 2_000_000 else { throw StudyError.message("오답 백업은 최대 2MB입니다.") }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(root.keys) == Set(["format","schema","items"]),
              let rows = root["items"] as? [[String: Any]], rows.count <= 1000,
              rows.allSatisfy({ Set($0.keys) == Set([
                  "id","subject","material","topic","correction",
                  "nextReview","retries","mastered"]) }) else {
            throw StudyError.message("오답 백업 필드가 올바르지 않습니다.")
        }
        let doc = try JSONDecoder().decode(WrongAnswersTransfer.self, from: data)
        guard doc.schema == 1 && doc.format == "studyone-wrong-answers",
              doc.items.count == rows.count else {
            throw StudyError.message("지원하지 않는 오답 JSON입니다.")
        }
        var ids = Set<String>()
        for item in doc.items {
            let valid = try StudyWrongAnswer(id: item.id, subject: item.subject,
                material: item.material, topic: item.topic, correction: item.correction,
                nextReview: item.nextReview, retries: item.retries, mastered: item.mastered)
            guard item == valid, ids.insert(item.id).inserted else {
                throw StudyError.message("중복되거나 손상된 오답 ID입니다.")
            }
        }
        return doc.items
    }

    private func encoded(_ items: [StudyWrongAnswer]) throws -> Data {
        let doc = WrongAnswersTransfer(format: "studyone-wrong-answers", schema: 1,
                                       items: items.sorted { $0.id < $1.id })
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let result = try encoder.encode(doc)
        _ = try Self.parse(result)
        return result
    }

    private func persist(_ items: [StudyWrongAnswer]) throws {
        guard loadError == nil else { throw StudyError.message("손상된 오답 원본은 수정할 수 없습니다.") }
        // Avoid overwriting a newer four-store import or another screen's edits.
        if let raw = try originalBytes() {
            guard try Self.parse(raw) == entries else {
                throw StudyError.message("오답이 다른 화면에서 변경됐습니다. 다시 열어주세요.")
            }
        } else if !entries.isEmpty {
            throw StudyError.message("기존 오답 원본이 없어 덮어쓰기를 차단했습니다.")
        }
        let bytes = try encoded(items)
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: url, options: .atomic)
        try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                             ofItemAtPath: url.path)
        guard try Data(contentsOf: url) == bytes else {
            throw StudyError.message("오답 저장 검증 실패")
        }
        entries = items
    }

    func save(_ item: StudyWrongAnswer) throws {
        var next = entries.filter { $0.id != item.id }
        next.append(item)
        try persist(next)
    }

    func exportBackup() throws -> URL {
        guard loadError == nil else { throw StudyError.message("오답 원본 오류") }
        if let original = try originalBytes() {
            guard try Self.parse(original) == entries else {
                throw StudyError.message("오답 원본이 변경됐습니다. 다시 열어 백업하세요.")
            }
        } else if !entries.isEmpty {
            throw StudyError.message("오답 원본을 찾을 수 없습니다.")
        }
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("StudyOne-wrong-answers-\(UUID().uuidString).json")
        try encoded(entries).write(to: output, options: .atomic)
        return output
    }

    /// Reload recovered wrong-answer notes without deleting the original file.
    func reloadAfterFourStoreRecovery() throws {
        if let raw = try originalBytes() {
            entries = try Self.parse(raw)
        } else {
            entries = []
        }
        loadError = nil
    }

    @discardableResult
    func importBackup(_ input: Data, expectedOriginal: Data?) throws -> URL {
        guard loadError == nil else { throw StudyError.message("오답 원본 오류") }
        let incoming = try Self.parse(input)
        let original = try originalBytes()
        guard original == expectedOriginal else {
            throw StudyError.message("승인 중 오답이 수정됐습니다. 다시 확인하세요.")
        }
        let folder = url.deletingLastPathComponent().appendingPathComponent(
            "BeforeWrongAnswerImport", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let backup = folder.appendingPathComponent("StudyOne-wrong-before-\(UUID().uuidString).bak")
        try (original ?? Data()).write(to: backup, options: .atomic)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: backup.path)
        try persist(incoming)
        return backup
    }
}

struct StudyWrongAnswersView: View {
    @StateObject private var store = StudyWrongAnswersStore()
    @State private var editing: StudyWrongAnswer?
    @State private var showingEditor = false
    @State private var exportURL: URL?
    @State private var pickingImport = false
    @State private var pendingData: Data?
    @State private var beforeImport: Data?
    @State private var confirmImport = false
    @State private var notice = ""
    @State private var errorText = ""
    @State private var showError = false
    @State private var writingTarget = ""
    @State private var writingAnswer = ""
    @State private var writingHidden = false
    @State private var writingResult = ""
    private var dueCount: Int {
        store.entries.filter { !$0.mastered && $0.nextReview <= Date().studyString() }.count
    }
    private func failure(_ error: Error) {
        errorText = error.localizedDescription
        showError = true
    }
    var body: some View {
        NavigationStack {
            List {
                Section("복습 현황") {
                    Text("오답 \(store.entries.count)개 · 오늘까지 복습 \(dueCount)개")
                    Text("오답·정답 근거·복습일은 오프라인 기기 저장입니다. ChatGPT/다른 기기에 자동 전송되지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    if let error = store.loadError {
                        Text(error).foregroundStyle(.red)
                    }
                }
                Section("오답 목록") {
                    if store.entries.isEmpty {
                        Text("등록된 오답이 없습니다. + 버튼으로 추가하세요.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(store.entries.sorted { $0.nextReview < $1.nextReview }) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            Text("[\(item.subject)] \(item.material) · \(item.topic)")
                                .font(.headline)
                            Text("정답 근거: \(item.correction)").font(.subheadline)
                            Text("복습일 \(item.nextReview) · 재풀이 \(item.retries)회\(item.mastered ? " · 숙달" : "")")
                                .font(.caption).foregroundStyle(.secondary)
                            HStack {
                                Button(item.mastered ? "복습 재개" : "재풀이 성공") {
                                    do {
                                        let day = item.mastered ? Date().studyString() :
                                            Calendar.current.date(byAdding: .day, value: 1, to: Date())!.studyString()
                                        try store.save(item.changed(
                                            retries: item.mastered ? item.retries : min(50,item.retries+1),
                                            nextReview: day, mastered: false))
                                    } catch { failure(error) }
                                }
                                Button(item.mastered ? "숙달 취소" : "숙달") {
                                    do { try store.save(item.changed(mastered: !item.mastered)) }
                                    catch { failure(error) }
                                }
                                Button("수정") { editing = item; showingEditor = true }
                            }.buttonStyle(.bordered).font(.caption)
                        }.padding(.vertical, 3)
                    }
                }
                Section("영어 쓰기 수행평가 연습") {
                    Text("문장을 가리고 직접 작성합니다. 이 연습 문장은 앱·AI·서버에 저장하거나 전송하지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    if !writingHidden {
                        TextField("암기할 영어 문장", text: $writingTarget, axis: .vertical)
                        Button("문장 가리고 시작") {
                            if !writingTarget.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                && writingTarget.count <= 1500 {
                                writingHidden = true
                                writingAnswer = ""
                                writingResult = ""
                            }
                        }
                        .disabled(writingTarget.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                  writingTarget.count > 1500)
                    } else {
                        TextField("기억나는 문장을 직접 쓰세요", text: $writingAnswer, axis: .vertical)
                            .lineLimit(3...6)
                        Button("채점하기") {
                            func clean(_ text: String) -> String {
                                text.split(whereSeparator: { $0.isWhitespace })
                                    .joined(separator: " ")
                            }
                            let correct = clean(writingTarget) == clean(writingAnswer)
                            writingResult = correct ? "정확하게 작성했습니다." :
                                "다른 부분이 있습니다. 정답: \(writingTarget)"
                        }
                        .disabled(writingAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        if !writingResult.isEmpty { Text(writingResult).font(.subheadline) }
                        Button("다른 문장 연습") {
                            writingTarget = ""; writingAnswer = ""
                            writingHidden = false; writingResult = ""
                        }
                    }
                }
                Section("데이터 백업·복원") {
                    Button("오답 JSON 백업 파일 준비") {
                        do { exportURL = try store.exportBackup(); notice = "백업 파일 준비 완료" }
                        catch { failure(error) }
                    }.disabled(store.loadError != nil)
                    if let exportURL {
                        ShareLink(item: exportURL) {
                            Label("오답 JSON 저장·공유", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button("오답 JSON 가져오기") { pickingImport = true }
                        .disabled(store.loadError != nil)
                    if !notice.isEmpty { Text(notice).font(.caption) }
                }
            }
            .navigationTitle("오답 관리")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { editing = nil; showingEditor = true } label: {
                        Image(systemName: "plus")
                    }.disabled(store.loadError != nil)
                }
            }
            .sheet(isPresented: $showingEditor) {
                StudyWrongAnswerEditor(existing: editing) { note in try store.save(note) }
            }
            .fileImporter(isPresented: $pickingImport, allowedContentTypes: [.json]) { result in
                do {
                    let url = try result.get()
                    guard url.startAccessingSecurityScopedResource() else {
                        throw StudyError.message("파일 접근 권한이 없습니다.")
                    }
                    defer { url.stopAccessingSecurityScopedResource() }
                    let bytes = try Data(contentsOf: url)
                    _ = try StudyWrongAnswersStore.parse(bytes)
                    beforeImport = try store.originalBytes()
                    pendingData = bytes
                    confirmImport = true
                } catch { failure(error) }
            }
            .confirmationDialog("오답 목록을 교체할까요?", isPresented: $confirmImport) {
                if let pendingData {
                    Button("검증한 오답으로 교체", role: .destructive) {
                        do {
                            try store.importBackup(pendingData, expectedOriginal: beforeImport)
                            notice = "오답 백업 복원 완료"
                        } catch { failure(error) }
                        self.pendingData = nil; beforeImport = nil
                    }
                }
                Button("취소", role: .cancel) { pendingData = nil; beforeImport = nil }
            } message: {
                Text("현재 오답만 교체하고 이전 원본을 앱 내부에 백업합니다. 일정·진도·계정 데이터는 유지됩니다.")
            }
            .alert("오답 관리 오류", isPresented: $showError) {
                Button("확인", role: .cancel) {}
            } message: { Text(errorText) }
        }
    }
}

struct StudyWrongAnswerEditor: View {
    @Environment(\.dismiss) private var dismiss
    let existing: StudyWrongAnswer?
    let onSave: (StudyWrongAnswer) throws -> Void
    @State private var subject = ""
    @State private var material = ""
    @State private var topic = ""
    @State private var correction = ""
    @State private var nextReview = Date().studyString()
    @State private var errorText = ""
    @State private var alert = false
    var body: some View {
        NavigationStack {
            Form {
                TextField("과목", text: $subject)
                TextField("교재·출처", text: $material)
                TextField("틀린 문제·개념", text: $topic, axis: .vertical)
                TextField("틀린 이유와 정답 근거", text: $correction, axis: .vertical)
                TextField("복습 날짜 YYYY-MM-DD", text: $nextReview)
            }
            .navigationTitle(existing == nil ? "오답 추가" : "오답 수정")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        do {
                            let note = try StudyWrongAnswer(
                                id: existing?.id ?? UUID().uuidString.lowercased(),
                                subject: subject.trimmingCharacters(in: .whitespacesAndNewlines),
                                material: material.trimmingCharacters(in: .whitespacesAndNewlines),
                                topic: topic.trimmingCharacters(in: .whitespacesAndNewlines),
                                correction: correction.trimmingCharacters(in: .whitespacesAndNewlines),
                                nextReview: nextReview.trimmingCharacters(in: .whitespacesAndNewlines),
                                retries: existing?.retries ?? 0,
                                mastered: existing?.mastered ?? false)
                            try onSave(note)
                            dismiss()
                        } catch { errorText = error.localizedDescription; alert = true }
                    }
                }
            }
            .onAppear {
                if let e = existing {
                    subject = e.subject; material = e.material; topic = e.topic
                    correction = e.correction; nextReview = e.nextReview
                }
            }
            .alert("오답 기록 오류", isPresented: $alert) {
                Button("확인", role: .cancel) {}
            } message: { Text(errorText) }
        }
    }
}
