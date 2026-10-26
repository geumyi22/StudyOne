import Foundation

/// Deterministic offline, read-only study paper/worksheet schedule.
/// It never modifies StudyOneStore, StudyProgressStore, Keychain or networks.
/// This is a PREVIEW only: creation of actual tasks requires explicit consent.
enum StudyPaperSchedule {
    struct Paper {
        let title: String
        let total: Int
        let completed: Int
    }
    struct Lane {
        let name: String
        let papers: [Paper]
    }
    struct Slot {
        let lane: String
        let title: String
    }
    struct Day {
        let date: String
        let review: String
        let papers: [Slot]
    }
    struct Preview {
        let days: [Day]
        let remainingPapers: Int
        let workDays: Int
        let dailyLimit: Int

        var clipboardText: String {
            var lines = [
                "StudyOne · 시험지·학습지 분량 미리보기 (저장하지 않음)",
                "남은 시험지 \(remainingPapers)회 · 하루 최대 \(dailyLimit)회"
            ]
            if days.isEmpty { lines.append("배정할 남은 시험지가 없습니다.") }
            for day in days {
                lines.append("")
                lines.append("\(day.date) · \(day.review) 개념 복습")
                for paper in day.papers {
                    lines.append("☐ [\(paper.lane)] \(paper.title)")
                }
            }
            lines.append("")
            lines.append("읽기 전용 추천안입니다. 실제 숙제·진도에 반영되지 않았습니다.")
            return lines.joined(separator: "\n")
        }
    }

    private struct PaperCursor {
        let paper: Paper
        var next: Int
        var remaining: Int

        init(_ paper: Paper) {
            self.paper = paper
            next = paper.completed + 1
            remaining = paper.total - paper.completed
        }
    }
    private struct LaneCursor {
        let name: String
        var papers: [PaperCursor]
        var cursor = 0

        var hasRemaining: Bool { papers.contains { $0.remaining > 0 } }

        mutating func takeOne() throws -> Slot {
            for offset in 0..<papers.count {
                let index = (cursor + offset) % papers.count
                if papers[index].remaining == 0 { continue }
                let number = papers[index].next
                let circled = [
                    "①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩",
                    "⑪","⑫","⑬","⑭","⑮","⑯","⑰","⑱","⑲","⑳"
                ]
                let label = number <= circled.count
                    ? circled[number - 1] : "(\(number))"
                let result = Slot(lane: name,
                                  title: papers[index].paper.title + " " + label)
                papers[index].next += 1
                papers[index].remaining -= 1
                cursor = (index + 1) % papers.count
                return result
            }
            throw StudyError.message("남은 시험지를 선택할 수 없습니다.")
        }
    }

    private static func checkedLabel(_ value: String) throws -> String {
        guard !value.isEmpty, value == value.trimmingCharacters(in: .whitespacesAndNewlines),
              value.count <= 80, !value.contains("\n"), !value.contains("\r") else {
            throw StudyError.message("단원·시험지 이름은 1~80자로 입력하세요.")
        }
        return value
    }

    private static var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private static func parsed(_ value: String) throws -> Date {
        guard value.range(of: #"^\d{4}-\d{2}-\d{2}$"#,
                          options: .regularExpression) != nil else {
            throw StudyError.message("날짜는 YYYY-MM-DD 형식이어야 합니다.")
        }
        let formatter = DateFormatter()
        formatter.calendar = utcCalendar
        formatter.timeZone = TimeZone(secondsFromGMT: 0)!
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        guard let date = formatter.date(from: value),
              formatter.string(from: date) == value else {
            throw StudyError.message("실제 존재하는 날짜를 입력하세요.")
        }
        return date
    }

