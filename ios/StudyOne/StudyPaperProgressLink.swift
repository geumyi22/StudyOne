import Foundation

/// Import counts ONLY when all 18 exact, separately tracked local progress
/// rows are available and consistent. A 64-item aggregate "18회" entry is
/// NEVER interpreted as an individual-paper completion list.
enum StudyPaperProgressLink {
    static let material = "국어 인쇄 시험지 18회"
    static let names = [
        "2-1 소단원 평가", "2-2 소단원 평가", "2단원 대단원 평가",
        "3-1 소단원 평가", "3-2 소단원 평가", "3단원 대단원 평가"
    ]
    private static let circled = ["①","②","③"]

    struct Verified {
        let lanes: [StudyPaperSchedule.Lane]
        let completed: Int
    }

    static func checked(_ records: [StudyProgressItem]) throws -> Verified {
        var matched = [String: StudyProgressItem]()
        for item in records {
            guard item.valid else {
                throw StudyError.message("진도 데이터가 손상됐습니다.")
            }
            guard item.subject == "국어", item.material == material else { continue }
            guard item.inExamScope else {
                throw StudyError.message("시험범위 제외 기록이 있어 자동 채우기를 중단합니다.")
            }
            guard matched[item.unit] == nil else {
                throw StudyError.message("동일한 시험지 회차 진도가 중복됐습니다.")
            }
            matched[item.unit] = item
        }
        guard matched.count == 18 else {
            throw StudyError.message(
                "인쇄 시험지 18개 회차별 기록이 없어 자동 입력할 수 없습니다. "
                + "합계 4/18만으로 어떤 회차를 완료했는지 추측하지 않습니다.")
        }
        var left = [StudyPaperSchedule.Paper]()
        var right = [StudyPaperSchedule.Paper]()
        var totalDone = 0
        for (i, name) in names.enumerated() {
            var finished = 0
            var metIncomplete = false
            for suffix in circled {
                let unit = "\(name) \(suffix)"
                guard let item = matched.removeValue(forKey: unit),
                      item.status != .unknown else {
                    throw StudyError.message("회차 \(unit)의 기록이 없거나 확인 필요 상태입니다.")
                }
                if item.status == .done {
                    guard !metIncomplete else {
                        throw StudyError.message("완료 회차가 순서대로 이어지지 않습니다: \(unit)")
                    }
                    finished += 1
                    totalDone += 1
                } else {
                    metIncomplete = true
                }
            }
            let paper = StudyPaperSchedule.Paper(title: name, total: 3,
                                                  completed: finished)
            if i < 3 { left.append(paper) } else { right.append(paper) }
        }
        guard matched.isEmpty else {
            throw StudyError.message("알 수 없는 인쇄 시험지 회차가 포함됐습니다.")
        }
        return Verified(lanes: [
            StudyPaperSchedule.Lane(name: "2단원", papers: left),
            StudyPaperSchedule.Lane(name: "3단원", papers: right)
        ], completed: totalDone)
    }

    static func selfTest() -> Bool {
        do {
            let all = try names.flatMap { title in
                try circled.map { suffix -> StudyProgressItem in
                    try StudyProgressItem.create(
                        subject: "국어", material: material,
                        unit: "\(title) \(suffix)", status:
                            (title == names[0] && suffix == "①") ||
                            (title == names[3] && suffix == "①") ? .done : .todo,
                        inExamScope: true)
                }
            }
            let result = try checked(all)
            guard result.completed == 2,
                  result.lanes[0].papers[0].completed == 1,
                  result.lanes[1].papers[0].completed == 1 else { return false }
            do {
                _ = try checked(Array(all.dropLast()))
                return false
            } catch { }
            let aggregate = try StudyProgressItem.create(
                subject: "국어", material: material, unit: "인쇄 시험지 18회",
                status: .inProgress, inExamScope: true)
            do {
                _ = try checked([aggregate])
                return false
            } catch { }
            return true
        } catch {
            return false
        }
    }
}
