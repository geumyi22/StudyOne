import Foundation

enum StudyError: Error { case message(String) }
struct Fixture: Decodable { let cases: [Case] }
struct Case: Decodable { let plan: Plan }
struct Plan: Decodable {
    let startDate: String; let endDate: String; let maxPerDay: Int
    let firstReview: String; let skipDates: [String]; let lanes: [Lane]
}
struct Lane: Decodable { let label: String; let items: [Paper] }
struct Paper: Decodable { let title: String; let total: Int; let completed: Int? }
let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
for item in fixture.cases {
    let p = item.plan
    do {
        let result = try StudyPaperSchedule.preview(start: p.startDate, end: p.endDate,
            dailyLimit: p.maxPerDay, skipDates: p.skipDates, firstReview: p.firstReview,
            lanes: p.lanes.map { lane in StudyPaperSchedule.Lane(name: lane.label,
                papers: lane.items.map { StudyPaperSchedule.Paper(title: $0.title, total: $0.total, completed: $0.completed ?? 0) }) })
        var text = "\(result.remainingPapers)|\(result.workDays)|\(result.dailyLimit)\n"
        for day in result.days {
            text += day.date + "|" + day.review + "\n"
            for paper in day.papers { text += paper.lane + "\t" + paper.title + "\n" }
        }
        print(Data(text.utf8).base64EncodedString())
    } catch { print("ERROR") }
}
