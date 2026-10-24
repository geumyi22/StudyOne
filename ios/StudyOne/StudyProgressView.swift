import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A local textbook/exam progress workspace. This view never modifies daily tasks.
struct StudyProgressView: View {
    @StateObject private var progress = StudyProgressStore()
    @State private var filter = "전체"
    @State private var showingEditor = false
    @State private var editing: StudyProgressItem?
    @State private var deleting: StudyProgressItem?
    @State private var importPicker = false
    @State private var pendingImport: Data?
    @State private var pendingImportOriginal: [StudyProgressItem]?
    @State private var showOnlineAdd = false
    @State private var onlineOriginal: [StudyProgressItem] = []
    @State private var onlineSubjects: [String] = []
    @State private var importCount = 0
    @State private var exportURL: URL?
    @State private var backupNotice = ""
    @State private var showError = false
    @State private var errorText = ""

    private var visible: [StudyProgressItem] {
        progress.items.filter { filter == "전체" || $0.status.rawValue == filter }
            .sorted {
                if $0.subject != $1.subject { return $0.subject < $1.subject }
                if $0.material != $1.material { return $0.material < $1.material }
                return $0.unit < $1.unit
            }
    }

    private var examItems: [StudyProgressItem] { progress.items.filter(\.inExamScope) }
    private var finishedCount: Int { examItems.filter { $0.status == .done }.count }
    private var startedCount: Int { examItems.filter { $0.status == .inProgress }.count }
    private var todoCount: Int { examItems.filter { $0.status == .todo }.count }
    private var unknownCount: Int { examItems.filter { $0.status == .unknown }.count }

    /// Export for deliberate, on-device clipboard copying only. No network upload.
    private var checklistText: String {
        let sorted = progress.items.sorted {
            if $0.subject != $1.subject { return $0.subject < $1.subject }
            if $0.material != $1.material { return $0.material < $1.material }
            return $0.unit < $1.unit
        }
        guard !sorted.isEmpty else {
            return "StudyOne 교재·시험범위 진도 체크리스트\n등록된 진도가 없습니다."
        }
        return (["StudyOne 교재·시험범위 진도 체크리스트"] + sorted.map { item in
            let mark = item.status == .done ? "☑" : "☐"
            let scope = item.inExamScope ? "시험범위 포함" : "시험범위 제외"
            return "\(mark) [\(item.subject)] \(item.material) · \(item.unit) — \(item.status.rawValue) (\(scope))"
        }).joined(separator: "\n")
    }

