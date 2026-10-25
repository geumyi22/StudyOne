import Foundation
import Security

struct School: Codable, Identifiable, Equatable {
    var eduCode: String
    var schoolCode: String
    var name: String
    var kind: String
    var address: String
    var id: String { eduCode + ":" + schoolCode }
}
struct StudyTask: Codable, Identifiable, Equatable {
    var id: Int64
    var type: String
    var subject: String
    var title: String
    var dueDate: String
    var importance: Int
    var completed: Bool
}
struct TimetableEntry: Codable, Identifiable {
    var date: String
    var period: Int
    var subject: String
    var id: String { date + ":" + String(period) + ":" + subject }
}
struct Meal: Codable, Identifiable {
    var date: String
    var menu: String
    var kcal: String
    var id: String { date }
}
struct SchoolCache: Codable {
    var identity: String
    var savedAt: Date
    var timetable: [TimetableEntry]
    var meals: [Meal]
}
struct StudyOneBackup: Codable {
    let format: String
    let schema: Int
    let exportedAt: String
    let grade: Int
    let className: String
    let school: School?
    let tasks: [StudyTask]
}
extension Date {
    func studyString(_ pattern: String = "yyyy-MM-dd") -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Seoul")
        f.dateFormat = pattern
        return f.string(from: self)
    }
}
enum SecretVault {
    private static let service = "com.studyone.ios.credentials"
    static func load(_ account: String) -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    static func save(_ value: String, account: String) throws {
        let key = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        if key.isEmpty { return }
        var data = query
        data[kSecValueData as String] = Data(key.utf8)
        data[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(data as CFDictionary, nil)
        if status != errSecSuccess { throw StudyError.message("보안 키 저장 실패 (코드 \(status))") }
    }
}
enum StudyError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        if case let .message(text) = self { return text }
        return "알 수 없는 오류"
    }
}
@MainActor
final class StudyOneStore: ObservableObject {
    @Published var school: School?
    @Published var grade: Int = 1
    @Published var className: String = "1"
    @Published var tasks: [StudyTask] = []
    @Published var timetable: [TimetableEntry] = []
    @Published var meals: [Meal] = []
    @Published var cacheDate: Date?
    @Published var schoolError = ""
    @Published var loadingSchool = false
    private let settingsKey = "studyone_ios_settings_v1"
    private let cacheKey = "studyone_ios_neis_cache_v1"
    private var lastSettingsBytes: Data?
    private var settingsLoadError = false
    private var lastAttempt: Date?
    private struct Saved: Codable {
        var school: School?
        var grade: Int
        var className: String
        var tasks: [StudyTask]
    }
    init() {
        let raw = UserDefaults.standard.data(forKey: settingsKey)
        lastSettingsBytes = raw
        if let raw {
            if let value = try? JSONDecoder().decode(Saved.self, from: raw) {
                school = value.school
                grade = value.grade
                className = value.className
                tasks = value.tasks
            } else {
                // Never let an ordinary edit erase an unreadable original.
                settingsLoadError = true
                schoolError = "저장된 일정 원본을 읽지 못했습니다. 기존 내용을 보존하고 변경을 차단했습니다."
            }
        }
        restoreCache()
    }
    /// Fail closed for explicit sync. Legacy initial load tolerates a broken
    /// UserDefaults blob; it must never silently upload a truncated task list.
    func checkedTasksForSync() throws -> [StudyTask] {
        guard let bytes = UserDefaults.standard.data(forKey: settingsKey) else {
            guard tasks.isEmpty else {
                throw StudyError.message("일정이 디스크에 저장되지 않았습니다. 전송을 중단합니다.")
            }
            return []
        }
        guard let saved = try? JSONDecoder().decode(Saved.self, from: bytes),
              saved.tasks == tasks, saved.tasks.count <= 5000 else {
            throw StudyError.message("저장된 일정과 현재 화면의 진도가 일치하지 않습니다. 전송하지 않습니다.")
        }
        var ids = Set<Int64>()
        for task in saved.tasks {
            guard task.id > 0,
                  ids.insert(task.id).inserted,
                  !task.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  task.type.count <= 100,
                  task.subject.count <= 60,
                  !task.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  task.title.count <= 500,
                  (1...3).contains(task.importance) else {
                throw StudyError.message("일정 ID 또는 필드가 잘못되어 전송을 차단했습니다.")
            }
        }
        return saved.tasks
    }

