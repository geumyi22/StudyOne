import SwiftUI
import UIKit

/// On-device preview editor. Intentionally has no StudyOneStore or network.
/// Copying the resulting plan is not consent to insert real tasks.
struct StudyPaperScheduleView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var tasks: StudyOneStore
    @ObservedObject var progress: StudyProgressStore
    @State private var expectedTasks: [StudyTask]?
    @State private var autofillProgress: [StudyProgressItem]?
    @State private var autofillStamp: String?
    @State private var approvedInputStamp: String?
    @State private var showingConfirmation = false
    @State private var startDate = Date().studyString()
    @State private var endDate = Calendar.current.date(byAdding: .day, value: 8, to: Date())?
        .studyString() ?? Date().studyString()
    @State private var dailyLimit = 2
    @State private var firstReview = "학습지"
    @State private var skipDates = ""
    @State private var counts = Array(repeating: "3/0", count: 6)
    // New generic mode is opt-in; the original Korean 18-round editor stays intact.
    private struct EditablePaper: Identifiable {
        let id = UUID()
        var unit = "1단원"
        var title = ""
        var rounds = "1/0"
    }
    @State private var genericMode = false
    @State private var genericSubject = ""
    @State private var genericMaterial = ""
    @State private var genericRows: [EditablePaper] = [EditablePaper()]
    @State private var result: StudyPaperSchedule.Preview?
    @State private var notice = ""
    @State private var errorText = ""
    @State private var showError = false

    private let names = [
        "2-1 소단원 평가", "2-2 소단원 평가", "2단원 대단원 평가",
        "3-1 소단원 평가", "3-2 소단원 평가", "3단원 대단원 평가"
    ]

    private var inputStamp: String {
        let rows = genericRows.flatMap { [$0.unit, $0.title, $0.rounds] }
        return ([(genericMode ? "generic" : "legacy"), genericSubject, genericMaterial,
                 startDate, endDate, String(dailyLimit), firstReview, skipDates] +
                counts + rows).joined(separator: "\u{001F}")
    }

    private func customLanes() throws -> [StudyPaperSchedule.Lane] {
        guard (1...160).contains(genericRows.count) else {
            throw StudyError.message("시험지 종류는 1~160개를 입력하세요.")
        }
        var order = [String]()
        var grouped = [String: [StudyPaperSchedule.Paper]]()
        for row in genericRows {
            let parts = row.rounds.trimmingCharacters(in: .whitespacesAndNewlines)
                .split(separator: "/", omittingEmptySubsequences: false)
            guard parts.count == 2,
                  let total = Int(parts[0].trimmingCharacters(in: .whitespaces)),
                  let done = Int(parts[1].trimmingCharacters(in: .whitespaces)) else {
                throw StudyError.message("총/연속 완료 회차는 예: 3/1 형식으로 입력하세요.")
            }
            if grouped[row.unit] == nil { order.append(row.unit); grouped[row.unit] = [] }
            grouped[row.unit, default: []].append(
                StudyPaperSchedule.Paper(title: row.title, total: total, completed: done))
        }
        return order.map { StudyPaperSchedule.Lane(name: $0, papers: grouped[$0]!) }
    }

    private func autoFillFromDevice() {
        do {
            let original = try progress.checkedPaperPreviewItems()
            var verifiedCount = 0
            if genericMode {
                let mapped = try StudyPaperGenericProgressLink.checked(
                    original, subject: genericSubject, material: genericMaterial,
                    template: customLanes())
                verifiedCount = mapped.completed
                for index in genericRows.indices {
                    let row = genericRows[index]
                    guard let paper = mapped.lanes.first(where: { $0.name == row.unit })?
                        .papers.first(where: { $0.title == row.title }) else {
                        throw StudyError.message("정확한 개별 진도를 연결하지 못했습니다.")
                    }
                    genericRows[index].rounds = "\(paper.total)/\(paper.completed)"
                }
            } else {
                let verified = try StudyPaperProgressLink.checked(original)
                verifiedCount = verified.completed
                for i in names.indices {
                    let paper = i < 3 ? verified.lanes[0].papers[i]
                                      : verified.lanes[1].papers[i - 3]
                    counts[i] = "\(paper.total)/\(paper.completed)"
                }
            }
            autofillProgress = original
            autofillStamp = inputStamp
            expectedTasks = nil
            result = nil
            approvedInputStamp = nil
            notice = "개별 회차의 UUID와 순서가 검증된 \(verifiedCount)회 완료를 확인했습니다."
        } catch {
            errorText = error.localizedDescription
            showError = true
        }
    }

    private func approveReviewedPlan() {
        do {
            guard let result, let expectedTasks, approvedInputStamp == inputStamp else {
                throw StudyError.message("미리보기 이후 입력이 변경됐습니다. 다시 계산하세요.")
            }
            if let original = autofillProgress {
                guard autofillStamp == inputStamp,
                      try progress.checkedPaperPreviewItems() == original else {
                    throw StudyError.message("미리보기 이후 진도가 변경됐습니다. 다시 확인하세요.")
                }
            }
            let added = try StudyPaperPlanApproval.prepare(
                result, existing: expectedTasks,
                subject: genericMode ? genericSubject : "국어")
            let backup = try tasks.mergeNewTasksFromServer(added, original: expectedTasks)
            notice = "\(added.count)개 숙제를 전체 추가했습니다. 원본 복구본: \(backup.lastPathComponent)"
            self.result = nil
            self.expectedTasks = nil
            self.approvedInputStamp = nil
        } catch {
            errorText = error.localizedDescription
            showError = true
        }
    }

    private func calculate() {
        do {
            var items = [StudyPaperSchedule.Paper]()
            if genericMode {
                guard !genericSubject.isEmpty,
                      genericSubject == genericSubject.trimmingCharacters(in: .whitespacesAndNewlines),
                      genericSubject.utf16.count <= 60 else {
                    throw StudyError.message("과목은 공백 없이 1~60자로 입력하세요.")
                }
            }
            for index in names.indices {
                let parts = counts[index].trimmingCharacters(in: .whitespacesAndNewlines)
                    .split(separator: "/", omittingEmptySubsequences: false)
                guard parts.count == 2,
                      let total = Int(parts[0].trimmingCharacters(in: .whitespaces)),
                      let done = Int(parts[1].trimmingCharacters(in: .whitespaces)) else {
                    throw StudyError.message("\(names[index])은 '총/완료' 형식으로 입력하세요. 예: 3/1")
                }
                items.append(StudyPaperSchedule.Paper(title: names[index],
                                                       total: total, completed: done))
            }
            let exclusions = skipDates.trimmingCharacters(in: .whitespacesAndNewlines)
            let skipped = exclusions.isEmpty ? [] : exclusions
                .split(separator: ",", omittingEmptySubsequences: false)
                .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            let verifiedTasks = try tasks.checkedTasksForSync()
            let requestedLanes = try genericMode ? customLanes() : [
                StudyPaperSchedule.Lane(name: "2단원", papers: Array(items.prefix(3))),
                StudyPaperSchedule.Lane(name: "3단원", papers: Array(items.suffix(3)))
            ]
            let calculated = try StudyPaperSchedule.preview(
                start: startDate.trimmingCharacters(in: .whitespacesAndNewlines),
                end: endDate.trimmingCharacters(in: .whitespacesAndNewlines),
                dailyLimit: dailyLimit,
                skipDates: skipped,
                firstReview: firstReview,
                lanes: requestedLanes
            )
            result = calculated
            expectedTasks = verifiedTasks
            approvedInputStamp = inputStamp
            notice = "읽기 전용 계획을 계산했습니다. 실제 숙제·진도는 그대로입니다."
        } catch {
            result = nil
            expectedTasks = nil
            approvedInputStamp = nil
            errorText = error.localizedDescription
            showError = true
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("시험지 배분 방식") {
                    Toggle("모든 과목 · 자유 편집", isOn: $genericMode)
                    if genericMode {
                        TextField("과목 (필수)", text: $genericSubject)
                            .textInputAutocapitalization(.never)
                        TextField("교재 (개별 진도 연결 시 필요)", text: $genericMaterial)
                            .textInputAutocapitalization(.never)
                        Text("원본 진도는 변경하지 않습니다. 개별 회차 UUID가 완전하게 기록된 경우에만 자동 채울 수 있습니다.")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text("기존 국어 인쇄 시험지 18회 템플릿입니다. 개별 회차 18개가 모두 있는 경우만 자동 채웁니다.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    TextField("시작일 (YYYY-MM-DD)", text: $startDate)
                        .textContentType(.none)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("마감일 (YYYY-MM-DD)", text: $endDate)
                        .textContentType(.none)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Stepper("하루 최대 \(dailyLimit)회", value: $dailyLimit, in: 1...12)
                    Picker("개념 복습 시작", selection: $firstReview) {
                        Text("학습지").tag("학습지")
                        Text("교재").tag("교재")
                    }.pickerStyle(.segmented)
                    TextField("쉬는 날: YYYY-MM-DD, YYYY-MM-DD (선택)", text: $skipDates)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("완료 회차 불러오기") {
                    Button {
                        autoFillFromDevice()
                    } label: {
                        Label(genericMode ? "현재 과목·교재의 개별 회차 진도 확인" :
                              "기기의 개별 시험지 18회 진도에서 자동 채우기",
                              systemImage: "checkmark.square.stack")
                    }
                    Text("모든 회차의 명확한 UUID·완료 순서가 없으면 수동 입력을 유지합니다. 실제 진도는 변경되지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if genericMode {
                    Section("범용 단원 · 시험지 종류 편집") {
                        ForEach(genericRows.indices, id: \.self) { index in
                            VStack(alignment: .leading) {
                                TextField("단원 (예: 1단원)", text: $genericRows[index].unit)
                                TextField("시험지 이름", text: $genericRows[index].title)
                                TextField("전체/연속 완료 (예: 3/1)", text: $genericRows[index].rounds)
                                    .keyboardType(.numbersAndPunctuation)
                                Button("이 종류 삭제", role: .destructive) {
                                    if genericRows.count > 1 { genericRows.remove(at: index) }
                                }.disabled(genericRows.count <= 1)
                            }
                        }
                        Button {
                            if genericRows.count < 160 { genericRows.append(EditablePaper()) }
                        } label: { Label("시험지 종류 추가", systemImage: "plus") }
                        .disabled(genericRows.count >= 160)
                    }
                } else {
                Section("2단원 · 시험지별 총 횟수 / 완료 횟수") {
                    ForEach(0..<3, id: \.self) { index in
                        HStack {
                            Text(names[index]).font(.subheadline)
                            Spacer()
                            TextField("3/0", text: $counts[index])
                                .multilineTextAlignment(.trailing)
                                .keyboardType(.numbersAndPunctuation)
                                .frame(width: 82)
                                .accessibilityLabel("\(names[index]) 총/완료")
                        }
                    }
                }
                Section("3단원 · 시험지별 총 횟수 / 완료 횟수") {
                    ForEach(3..<6, id: \.self) { index in
                        HStack {
                            Text(names[index]).font(.subheadline)
                            Spacer()
                            TextField("3/0", text: $counts[index])
                                .multilineTextAlignment(.trailing)
                                .keyboardType(.numbersAndPunctuation)
                                .frame(width: 82)
                                .accessibilityLabel("\(names[index]) 총/완료")
                        }
                    }
                }
                }
                Section("학습 일정 미리보기") {
                    Button {
                        calculate()
                    } label: {
                        Label("남은 시험지 분량 자동 배분", systemImage: "calendar.badge.clock")
                    }
                    Text("미리보기만으로는 기존 숙제·진도를 변경하지 않습니다. 확인 후 별도 동의하면 새 숙제만 한꺼번에 추가할 수 있습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let result {
                    Section("배분 결과 · 남은 \(result.remainingPapers)회") {
                        ForEach(result.days.indices, id: \.self) { index in
                            let day = result.days[index]
                            VStack(alignment: .leading, spacing: 6) {
                                Text("\(day.date) · \(day.review) 개념 복습")
                                    .font(.headline)
                                ForEach(day.papers.indices, id: \.self) { item in
                                    Text("☐ [\(day.papers[item].lane)] \(day.papers[item].title)")
                                        .font(.subheadline)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                        if result.days.isEmpty {
                            Text("배정할 남은 시험지가 없습니다.")
                        }
                        Button {
                            UIPasteboard.general.string = result.clipboardText
                            notice = "읽기 전용 계획을 복사했습니다."
                        } label: {
                            Label("계획 결과 전체 복사", systemImage: "doc.on.doc")
                        }
                        Button {
                            showingConfirmation = true
                        } label: {
                            Label("확인한 시험지를 실제 할 일에 추가",
                                  systemImage: "checkmark.shield")
                        }.disabled(result.remainingPapers == 0)
                    }
                }
                if !notice.isEmpty {
                    Section("상태") {
                        Text(notice).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .onChange(of: inputStamp) { current in
                if let saved = autofillStamp, saved != current {
                    autofillProgress = nil
                    autofillStamp = nil
                    notice = "자동 채움 이후 입력이 변경되어 해당 검증을 해제했습니다. 수동 완료 횟수를 다시 확인하세요."
                }
                if result != nil {
                    result = nil
                    expectedTasks = nil
                    approvedInputStamp = nil
                    notice = "입력이 변경됐습니다. 새로 미리보기를 계산하세요."
                }
            }
            .navigationTitle(genericMode ? "범용 시험지 배분" : "국어 시험지 배분")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                }
            }
            .confirmationDialog("실제 시험지 숙제를 추가할까요?",
                                isPresented: $showingConfirmation,
                                titleVisibility: .visible) {
                Button("중복 확인 후 새 숙제 전체 추가") {
                    approveReviewedPlan()
                }
                Button("취소", role: .cancel) {}
            } message: {
                Text("기존 완료/미완료 숙제는 그대로 두고, 미리보기의 \(result?.remainingPapers ?? 0)회만 추가합니다. 동일 날짜·과목·제목의 숙제가 있거나 검토 후 데이터가 변경되면 전체 등록을 취소합니다. 기존 일정 원본은 앱 내부에 별도 보관합니다.")
            }
            .alert("분량 계산 오류", isPresented: $showError) {
                Button("확인", role: .cancel) {}
            } message: {
                Text(errorText)
            }
        }
    }
}
