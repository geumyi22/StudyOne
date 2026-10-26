import SwiftUI
import UIKit

/// On-device preview editor. Intentionally has no StudyOneStore or network.
/// Copying the resulting plan is not consent to insert real tasks.
struct StudyPaperScheduleView: View {
    @Environment(\.dismiss) private var dismiss
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
            result = try StudyPaperSchedule.preview(
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
            notice = "읽기 전용 계획을 계산했습니다. 실제 숙제·진도는 그대로입니다."
        } catch {
            result = nil
            errorText = error.localizedDescription
            showError = true
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("국어 인쇄 시험지 18회 템플릿") {
                    Text("일정과 완료 횟수를 직접 입력합니다. 기기의 실제 진도나 ChatGPT 계정에서 데이터를 읽어오지 않습니다.")
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
                    Text("계획 계산·복사는 오프라인에서만 실행됩니다. 기존 숙제, 완료 상태, 학습 진도에는 저장하지 않습니다.")
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
            .alert("분량 계산 오류", isPresented: $showError) {
                Button("확인", role: .cancel) {}
            } message: {
                Text(errorText)
            }
        }
    }
}