    /// Writes new server IDs only, after backing up the exact original settings.
    /// Existing school, NEIS keys, tasks and study completion states are retained.
    func mergeNewTasksFromServer(_ added: [StudyTask],
                                 original expected: [StudyTask]) throws -> URL {
        let verified = try checkedTasksForSync()
        guard verified == expected else {
            throw StudyError.message("승인 도중 iPhone 일정이 변경되어 가져오기를 중단했습니다.")
        }
        guard !added.isEmpty, verified.count + added.count <= 5000 else {
            throw StudyError.message("가져올 일정이 없거나 저장 제한을 초과합니다.")
        }
        var ids = Set(verified.map(\.id))
        for task in added {
            guard task.id > 0, ids.insert(task.id).inserted,
                  !task.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  task.type.count <= 100, task.subject.count <= 60,
                  !task.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  task.title.count <= 500, (1...3).contains(task.importance) else {
                throw StudyError.message("잘못되거나 기존 항목과 ID가 겹치는 일정이 있습니다.")
            }
        }
        let merged = (verified + added).sorted { $0.dueDate < $1.dueDate }
        let document = Saved(school: school, grade: grade,
                             className: className, tasks: merged)
        let blob = try JSONEncoder().encode(document)
        let raw = UserDefaults.standard.data(forKey: settingsKey)
        let backup = try StudySyncBackup.save(raw, kind: "tasks")
        UserDefaults.standard.set(blob, forKey: settingsKey)
        guard UserDefaults.standard.data(forKey: settingsKey) == blob else {
            throw StudyError.message("동기화 일정 저장에 실패했습니다. 이전 백업은 보존됩니다.")
        }
        tasks = merged
        lastSettingsBytes = blob
        return backup
    }

    func persist() {
        guard !settingsLoadError else {
            schoolError = "손상된 일정 원본을 보존하기 위해 변경을 저장하지 않았습니다."
            return
        }
        guard UserDefaults.standard.data(forKey: settingsKey) == lastSettingsBytes else {
            // Another tab or a restored backup changed the original data.
            // Restore what is actually on disk rather than overwriting it.
            try? reloadAfterFourStoreRecovery()
            schoolError = "일정이 다른 화면에서 변경됐습니다. 다시 확인한 후 수정하세요."
            return
        }
        let saved = Saved(school: school, grade: grade, className: className, tasks: tasks)
        if let bytes = try? JSONEncoder().encode(saved) {
            UserDefaults.standard.set(bytes, forKey: settingsKey)
            if UserDefaults.standard.data(forKey: settingsKey) == bytes {
                lastSettingsBytes = bytes
            } else {
                schoolError = "일정 저장 검증 실패. 기존 기록을 확인하세요."
            }
        }
    }
    func setSchool(_ value: School) {
        school = value
        invalidateCache()
        persist()
    }
    func setClass(grade newGrade: Int, className newClass: String) {
        grade = newGrade
        className = newClass
        invalidateCache()
        persist()
    }
    func add(_ item: StudyTask) {
        tasks.append(item)
        tasks.sort { $0.dueDate < $1.dueDate }
        persist()
    }
    func toggle(_ item: StudyTask) {
        guard let index = tasks.firstIndex(where: { $0.id == item.id }) else { return }
        tasks[index].completed.toggle()
        persist()
    }
    func delete(at offsets: IndexSet, from visible: [StudyTask]) {
        let ids = Set(offsets.map { visible[$0].id })
        tasks.removeAll { ids.contains($0.id) }
        persist()
    }
    var priorityTasks: [StudyTask] {
        tasks.filter { !$0.completed }.sorted { a, b in
            if a.dueDate != b.dueDate { return a.dueDate < b.dueDate }
            return a.importance > b.importance
        }
    }
    var offlineSuggestion: String {
        guard let first = priorityTasks.first else { return "미완료 일정이 없습니다. 다음 학습 목표를 등록해 보세요." }
        return "우선 추천: \(first.subject) · \(first.title) (마감 \(first.dueDate))\n25분 집중 학습 → 5분 휴식 → 15분 복습을 권장합니다."
    }
    private var identity: String {
        guard let school else { return "" }
        let monday = NeisService.monday()
        return "\(school.id):\(grade):\(className):\(monday.studyString())"
    }
    private func restoreCache() {
        guard let data = UserDefaults.standard.data(forKey: cacheKey),
              let cache = try? JSONDecoder().decode(SchoolCache.self, from: data),
              cache.identity == identity else { return }
        timetable = cache.timetable
        meals = cache.meals
        cacheDate = cache.savedAt
    }
    private func invalidateCache() {
        timetable = []
        meals = []
        cacheDate = nil
        lastAttempt = nil
        UserDefaults.standard.removeObject(forKey: cacheKey)
    }
    func reloadSchool(force: Bool = false) async {
        guard let selected = school else { schoolError = "설정에서 학교를 먼저 선택하세요."; return }
        let key = SecretVault.load("neis")
        guard !key.isEmpty else { schoolError = "NEIS API 키를 먼저 입력하세요."; return }
        if !force, let lastAttempt, Date().timeIntervalSince(lastAttempt) < 600 { return }
        if loadingSchool { return }
        loadingSchool = true
        lastAttempt = Date()
        schoolError = ""
        defer { loadingSchool = false }
        do {
            let fetched = try await NeisService.loadWeek(key: key, school: selected, grade: grade, className: className)
            guard school == selected, identity == fetched.identity else { return }
            timetable = fetched.timetable
            meals = fetched.meals
            cacheDate = Date()
            let cache = SchoolCache(identity: identity, savedAt: Date(), timetable: timetable, meals: meals)
            if let data = try? JSONEncoder().encode(cache) {
                UserDefaults.standard.set(data, forKey: cacheKey)
            }
        } catch {
            schoolError = error.localizedDescription
        }
    }
    func exportBackup() throws -> URL {
        let backup = StudyOneBackup(format: "studyone-backup", schema: 1,
                                    exportedAt: Date().studyString(), grade: grade,
                                    className: className, school: school, tasks: tasks)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(backup)
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("StudyOne-backup-\(Date().studyString()).json")
        try data.write(to: path, options: .atomic)
        return path
    }
    /// Refresh all observable task fields after four-store rollback.
    func reloadAfterFourStoreRecovery() throws {
        if let raw = UserDefaults.standard.data(forKey: settingsKey) {
            let decoded = try JSONDecoder().decode(Saved.self, from: raw)
            guard decoded.grade >= 1 && decoded.grade <= 6,
                  !decoded.className.isEmpty, decoded.className.count <= 3,
                  decoded.className.allSatisfy({ $0.isNumber }),
                  decoded.tasks.count <= 5000 else {
                throw StudyError.message("복구된 일정 설정이 손상됐습니다.")
            }
            school = decoded.school
            grade = decoded.grade
            className = decoded.className
            tasks = decoded.tasks
        } else {
            school = nil
            grade = 1
            className = "1"
            tasks = []
        }
        lastSettingsBytes = UserDefaults.standard.data(forKey: settingsKey)
        settingsLoadError = false
        restoreCache()
    }