    static func preview(start: String, end: String,
                        dailyLimit: Int, skipDates: [String],
                        firstReview: String, lanes: [Lane]) throws -> Preview {
        let first = try parsed(start), last = try parsed(end)
        let calendar = utcCalendar
        let span = calendar.dateComponents([.day], from: first, to: last).day ?? -1
        guard span >= 0, span < 120 else {
            throw StudyError.message("시작일~마감일을 120일 이내로 지정하세요.")
        }
        guard (1...12).contains(dailyLimit) else {
            throw StudyError.message("하루 최대 분량은 1~12회입니다.")
        }
        guard firstReview == "학습지" || firstReview == "교재" else {
            throw StudyError.message("복습 출처는 학습지 또는 교재입니다.")
        }
        guard (1...8).contains(lanes.count), skipDates.count <= 120 else {
            throw StudyError.message("단원은 1~8개, 쉬는 날은 최대 120개입니다.")
        }
        let skipSet = Set(skipDates)
        guard skipSet.count == skipDates.count else {
            throw StudyError.message("쉬는 날짜가 중복됩니다.")
        }
        for date in skipSet {
            let parsedDate = try parsed(date)
            guard parsedDate >= first, parsedDate <= last else {
                throw StudyError.message("쉬는 날은 계획 범위 안에 있어야 합니다.")
            }
        }

        var usedLanes = Set<String>()
        var state = [LaneCursor]()
        var remaining = 0
        for lane in lanes {
            let name = try checkedLabel(lane.name)
            guard usedLanes.insert(name).inserted,
                  (1...20).contains(lane.papers.count) else {
                throw StudyError.message("단원이나 시험지 종류가 중복·누락됐습니다.")
            }
            var titles = Set<String>()
            for paper in lane.papers {
                let title = try checkedLabel(paper.title)
                guard titles.insert(title).inserted,
                      (1...60).contains(paper.total),
                      paper.completed >= 0, paper.completed <= paper.total else {
                    throw StudyError.message("중복된 시험지 또는 잘못된 총/완료 회차가 있습니다.")
                }
                remaining += paper.total - paper.completed
            }
            state.append(LaneCursor(name: name, papers: lane.papers.map(PaperCursor.init)))
        }
        guard remaining <= 240 else {
            throw StudyError.message("남은 시험지는 최대 240회입니다.")
        }
        var workDates = [(String, Date)]()
        for offset in 0...span {
            guard let date = calendar.date(byAdding: .day, value: offset, to: first) else {
                throw StudyError.message("날짜를 계산하지 못했습니다.")
            }
            let dateText = formatDay(date)
            if !skipSet.contains(dateText) { workDates.append((dateText, date)) }
        }
        guard remaining <= dailyLimit * workDates.count else {
            throw StudyError.message("남은 날짜에 모두 끝낼 수 없습니다. 날짜나 하루 분량을 조정하세요.")
        }
        let total = remaining
        var output = [Day]()
        for (dayIndex, pair) in workDates.enumerated() {
            if remaining == 0 { break }
            var selected = [Slot]()
            for slot in 0..<dailyLimit {
                if remaining == 0 { break }
                let desired = (dayIndex * dailyLimit + slot) % state.count
                var chosen: Int?
                for offset in 0..<state.count {
                    let index = (desired + offset) % state.count
                    if state[index].hasRemaining { chosen = index; break }
                }
                guard let index = chosen else {
                    throw StudyError.message("회차 분배가 중단됐습니다.")
                }
                selected.append(try state[index].takeOne())
                remaining -= 1
            }
            let review = dayIndex % 2 == 0 ? firstReview
                : (firstReview == "학습지" ? "교재" : "학습지")
            output.append(Day(date: pair.0, review: review, papers: selected))
        }
        guard remaining == 0 else {
            throw StudyError.message("분량 분배 결과가 불완전합니다.")
        }
        return Preview(days: output, remainingPapers: total,
                       workDays: workDates.count, dailyLimit: dailyLimit)
    }

    private static func formatDay(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = utcCalendar
        formatter.timeZone = TimeZone(secondsFromGMT: 0)!
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    static func selfTest() -> Bool {
        do {
            let left = Lane(name: "2단원", papers: [
                Paper(title: "2-1 소단원 평가", total: 3, completed: 0),
                Paper(title: "2-2 소단원 평가", total: 3, completed: 0),
                Paper(title: "2단원 대단원 평가", total: 3, completed: 0)])
            let right = Lane(name: "3단원", papers: [
                Paper(title: "3-1 소단원 평가", total: 3, completed: 0),
                Paper(title: "3-2 소단원 평가", total: 3, completed: 0),
                Paper(title: "3단원 대단원 평가", total: 3, completed: 0)])
            let full = try preview(start: "2026-10-25", end: "2026-11-02",
                                   dailyLimit: 2, skipDates: [],
                                   firstReview: "학습지", lanes: [left, right])
            guard full.days.count == 9, full.remainingPapers == 18,
                  full.days[0].papers.count == 2,
                  full.days[0].papers[0].title == "2-1 소단원 평가 ①",
                  full.days[0].papers[1].title == "3-1 소단원 평가 ①",
                  full.days[1].review == "교재",
                  full.days[8].date == "2026-11-02" else { return false }
            let partially = Lane(name: "2단원", papers: [
                Paper(title: "2-1 소단원 평가", total: 3, completed: 1),
                Paper(title: "2-2 소단원 평가", total: 3, completed: 1),
                Paper(title: "2단원 대단원 평가", total: 3, completed: 0)])
            let other = Lane(name: "3단원", papers: [
                Paper(title: "3-1 소단원 평가", total: 3, completed: 1),
                Paper(title: "3-2 소단원 평가", total: 3, completed: 1),
                Paper(title: "3단원 대단원 평가", total: 3, completed: 0)])
            let resumed = try preview(start: "2026-10-27", end: "2026-11-02",
                                      dailyLimit: 2, skipDates: [],
                                      firstReview: "교재", lanes: [partially, other])
            guard resumed.remainingPapers == 14, resumed.days.count == 7,
                  resumed.days[0].papers[0].title == "2-1 소단원 평가 ②",
                  resumed.days[0].review == "교재" else { return false }
            do {
                _ = try preview(start: "2026-10-25", end: "2026-11-02",
                                dailyLimit: 1, skipDates: [],
                                firstReview: "학습지", lanes: [left, right])
                return false
            } catch { /* Over-capacity must fail closed. */ }
            return full.clipboardText.contains("실제 숙제·진도에 반영되지")
        } catch {
            return false
        }
    }
}
