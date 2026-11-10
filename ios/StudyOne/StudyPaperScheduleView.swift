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
    @State private var approvedInputStamp: String?
    @State private var showingConfirmation = false
    @State private var startDate = Date().studyString()
    @State private var endDate = Calendar.current.date(byAdding: .day, value: 8, to: Date())?
        .studyString() ?? Date().studyString()
    @State private var dailyLimit = 2
    @State private var firstReview = "학습지"
    @State private var skipDates = ""
    @State private var counts = Array(repeating: "3/0", count: 6)
    @State private var result: StudyPaperSchedule.Preview?
    @State private var notice = ""
    @State private var errorText = ""
    @State private var showError = false

    private let names = [
        "2-1 소단원 평가", "2-2 소단원 평가", "2단원 대단원 평가",
        "3-1 소단원 평가", "3-2 소단원 평가", "3단원 대단원 평가"
    ]

    private var inputStamp: String {
        ([startDate, endDate, String(dailyLimit), firstReview, skipDates] + counts)
            .joined(separator: "\u{001F}")
    }

    private func autoFillFromDevice() {
        do {
            let original = try progress.checkedPaperPreviewItems()
            let verified = try StudyPaperProgressLink.checked(original)
            for i in names.indices {
                let paper = i < 3 ? verified.lanes[0].papers[i]
                                  : verified.lanes[1].papers[i - 3]
                counts[i] = "\(paper.total)/\(paper.completed)"
            }
            autofillProgress = original
            expectedTasks = nil
            result = nil
            approvedInputStamp = nil
            notice = "기기에 기록된 개별 시험지 18회 중 \(verified.completed)회 완료를 확인했습니다."
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
                guard try progress.checkedPaperPreviewItems() == original else {
                    throw StudyError.message("미리보기 이후 진도가 변경됐습니다. 다시 확인하세요.")
                }
            }
            let added = try StudyPaperPlanApproval.prepare(result, existing: expectedTasks)
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
            let calculated = try StudyPaperSchedule.preview(
                start: startDate.trimmingCharacters(in: .whitespacesAndNewlines),
                end: endDate.trimmingCharacters(in: .whitespacesAndNewlines),
                dailyLimit: dailyLimit,
                skipDates: skipped,
                firstReview: firstReview,
                lanes: [
                    StudyPaperSchedule.Lane(name: "2단원",
                                            papers: Array(items.prefix(3))),
                    StudyPaperSchedule.Lane(name: "3단원",
                                            papers: Array(items.suffix(3)))
                ]
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
                Section("국어 인쇄 시험지 18회 템플릿") {
                    Text("개별 시험지 18회가 기기 진도에 정확하게 기록됐으면 자동 채우기를 사용할 수 있습니다. 합계 진도만으로는 완료 회차를 추측하지 않습니다. ChatGPT 계정은 조회하지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
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
                        Label("기기의 개별 시험지 18회 진도에서 자동 채우기",
                              systemImage: "checkmark.square.stack")
                    }
                    Text("18개 회차의 명확한 기록이 없으면 수동 입력을 유지합니다. 실제 진도는 변경되지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
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
            .navigationTitle("시험지 자동 배분")
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