    private func error(_ message: String) {
        errorText = message
        showError = true
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("시험범위 진도").font(.headline)
                        Text("완료 \(finishedCount)/\(examItems.count)개 · 진행 중 \(startedCount) · 미완료 \(todoCount) · 확인 필요 \(unknownCount)")
                            .font(.subheadline).foregroundStyle(.secondary)
                        ProgressView(value: Double(finishedCount), total: Double(max(1, examItems.count)))
                    }
                    .padding(.vertical, 5)
                }
                Section {
                    Picker("진도 상태", selection: $filter) {
                        Text("전체").tag("전체")
                        ForEach(StudyProgressStatus.allCases) { value in
                            Text(value.rawValue).tag(value.rawValue)
                        }
                    }
                    .pickerStyle(.menu)
                    Text("교재·소단원·모의고사·학습지·전체 오답 복습을 별도로 기록할 수 있습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("온라인 내신 문제") {
                    Button {
                        onlineOriginal = progress.items
                        onlineSubjects = StudyOnlinePractice.missingSubjects(in: onlineOriginal)
                        if onlineSubjects.isEmpty {
                            backupNotice = "6과목 모두 이미 등록되어 있습니다. 기존 상태를 유지했습니다."
                        } else {
                            showOnlineAdd = true
                        }
                    } label: {
                        Label("6과목 온라인 내신 문제 빠른 추가", systemImage: "plus.circle")
                    }
                    .disabled(progress.loadError != nil)
                    Text("없는 과목만 미완료로 추가합니다. 기존 완료·진행 중 기록은 건너뜁니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let failure = progress.loadError {
                    Section("읽기 오류") {
                        Text(failure).foregroundStyle(.red)
                    }
                }
                Section("진도 항목 \(visible.count)개") {
                    if visible.isEmpty {
                        Text("등록된 진도가 없습니다. + 버튼으로 추가해 보세요.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(visible) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Button {
                                    do {
                                        try progress.update(
                                            item, subject: item.subject, material: item.material,
                                            unit: item.unit,
                                            status: item.status == .done ? .todo : .done,
                                            inExamScope: item.inExamScope
                                        )
                                    } catch { self.error(error.localizedDescription) }
                                } label: {
                                    Image(systemName: item.status == .done ? "checkmark.circle.fill" : "circle")
                                        .font(.title2)
                                        .foregroundStyle(item.status == .done ? Color.green : Color.secondary)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("[\(item.subject)] \(item.unit) 완료 체크")
                                .accessibilityValue(item.status == .done ? "완료" : "미완료")
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("[\(item.subject)] \(item.unit)").font(.headline)
                                    Text("\(item.subject) · \(item.material)")
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 8)
                                Menu {
                                    ForEach(StudyProgressStatus.allCases) { status in
                                        Button(status.rawValue) {
                                            do {
                                                try progress.update(item, subject: item.subject,
                                                                    material: item.material, unit: item.unit,
                                                                    status: status, inExamScope: item.inExamScope)
                                            } catch { self.error(error.localizedDescription) }
                                        }
                                    }
                                } label: {
                                    Text(item.status.rawValue).font(.caption.bold())
                                        .padding(.horizontal, 9).padding(.vertical, 6)
                                        .background(.quaternary, in: Capsule())
                                }
                            }
                            Text(item.inExamScope ? "시험범위 포함" : "시험범위 제외")
                                .font(.caption).foregroundStyle(.secondary)
                            HStack(spacing: 12) {
                                Button {
                                    editing = item
                                    showingEditor = true
                                } label: { Label("수정", systemImage: "pencil") }
                                    .buttonStyle(.borderless)
                                Spacer()
                                Button(role: .destructive) {
                                    deleting = item
                                } label: { Label("삭제", systemImage: "trash") }
                                    .buttonStyle(.borderless)
                            }
                            .font(.caption)
                        }
                        .padding(.vertical, 3)
                    }
                }
                Section("삼성 리마인더용 진도 체크리스트") {
                    Button {
                        UIPasteboard.general.string = checklistText
                        backupNotice = "과목명이 포함된 진도 체크리스트를 복사했습니다."
                    } label: {
                        Label("과목명 포함 진도 목록 복사", systemImage: "doc.on.doc")
                    }
                    Text("체크된 진도는 ☑, 나머지는 ☐로 표시합니다. 상태 및 시험범위 포함 여부도 복사됩니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("진도 데이터 백업·복원") {
                    Text("진도만 별도 JSON 파일로 내보낼 수 있습니다. 기존 학교·일정 백업과는 다른 파일입니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("진도 JSON 백업 파일 준비") {
                        do {
                            exportURL = try progress.exportBackup()
                            backupNotice = "진도 백업 파일을 준비했습니다."
                        } catch { self.error(error.localizedDescription) }
                    }
                    if let exportURL {
                        ShareLink(item: exportURL) {
                            Label("진도 JSON 공유·저장", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button("진도 JSON 백업 불러오기") { importPicker = true }
                    if !backupNotice.isEmpty {
                        Text(backupNotice).font(.caption).foregroundStyle(.secondary)
                    }
                    Text("여전히 기기별 로컬 데이터입니다. ChatGPT나 다른 기기로 실시간 자동 동기화되지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("교재·진도")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editing = nil
                        showingEditor = true
                    } label: { Image(systemName: "plus") }
                        .disabled(progress.loadError != nil)
                }
            }
            .sheet(isPresented: $showingEditor) {
                StudyProgressEditor(existing: editing) { subject, material, unit, status, scope in
                    if let item = editing {
                        try progress.update(item, subject: subject, material: material,
                                            unit: unit, status: status, inExamScope: scope)
                    } else {
                        try progress.add(subject: subject, material: material,
                                         unit: unit, status: status, inExamScope: scope)
                    }
                }
            }
            .fileImporter(isPresented: $importPicker, allowedContentTypes: [.json]) { result in
                do {
                    let file = try result.get()
                    guard file.startAccessingSecurityScopedResource() else {
                        throw StudyError.message("진도 백업 파일 접근 권한이 없습니다.")
                    }
                    defer { file.stopAccessingSecurityScopedResource() }
                    let bytes = try Data(contentsOf: file)
                    guard bytes.count <= 2_000_000 else {
                        throw StudyError.message("진도 백업은 최대 2MB입니다.")
                    }
                    let parsed = try JSONDecoder().decode(StudyProgressTransfer.self, from: bytes)
                    let ids = parsed.items.map(\.id)
                    guard parsed.format == "studyone-progress", parsed.schema == 1,
                          parsed.items.count <= 2000, Set(ids).count == ids.count,
                          parsed.items.allSatisfy(\.valid) else {
                        throw StudyError.message("지원되지 않거나 손상된 진도 백업입니다.")
                    }
                    importCount = parsed.items.count
                    pendingImportOriginal = progress.items
                    pendingImport = bytes
                } catch { self.error(error.localizedDescription) }
            }
            .confirmationDialog(
                pendingImport == nil ? "진도 항목을 삭제할까요?" : "진도 백업으로 기존 데이터를 교체할까요?",
                isPresented: Binding(
                    get: { deleting != nil || pendingImport != nil },
                    set: {
                        if !$0 {
                            deleting = nil
                            pendingImport = nil
                            pendingImportOriginal = nil
                        }
                    }
                ), titleVisibility: .visible
            ) {
                if let item = deleting {
                    Button("삭제", role: .destructive) {
                        do { try progress.remove(item) }
                        catch { self.error(error.localizedDescription) }
                        deleting = nil
                    }
                }
                if let bytes = pendingImport {
                    Button("진도 \(importCount)개로 교체", role: .destructive) {
                        do {
                            guard let original = pendingImportOriginal else {
                                throw StudyError.message("가져오기 미리보기를 다시 확인하세요.")
                            }
                            let recovery = try progress.importBackup(data: bytes, original: original)
                            backupNotice = "진도를 복원했습니다. 내부 복구본: \(recovery.lastPathComponent)"
                        } catch { self.error(error.localizedDescription) }
                        pendingImport = nil
                        pendingImportOriginal = nil
                    }
                }
                Button("취소", role: .cancel) {
                    deleting = nil
                    pendingImport = nil
                    pendingImportOriginal = nil
                }
            } message: {
                Text(pendingImport == nil
                     ? (deleting.map { "\($0.subject) · \($0.unit)" } ?? "")
                     : "현재 이 기기의 진도만 교체됩니다. 학교·숙제·시험 일정은 그대로 보존됩니다. 승인하면 기존 진도 원본을 앱 내부에 자동 백업합니다.")
            }
            .confirmationDialog("온라인 내신 문제 빠른 추가", isPresented: $showOnlineAdd,
                                titleVisibility: .visible) {
                Button("신규 \(onlineSubjects.count)개만 추가") {
                    do {
                        let (count, backup) = try progress.addMissingOnlinePractice(
                            original: onlineOriginal)
                        backupNotice = "신규 \(count)개를 추가했습니다. 내부 복구본: \(backup.lastPathComponent)"
                    } catch { self.error(error.localizedDescription) }
                }
                Button("취소", role: .cancel) {}
            } message: {
                Text("새로 추가할 과목: \(onlineSubjects.joined(separator: ", "))\n"
                     + "상태는 미완료이며, 원본은 앱 내부에 백업합니다. 기존 진도 상태는 변경하지 않습니다.")
            }
            .alert("진도 관리 오류", isPresented: $showError) {
                Button("확인", role: .cancel) {}
            } message: { Text(errorText) }
        }
    }
}

