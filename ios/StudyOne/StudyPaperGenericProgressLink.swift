import Foundation

/// Maps generic worksheet rounds to individual, stable StudyProgressItem UUIDs.
/// No aggregate counts or unrelated progress are inferred; entirely read-only.
enum StudyPaperGenericProgressLink {
    struct Verified {
        let lanes: [StudyPaperSchedule.Lane]
        let completed: Int
    }

    static func suffix(_ number: Int) -> String {
        let circled = ["①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩",
                       "⑪","⑫","⑬","⑭","⑮","⑯","⑰","⑱","⑲","⑳"]
        return number <= 20 ? circled[number - 1] : "(\(number))"
    }

    static func checked(_ records: [StudyProgressItem], subject: String,
                        material: String, template: [StudyPaperSchedule.Lane]) throws -> Verified {
        guard !subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !material.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !template.isEmpty else {
            throw StudyError.message("정확한 과목·교재와 개별 진도 기록이 필요합니다.")
        }
        var items = [String: StudyProgressItem]()
        var ids = Set<String>()
        for record in records {
            guard record.valid else { throw StudyError.message("진도 원본이 손상됐습니다.") }
            guard record.subject == subject, record.material == material else { continue }
            guard record.inExamScope else {
                throw StudyError.message("시험범위 제외 진도는 자동 채우지 않습니다.")
            }
            guard ids.insert(record.id.lowercased()).inserted, items[record.unit] == nil else {
                throw StudyError.message("진도 회차 또는 ID가 중복됐습니다.")
            }
            items[record.unit] = record
        }
        var total = 0
        var completed = 0
        var mapped = [StudyPaperSchedule.Lane]()
        for lane in template {
            var mappedPapers = [StudyPaperSchedule.Paper]()
            for paper in lane.papers {
                guard (1...60).contains(paper.total) else {
                    throw StudyError.message("회차는 1~60이어야 합니다.")
                }
                var done = 0
                var unfinished = false
                for number in 1...paper.total {
                    total += 1
                    let unit = "[\(lane.name)] \(paper.title) \(suffix(number))"
                    guard let item = items.removeValue(forKey: unit),
                          item.status != .unknown else {
                        throw StudyError.message("회차 \(unit)의 기록이 없거나 확인 필요입니다.")
                    }
                    if item.status == .done {
                        guard !unfinished else {
                            throw StudyError.message("완료 회차가 연속되지 않습니다: \(unit)")
                        }
                        done += 1
                        completed += 1
                    } else { unfinished = true }
                }
                mappedPapers.append(StudyPaperSchedule.Paper(
                    title: paper.title, total: paper.total, completed: done))
            }
            mapped.append(StudyPaperSchedule.Lane(name: lane.name,papers: mappedPapers))
        }
        guard total <= 240, items.isEmpty else {
            throw StudyError.message("진도 기록이 템플릿과 정확히 일치하지 않습니다.")
        }
        return Verified(lanes: mapped, completed: completed)
    }

    static func selfTest() -> Bool {
        do {
            let lane = StudyPaperSchedule.Lane(name: "1단원", papers: [
                StudyPaperSchedule.Paper(title: "형성평가", total: 3, completed: 0)
            ])
            let units = ["①","②","③"]
            let records = try units.enumerated().map { i, number in
                try StudyProgressItem.create(
                    subject: "수학", material: "풍산자",
                    unit: "[1단원] 형성평가 \(number)",
                    status: i == 0 ? .done : .todo, inExamScope: true)
            }
            let result = try checked(records, subject: "수학",
                                     material: "풍산자", template: [lane])
            guard result.completed == 1,
                  result.lanes[0].papers[0].completed == 1 else { return false }
            do {
                _ = try checked(Array(records.dropLast()),
                                subject: "수학", material: "풍산자",
                                template: [lane])
                return false
            } catch { }
            let summary = try StudyProgressItem.create(
                subject: "수학", material: "풍산자",
                unit: "3회 중 1회 완료", status: .inProgress, inExamScope: true)
            do {
                _ = try checked([summary], subject: "수학",
                                material: "풍산자", template: [lane])
                return false
            } catch { }
            return true
        } catch { return false }
    }
}
