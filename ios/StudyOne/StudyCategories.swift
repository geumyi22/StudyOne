import Foundation

/// Five persistent StudyOne planner roles, independent from individual daily tasks.
/// Previous task types stay available to avoid changing imported schema-1 backups.
struct StudyRole {
    let title: String
    let description: String
}

enum StudyCategories {
    static let roles: [StudyRole] = [
        StudyRole(
            title: "개별 항목 (전날 못 다)",
            description: "이전 날짜에 끝내지 못한 공부와 이월 사유를 확인하고 다시 배치합니다. 이미 완료한 과제는 중복 등록하지 않습니다."
        ),
        StudyRole(
            title: "정리 및 복습 항목",
            description: "학교 학습지·교과서 정독, 시험범위 전체 오답 및 개념 복습을 관리합니다. 새로운 문제집 진도와 새 문제 풀이는 숙제 항목에 넣습니다."
        ),
        StudyRole(
            title: "수행 및 암기 항목",
            description: "학교 수행평가 자료 조사·작성·제작, 암기형 쓰기 평가 및 실기 연습을 관리합니다. 서로 다른 수행평가는 개별 과제로 유지합니다."
        ),
        StudyRole(
            title: "숙제 항목",
            description: "학교·학원 숙제뿐 아니라 스스로 정한 새로운 문제집 진도와 단원평가·문제 풀이를 관리합니다. 채점과 제출 여부도 확인합니다."
        ),
        StudyRole(
            title: "기타 항목",
            description: "시험 일정, 준비물, 학습 계획 및 다음 날 준비를 관리합니다. 실제 공부·복습 내용은 해당 분류에 넣습니다."
        )
    ]

    static let legacyTypes = ["과제", "수행평가", "시험", "준비물"]

    static var filters: [String] {
        ["전체"] + roles.map(\.title) + legacyTypes
    }

    static func taskTypes(preserving current: String?) -> [String] {
        var result = roles.map(\.title) + legacyTypes
        if let current, !current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           !result.contains(current) {
            result.append(current)
        }
        return result
    }
}
