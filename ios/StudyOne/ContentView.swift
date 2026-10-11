import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    var body: some View {
        TabView {
            DashboardView().tabItem { Label("홈", systemImage: "house") }
            SchoolView().tabItem { Label("시간표·급식", systemImage: "calendar") }
            TasksView().tabItem { Label("일정", systemImage: "checklist") }
            AiView().tabItem { Label("AI", systemImage: "sparkles") }
            SettingsView().tabItem { Label("설정", systemImage: "gearshape") }
        }.tint(.indigo)
    }
}
private func dayTitle(_ text: String) -> String {
    guard text.count == 8 else { return text }
    let month = String(text.dropFirst(4).prefix(2))
    let day = String(text.suffix(2))
    return "\(Int(month) ?? 0)월 \(Int(day) ?? 0)일"
}
struct DashboardView: View {
    @EnvironmentObject private var store: StudyOneStore
    private var today: String { Date().studyString("yyyyMMdd") }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(store.school?.name ?? "학교를 설정해 주세요").font(.title2.bold())
                        Text("\(store.grade)학년 \(store.className)반 · \(Date().studyString())")
                            .foregroundStyle(.secondary)
                        Text("오늘 할 일을 한곳에서 관리하세요.")
                    }.padding(.vertical, 12)
                }
                Section("우선 학습") {
                    Text(store.offlineSuggestion).font(.subheadline)
                    ForEach(Array(store.priorityTasks.prefix(3))) { task in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(task.title).bold()
                            Text("\(task.type) · \(task.subject) · \(task.dueDate)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section("오늘의 수업") {
                    let todays = store.timetable.filter { $0.date == today }
                    if todays.isEmpty {
                        Text("조회된 수업이 없습니다. 학교 설정 또는 NEIS 조회 결과를 확인해 주세요.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(todays) { entry in
                            HStack {
                                Text("\(entry.period)교시").foregroundStyle(.secondary)
                                Spacer()
                                Text(entry.subject).bold()
                            }
                        }
                    }
                }
                Section("오늘의 중식") {
                    if let meal = store.meals.first(where: { $0.date == today }) {
                        Text(meal.menu)
                        if !meal.kcal.isEmpty { Text(meal.kcal).font(.caption).foregroundStyle(.secondary) }
                    } else { Text("조회된 급식이 없습니다.").foregroundStyle(.secondary) }
                }
                if !store.schoolError.isEmpty {
                    Section("조회 상태") { Text(store.schoolError).foregroundStyle(.orange) }
                }
            }
            .navigationTitle("StudyOne")
            .task { await store.reloadSchool() }
        }
    }
}
struct TasksView: View {
    @EnvironmentObject private var store: StudyOneStore
    @State private var showingEditor = false
    @State private var editing: StudyTask?
    @State private var filter = "전체"
    private let filters = ["전체", "과제", "수행평가", "시험", "준비물"]
    private var filtered: [StudyTask] {
        store.tasks.filter { filter == "전체" || $0.type == filter }
            .sorted { $0.dueDate == $1.dueDate ? $0.importance > $1.importance : $0.dueDate < $1.dueDate }
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("구분", selection: $filter) {
                        ForEach(filters, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(.menu)
                }
                Section("일정 \(filtered.count)개") {
                    if filtered.isEmpty { Text("등록된 일정이 없습니다.").foregroundStyle(.secondary) }
                    ForEach(filtered) { item in
                        HStack(spacing: 12) {
                            Button { store.toggle(item) } label: {
                                Image(systemName: item.completed ? "checkmark.circle.fill" : "circle").font(.title2)
                            }.buttonStyle(.borderless)
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.title).fontWeight(.semibold).strikethrough(item.completed)
                                Text("\(item.type) · \(item.subject) · \(item.dueDate) · 중요도 \(item.importance)")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            Button {
                                editing = item
                                showingEditor = true
                            } label: { Image(systemName: "pencil").foregroundStyle(.secondary) }
                                .buttonStyle(.borderless)
                        }.padding(.vertical, 4)
                    }
                    .onDelete { store.delete(at: $0, from: filtered) }
                }
            }
            .navigationTitle("학습 일정")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { editing = nil; showingEditor = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showingEditor) {
                TaskEditor(existing: editing) { newTask in
                    if let idx = store.tasks.firstIndex(where: { $0.id == newTask.id }) {
                        store.tasks[idx] = newTask
                        store.persist()
                    } else { store.add(newTask) }
                }
            }
        }
    }
}
struct TaskEditor: View {
    @Environment(\.dismiss) private var dismiss
    let existing: StudyTask?
    let onSave: (StudyTask) -> Void
    @State private var type = "과제"
    @State private var subject = ""
    @State private var title = ""
    @State private var due = Date()
    @State private var importance = 2
    var body: some View {
        NavigationStack {
            Form {
                Picker("일정 종류", selection: $type) {
                    ForEach(["과제", "수행평가", "시험", "준비물"], id: \.self) { Text($0) }
                }
                TextField("과목", text: $subject)
                TextField("할 일", text: $title, axis: .vertical).lineLimit(2...4)
                DatePicker("마감일", selection: $due, displayedComponents: .date)
                Picker("중요도", selection: $importance) {
                    Text("낮음").tag(1)
                    Text("보통").tag(2)
                    Text("높음").tag(3)
                }
            }
            .navigationTitle(existing == nil ? "일정 추가" : "일정 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        let t = StudyTask(id: existing?.id ?? Int64(Date().timeIntervalSince1970 * 1000),
                                          type: type, subject: subject.trimmingCharacters(in: .whitespaces),
                                          title: title.trimmingCharacters(in: .whitespaces),
                                          dueDate: due.studyString(), importance: importance,
                                          completed: existing?.completed ?? false)
                        onSave(t)
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || title.count > 500)
                }
            }
            .onAppear {
                guard let t = existing else { return }
                type = t.type; subject = t.subject; title = t.title; importance = t.importance
                let fmt = DateFormatter()
                fmt.dateFormat = "yyyy-MM-dd"; fmt.locale = Locale(identifier: "en_US_POSIX")
                due = fmt.date(from: t.dueDate) ?? Date()
            }
        }
    }
}
struct SchoolView: View {
    @EnvironmentObject private var store: StudyOneStore
    @State private var name = ""
    @State private var results: [School] = []
    @State private var loadingSearch = false
    @State private var searchError = ""
    @State private var editGrade = 1
    @State private var editClass = "1"
    var body: some View {
        NavigationStack {
            List {
                Section("현재 학교") {
                    Text(store.school?.name ?? "미설정")
                    if let s = store.school { Text(s.address).font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        Picker("학년", selection: $editGrade) {
                            ForEach(1...6, id: \.self) { Text("\($0)학년").tag($0) }
                        }
                        TextField("반", text: $editClass)
                            .keyboardType(.numberPad)
                            .frame(width: 65)
                            .textFieldStyle(.roundedBorder)
                        Button("적용") {
                            store.setClass(grade: editGrade, className: editClass)
                            Task { await store.reloadSchool(force: true) }
                        }
                        .disabled(editClass.isEmpty || editClass.count > 3 || !editClass.allSatisfy({ $0.isNumber }))
                    }
                }
                Section("학교 검색") {
                    HStack {
                        TextField("학교 이름 (2글자 이상)", text: $name)
                            .textInputAutocapitalization(.never)
                        Button("검색") {
                            loadingSearch = true
                            searchError = ""
                            Task {
                                do { results = try await NeisService.searchSchool(key: SecretVault.load("neis"), name: name) }
                                catch { results = []; searchError = error.localizedDescription }
                                loadingSearch = false
                            }
                        }
                        .disabled(loadingSearch || name.trimmingCharacters(in: .whitespaces).count < 2)
                    }
                    if loadingSearch { ProgressView("검색 중") }
                    if !searchError.isEmpty { Text(searchError).foregroundStyle(.orange) }
                    ForEach(results) { school in
                        Button {
                            store.setSchool(school)
                            results = []
                            Task { await store.reloadSchool(force: true) }
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(school.name) · \(school.kind)").fontWeight(.semibold)
                                Text(school.address).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section("이번 주 시간표") {
                    Button { Task { await store.reloadSchool(force: true) } } label: {
                        Label("NEIS 새로고침", systemImage: "arrow.clockwise")
                    }.disabled(store.loadingSchool)
                    if store.loadingSchool { ProgressView("조회 중") }
                    if let cache = store.cacheDate {
                        Text("마지막 정상 조회: \(cache.formatted(date: .abbreviated, time: .shortened))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if !store.schoolError.isEmpty { Text(store.schoolError).foregroundStyle(.orange) }
                    if store.timetable.isEmpty { Text("조회된 시간표가 없습니다.").foregroundStyle(.secondary) }
                    ForEach(store.timetable) { t in
                        HStack {
                            Text(dayTitle(t.date) + " · \(t.period)교시").foregroundStyle(.secondary)
                            Spacer()
                            Text(t.subject)
                        }
                    }
                }
                Section("이번 주 중식") {
                    if store.meals.isEmpty { Text("조회된 급식이 없습니다.").foregroundStyle(.secondary) }
                    ForEach(store.meals) { meal in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(dayTitle(meal.date)).fontWeight(.semibold)
                            Text(meal.menu)
                            if !meal.kcal.isEmpty { Text(meal.kcal).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            .navigationTitle("학교 생활")
            .onAppear { editGrade = store.grade; editClass = store.className }
        }
    }
}
struct AiView: View {
    @EnvironmentObject private var store: StudyOneStore
    @State private var mode = "질문 설명"
    @State private var question = ""
    @State private var minutes = 60
    @State private var includeTasks = false
    @State private var preview = ""
    @State private var showConsent = false
    @State private var answer = ""
    @State private var errorText = ""
    @State private var busy = false
    var body: some View {
        NavigationStack {
            Form {
                Section("AI 학습 도우미") {
                    Picker("사용 방식", selection: $mode) {
                        ForEach(["질문 설명", "복습 퀴즈", "맞춤 공부 계획"], id: \.self) { Text($0) }
                    }
                    TextField("질문 또는 공부 목표", text: $question, axis: .vertical).lineLimit(3...6)
                    if mode == "맞춤 공부 계획" {
                        Stepper("공부 시간: \(minutes)분", value: $minutes, in: 20...600, step: 10)
                    }
                    Toggle("미완료 과제 정보를 이번 요청에 포함", isOn: $includeTasks)
                    Text("과제 전송은 기본적으로 꺼져 있습니다. 전송 내용은 요청할 때마다 확인할 수 있습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button {
                        do {
                            preview = try AiService.input(mode: mode, question: question, minutes: minutes,
                                                          tasks: store.tasks, includeTasks: includeTasks)
                            errorText = ""
                            showConsent = true
                        } catch { errorText = error.localizedDescription }
                    } label: { Label(busy ? "요청 중" : "요청 내용 확인", systemImage: "paperplane") }
                    .disabled(busy)
                    if busy { ProgressView() }
                    if !errorText.isEmpty { Text(errorText).foregroundStyle(.orange) }
                }
                Section("AI 답변") {
                    if answer.isEmpty { Text("요청하면 여기에 결과가 표시됩니다.").foregroundStyle(.secondary) }
                    else { Text(answer).textSelection(.enabled) }
                }
                Section("키 없이도 사용할 수 있는 학습 추천") {
                    Text(store.offlineSuggestion).font(.subheadline)
                }
                Section {
                    Text("개인 OpenAI API 키를 사용하는 직접 호출 방식입니다. 실제 사용량에 따라 API 요금이 부과될 수 있습니다. AI의 설명은 검증해 주세요.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("AI 학습")
            .sheet(isPresented: $showConsent) {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("다음 JSON 정보가 OpenAI로 전송됩니다. 과제 포함 여부와 내용을 확인해 주세요.")
                            Text(preview)
                                .font(.system(.footnote, design: .monospaced))
                                .textSelection(.enabled)
                                .padding()
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                        }.padding()
                    }
                    .navigationTitle("전송 전 확인")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("취소") { showConsent = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("동의하고 전송") {
                                showConsent = false
                                busy = true
                                errorText = ""
                                let snapshot = preview
                                Task {
                                    do { answer = try await AiService.ask(apiKey: SecretVault.load("openai"), input: snapshot) }
                                    catch { errorText = error.localizedDescription }
                                    busy = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
struct SettingsView: View {
    @EnvironmentObject private var store: StudyOneStore
    @State private var neisInput = ""
    @State private var openaiInput = ""
    @State private var neisSet = false
    @State private var openaiSet = false
    @State private var notice = ""
    @State private var exportURL: URL?
    @State private var importPicker = false
    @State private var confirmingImport = false
    @State private var importedBackup: Data?
    var body: some View {
        NavigationStack {
            Form {
                Section("NEIS API 키") {
                    Text(neisSet ? "키가 보안 저장소에 설정됨" : "키가 아직 설정되지 않음")
                        .font(.caption).foregroundStyle(.secondary)
                    SecureField("새 NEIS API 키", text: $neisInput)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("NEIS 키 저장") {
                        do {
                            try SecretVault.save(neisInput, account: "neis")
                            neisSet = !SecretVault.load("neis").isEmpty
                            neisInput = ""
                            notice = "NEIS 키 저장 완료"
                        } catch { notice = error.localizedDescription }
                    }.disabled(neisInput.trimmingCharacters(in: .whitespaces).isEmpty)
                    Button("NEIS 키 삭제", role: .destructive) {
                        try? SecretVault.save("", account: "neis")
                        neisSet = false
                    }
                }
                Section("OpenAI API 키") {
                    Text(openaiSet ? "키가 보안 저장소에 설정됨" : "키가 아직 설정되지 않음")
                        .font(.caption).foregroundStyle(.secondary)
                    SecureField("새 OpenAI API 키 (sk-...)", text: $openaiInput)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("OpenAI 키 저장") {
                        do {
                            guard openaiInput.hasPrefix("sk-"), openaiInput.count >= 16,
                                  openaiInput.count <= 512 else { throw StudyError.message("OpenAI API 키 형식을 확인해 주세요.") }
                            try SecretVault.save(openaiInput, account: "openai")
                            openaiSet = !SecretVault.load("openai").isEmpty
                            openaiInput = ""
                            notice = "OpenAI 키 저장 완료"
                        } catch { notice = error.localizedDescription }
                    }.disabled(openaiInput.isEmpty)
                    Button("OpenAI 키 삭제", role: .destructive) {
                        try? SecretVault.save("", account: "openai")
                        openaiSet = false
                    }
                }
                Section("백업 및 복원") {
                    Text("Android StudyOne의 schema 1 JSON 백업과 동일한 구조를 사용합니다. API 키는 포함되지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("백업 파일 준비") {
                        do { exportURL = try store.exportBackup(); notice = "파일을 내보낼 준비가 됐습니다." }
                        catch { notice = error.localizedDescription }
                    }
                    if let exportURL {
                        ShareLink(item: exportURL) {
                            Label("JSON 백업 공유·저장", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button("JSON 백업 불러오기") { importPicker = true }
                }
                Section("앱 정보") {
                    LabeledContent("버전", value: "3.0.0-beta.1 · iOS")
                    LabeledContent("번들 ID", value: "com.studyone.ios")
                    Text("Android APK의 데이터는 자동으로 이전되지 않습니다. 기존 Android 앱에서 JSON 백업을 내보낸 뒤 iOS에서 복원할 수 있습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if !notice.isEmpty { Section("작업 결과") { Text(notice) } }
            }
            .navigationTitle("설정")
            .onAppear {
                neisSet = !SecretVault.load("neis").isEmpty
                openaiSet = !SecretVault.load("openai").isEmpty
            }
            .fileImporter(isPresented: $importPicker, allowedContentTypes: [.json]) { result in
                do {
                    let file = try result.get()
                    guard file.startAccessingSecurityScopedResource() else {
                        throw StudyError.message("파일 접근 권한이 없습니다.")
                    }
                    defer { file.stopAccessingSecurityScopedResource() }
                    importedBackup = try Data(contentsOf: file)
                    confirmingImport = true
                } catch { notice = error.localizedDescription }
            }
            .confirmationDialog("기존 학교·일정 데이터를 백업 파일로 교체할까요?",
                                isPresented: $confirmingImport, titleVisibility: .visible) {
                Button("백업 복원", role: .destructive) {
                    guard let data = importedBackup else { return }
                    do { try store.importBackup(from: data); notice = "백업 복원 완료" }
                    catch { notice = error.localizedDescription }
                    importedBackup = nil
                }
                Button("취소", role: .cancel) { importedBackup = nil }
            }
        }
    }
}