struct StudyProgressEditor: View {
    @Environment(\.dismiss) private var dismiss
    let existing: StudyProgressItem?
    let save: (String, String, String, StudyProgressStatus, Bool) throws -> Void

    @State private var subject = ""
    @State private var material = ""
    @State private var unit = ""
    @State private var status: StudyProgressStatus = .todo
    @State private var inExamScope = true
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("시험범위와 교재") {
                    TextField("과목 (예: 과학)", text: $subject)
                    TextField("교재 (예: 오투)", text: $material)
                    TextField("항목 (예: 7-2 또는 전체 오답 복습)", text: $unit, axis: .vertical)
                        .lineLimit(1...3)
                    Toggle("시험범위에 포함", isOn: $inExamScope)
                }
                Section("진행 상태") {
                    Picker("상태", selection: $status) {
                        ForEach(StudyProgressStatus.allCases) { option in
                            Text(option.rawValue).tag(option)
                        }
                    }.pickerStyle(.menu)
                }
                if let errorText {
                    Section("입력 확인") {
                        Text(errorText).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(existing == nil ? "진도 추가" : "진도 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        do {
                            try save(subject, material, unit, status, inExamScope)
                            dismiss()
                        } catch {
                            errorText = error.localizedDescription
                        }
                    }
                    .disabled(subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              material.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              unit.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                guard let item = existing else { return }
                subject = item.subject
                material = item.material
                unit = item.unit
                status = item.status
                inExamScope = item.inExamScope
            }
        }
    }
}
