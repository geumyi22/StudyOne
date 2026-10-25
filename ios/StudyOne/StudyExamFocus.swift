import Foundation

/// A read-only, subject-by-subject exam study recommendation.
/// Only explicitly marked exam-scope records are counted. No reminders,
/// timestamps, task statuses or local progress rows are mutated.
enum StudyExamFocus {
    static let preferred = ["국어", "사회", "역사", "과학", "영어", "수학"]

    struct Subject: Identifiable {
        let name: String
        let total: Int
        let done: Int
        let started: Int
        let todo: Int
        let unknown: Int
        let waitingOnline: Int
        let next: String

        var id: String { name }
        var percent: Int {
            guard total > 0 else { return 0 }
            return Int((100.0 * Double(done) / Double(total)).rounded())
        }
        var summary: String {
            "완료 \(done)/\(total) · \(percent)% · 진행 \(started) · 미완료 \(todo) · 확인 \(unknown)"
        }
        var nextText: String { next.isEmpty ? "시험범위 학습 완료" : "우선: " + next }
    }

    private static func rank(_ item: StudyProgressItem) -> Int {
        switch item.status {
        case .inProgress: return 0
        case .todo: return 1
        case .unknown: return 2
        case .done: return 3
        }
    }

    static func summarize(_ records: [StudyProgressItem]) -> [Subject] {
        let groups = Dictionary(grouping: records.filter(\.inExamScope)) {
            $0.subject.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return groups.keys.filter { !$0.isEmpty }.sorted { a, b in
            let left = preferred.firstIndex(of: a) ?? preferred.count
            let right = preferred.firstIndex(of: b) ?? preferred.count
            return left == right ? a < b : left < right
        }.map { name in
            let entries = groups[name] ?? []
            let unfinished = entries.filter { $0.status != .done }
            let regular = unfinished.filter {
                $0.unit.trimmingCharacters(in: .whitespacesAndNewlines)
                    != StudyOnlinePractice.unit
            }
            let online = unfinished.filter {
                $0.unit.trimmingCharacters(in: .whitespacesAndNewlines)
                    == StudyOnlinePractice.unit
            }
            // If printed work remains, do not promote online problem banks.
            let choices = regular.isEmpty ? online : regular
            let candidate = choices.sorted { a, b in
                if rank(a) != rank(b) { return rank(a) < rank(b) }
                if a.material != b.material { return a.material < b.material }
                return a.unit < b.unit
            }.first
            let next = candidate.map { "\($0.material) · \($0.unit) (\($0.status.rawValue))" } ?? ""
            return Subject(name: name, total: entries.count,
                           done: entries.filter { $0.status == .done }.count,
                           started: entries.filter { $0.status == .inProgress }.count,
                           todo: entries.filter { $0.status == .todo }.count,
                           unknown: entries.filter { $0.status == .unknown }.count,
                           waitingOnline: online.count, next: next)
        }
    }
}
