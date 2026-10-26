import Foundation

/// Builds a user-reviewed all-or-nothing proposal in memory. Never persists.
/// Actual commit uses StudyOneStore.mergeNewTasksFromServer, which rechecks
/// the on-disk task snapshot, saves original bytes and appends once.
enum StudyPaperPlanApproval {
    private static func identity(_ task: StudyTask) -> String {
        func trimmed(_ value: String) -> String {
            value.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        }
        return [task.dueDate, trimmed(task.type), trimmed(task.subject),
                trimmed(task.title)].joined(separator: "|")
    }

    static func prepare(_ plan: StudyPaperSchedule.Preview,
                        existing: [StudyTask]) throws -> [StudyTask] {
        guard (1...240).contains(plan.remainingPapers) else {
            throw StudyError.message("실제 등록할 시험지 숙제가 없습니다.")
        }
        var identities = Set(existing.map(identity))
        var identifiers = Set(existing.map(\.id))
        var proposed = [StudyTask]()
        for day in plan.days {
            guard day.date.range(of: #"^\d{4}-\d{2}-\d{2}$"#,
                                 options: .regularExpression) != nil else {
                throw StudyError.message("계획 날짜가 올바르지 않습니다.")
            }
            for paper in day.papers {
                let title = "[\(paper.lane)] \(paper.title)"
                guard title.count <= 500 else {
                    throw StudyError.message("시험지 숙제 제목이 너무 깁니다.")
                }
                var id: Int64
                repeat { id = Int64.random(in: 1...Int64.max) }
                    while !identifiers.insert(id).inserted
                let task = StudyTask(id: id, type: "숙제 항목",
                                     subject: "국어", title: title,
                                     dueDate: day.date, importance: 2,
                                     completed: false)
                guard identities.insert(identity(task)).inserted else {
                    throw StudyError.message("이미 존재하거나 완료한 시험지 숙제가 있습니다: \(title)")
                }
                proposed.append(task)
            }
        }
        guard proposed.count == plan.remainingPapers else {
            throw StudyError.message("계획의 총 회차와 저장 예정 항목이 일치하지 않습니다.")
        }
        return proposed
    }

    static func selfTest() -> Bool {
        do {
            let first = StudyPaperSchedule.Lane(name: "2단원", papers: [
                StudyPaperSchedule.Paper(title: "2-1 소단원 평가",
                                         total: 3, completed: 1)
            ])
            let plan = try StudyPaperSchedule.preview(
                start: "2026-10-27", end: "2026-10-28",
                dailyLimit: 1, skipDates: [], firstReview: "교재", lanes: [first])
            let proposed = try prepare(plan, existing: [])
            guard proposed.count == 2, proposed.allSatisfy({
                $0.subject == "국어" && $0.type == "숙제 항목" &&
                !$0.completed && $0.id > 0
            }) else { return false }
            do {
                _ = try prepare(plan, existing: [proposed[0]])
                return false
            } catch { }
            return true
        } catch {
            return false
        }
    }
}