    func importBackup(from data: Data) throws {
        guard data.count < 5_000_000 else { throw StudyError.message("백업 파일이 너무 큽니다.") }
        let backup = try JSONDecoder().decode(StudyOneBackup.self, from: data)
        guard backup.format == "studyone-backup", backup.schema == 1,
              (1...6).contains(backup.grade), !backup.className.isEmpty,
              backup.className.count <= 3, backup.className.allSatisfy({ $0.isNumber }),
              backup.tasks.count <= 5000,
              backup.tasks.allSatisfy({ !$0.title.isEmpty && $0.title.count <= 500 && (1...3).contains($0.importance) && $0.dueDate.count == 10 }),
              backup.school.map({ !$0.eduCode.isEmpty && !$0.schoolCode.isEmpty }) ?? true else {
            throw StudyError.message("지원되지 않거나 손상된 StudyOne 백업 파일입니다.")
        }
        // Validate duplicate IDs and required task fields, not just count.
        var ids = Set<Int64>()
        guard backup.tasks.allSatisfy({
            $0.id > 0 && ids.insert($0.id).inserted &&
            !$0.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            $0.type.count <= 100 && $0.subject.count <= 60 &&
            !$0.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            $0.dueDate.range(of: #"^\d{4}-\d{2}-\d{2}$"#,
                             options: .regularExpression) != nil
        }) else {
            throw StudyError.message("중복된 일정 ID 또는 손상된 백업 항목이 있습니다.")
        }
        guard !settingsLoadError,
              UserDefaults.standard.data(forKey: settingsKey) == lastSettingsBytes else {
            throw StudyError.message("기기 원본을 확인할 수 없거나 다른 화면에서 변경됐습니다.")
        }
        let original = lastSettingsBytes
        let saved = Saved(school: backup.school, grade: backup.grade,
                          className: backup.className, tasks: backup.tasks)
        let bytes = try JSONEncoder().encode(saved)
        // Even the legacy one-store importer now retains exact original bytes.
        _ = try StudySyncBackup.save(original, kind: "tasks")
        UserDefaults.standard.set(bytes, forKey: settingsKey)
        guard UserDefaults.standard.data(forKey: settingsKey) == bytes else {
            if let original {
                UserDefaults.standard.set(original, forKey: settingsKey)
            } else {
                UserDefaults.standard.removeObject(forKey: settingsKey)
            }
            throw StudyError.message("일정 복원 저장 검증 실패. 원본을 복구했습니다.")
        }
        school = backup.school
        grade = backup.grade
        className = backup.className
        tasks = backup.tasks
        lastSettingsBytes = bytes
        invalidateCache()
    }
}
