import SwiftUI

/// A local textbook/exam progress workspace. This view never modifies daily tasks.
struct StudyProgressView: View {
    @StateObject private var progress = StudyProgressStore()
    @State private var filter = "전체"
    @State private var showingEditor = false
    @State private var editing: StudyProgressItem?
    @State private var deleting: StudyProgressItem?
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
                        Text("완료 \(finishedCount)/\(examItems.count)개 · 확인 필요 \(examItems.filter { $0.status == .unknown }.count)개")
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
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.unit).font(.headline)
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
                Section {
                    Text("진도는 현재 기기에만 저장됩니다. 이 개발 단계의 진도 데이터는 기존 schema-1 JSON 백업에 포함되지 않으며, Android·iOS·ChatGPT와 자동 동기화되지 않습니다.")
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
            .confirmationDialog("진도 항목을 삭제할까요?", isPresented: Binding(
                get: { deleting != nil },
                set: { if !$0 { deleting = nil } }
            )) {
                Button("삭제", role: .destructive) {
                    if let item = deleting {
                        do { try progress.remove(item) }
                        catch { self.error(error.localizedDescription) }
                    }
                    deleting = nil
                }
                Button("취소", role: .cancel) { deleting = nil }
            } message: {
                Text(deleting.map { "\($0.subject) · \($0.unit)" } ?? "")
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
