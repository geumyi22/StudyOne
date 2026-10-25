import Foundation

/// Developer-opt-in durable, create-only HTTPS outbox. The token is NEVER stored.
/// Offline data contains plaintext study notes, protected by the device account.
@MainActor
enum StudySyncOutbox {
    private static let format = "studyone-sync-outbox"
    private static let maxBytes = 2_000_000
    private static let maxBatches = 100
    private static let maxItems = 1000

    struct Item: Codable, Equatable {
        let kind: String
        let id: String
        let task: StudyTask?
        let progress: StudyProgressItem?

        var key: String { kind + "#" + id.lowercased() }

        func mutation() throws -> StudySyncClient.Mutation {
            if kind == "task", let task, task.id > 0, id == String(task.id),
               progress == nil {
                return .init(kind: kind, id: id, baseRevision: 0,
                             deleted: false, payload: .task(task))
            }
            if kind == "progress", let progress, progress.valid,
               progress.id.lowercased() == id.lowercased(), task == nil {
                return .init(kind: kind, id: id, baseRevision: 0,
                             deleted: false, payload: .progress(progress))
            }
            throw StudyError.message("오프라인 대기 기록의 형식이 올바르지 않습니다.")
        }
    }

    struct Batch: Codable, Identifiable {
        let id: String
        let accountId: String
        let endpoint: String
        let items: [Item]
    }

    private struct Document: Codable {
        let format: String
        let schema: Int
        var batches: [Batch]
    }

    static func checkedAccount(_ account: String) throws -> String {
        let text = account.lowercased()
        guard let uuid = UUID(uuidString: text), uuid.uuidString.lowercased() == text else {
            throw StudyError.message("전송할 계정 UUID를 정확히 입력하세요.")
        }
        return text
    }

    private static func checkedEndpoint(_ endpoint: String) throws -> String {
        let (url, prefix) = try StudySyncClient.checkedAddress(endpoint)
        guard prefix == "v2/", url.absoluteString == endpoint else {
            throw StudyError.message("대기열 전송에는 정확한 HTTPS 계정 서버 주소가 필요합니다.")
        }
        return endpoint
    }

    private static func storageURL() throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
            .appendingPathComponent("StudyOne", isDirectory: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        return support.appendingPathComponent("sync-outbox.json")
    }

    private static func validate(_ doc: Document) throws {
        guard doc.format == format, doc.schema == 1,
              doc.batches.count <= maxBatches else {
            throw StudyError.message("지원하지 않는 대기열 형식이거나 크기가 너무 큽니다.")
        }
        var ids = Set<String>()
        var keys = Set<String>()
        var total = 0
        for batch in doc.batches {
            guard UUID(uuidString: batch.id) != nil,
                  ids.insert(batch.id.lowercased()).inserted,
                  try checkedAccount(batch.accountId) == batch.accountId,
                  try checkedEndpoint(batch.endpoint) == batch.endpoint,
                  (1...50).contains(batch.items.count) else {
                throw StudyError.message("대기열 요청 ID·계정·서버가 올바르지 않습니다.")
            }
            total += batch.items.count
            guard total <= maxItems else {
                throw StudyError.message("대기열은 최대 1,000개의 신규 항목만 보관합니다.")
            }
            for item in batch.items {
                _ = try item.mutation()
                guard keys.insert(batch.accountId + "#" + item.key).inserted else {
                    throw StudyError.message("동일한 계정에 중복 대기 기록이 있습니다.")
                }
            }
        }
    }

    static func read() throws -> [Batch] {
        let url = try storageURL()
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let bytes = try Data(contentsOf: url)
        guard bytes.count <= maxBytes else {
            throw StudyError.message("오프라인 대기열 파일이 제한보다 큽니다.")
        }
        let doc = try JSONDecoder().decode(Document.self, from: bytes)
        try validate(doc)
        return doc.batches
    }

    private static func write(_ batches: [Batch]) throws {
        let document = Document(format: format, schema: 1, batches: batches)
        try validate(document)
        let encoded = try JSONEncoder().encode(document)
        guard encoded.count <= maxBytes else {
            throw StudyError.message("오프라인 대기열 파일 크기를 초과했습니다.")
        }
        let url = try storageURL()
        try encoded.write(to: url, options: .atomic)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen], ofItemAtPath: url.path)
    }

    static func enqueue(tasks: [StudyTask], progress: [StudyProgressItem],
                        account: String, endpoint: String) throws -> Int {
        let account = try checkedAccount(account)
        let endpoint = try checkedEndpoint(endpoint)
        var batches = try read()
        let existing = Set(batches.filter { $0.accountId == account }
            .flatMap { $0.items.map(\.key) })
        let synthetic = StudySyncClient.Snapshot(schema: 1, cursor: 0, records: [])
        let newMutations = try StudySyncClient.newOnly(
            tasks: tasks, progress: progress, snapshot: synthetic)
        var items: [Item] = []
        for mutation in newMutations {
            let item: Item
            switch mutation.payload {
            case .task(let task):
                item = Item(kind: "task", id: mutation.id, task: task, progress: nil)
            case .progress(let progress):
                item = Item(kind: "progress", id: mutation.id, task: nil, progress: progress)
            }
            if !existing.contains(item.key) { items.append(item) }
        }
        guard !items.isEmpty else { return 0 }
        for offset in stride(from: 0, to: items.count, by: 50) {
            batches.append(Batch(id: UUID().uuidString.lowercased(),
                accountId: account, endpoint: endpoint,
                items: Array(items[offset..<min(offset + 50, items.count)])))
        }
        try write(batches)
        return items.count
    }

    static func validateCurrent(_ batch: Batch, tasks: [StudyTask],
                                progress: [StudyProgressItem]) throws {
        let synthetic = StudySyncClient.Snapshot(schema: 1, cursor: 0, records: [])
        let fresh = try StudySyncClient.newOnly(
            tasks: tasks, progress: progress, snapshot: synthetic)
        var current: [String: Item] = [:]
        for mutation in fresh {
            switch mutation.payload {
            case .task(let task):
                current[mutation.kind + "#" + mutation.id.lowercased()] =
                    Item(kind: "task", id: mutation.id, task: task, progress: nil)
            case .progress(let progress):
                current[mutation.kind + "#" + mutation.id.lowercased()] =
                    Item(kind: "progress", id: mutation.id, task: nil, progress: progress)
            }
        }
        guard batch.items.allSatisfy({ current[$0.key] == $0 }) else {
            throw StudyError.message("오프라인 보관 후 기기 기록이 변경됐습니다. 전송을 중단합니다.")
        }
    }

    static func validateSession(_ batch: Batch, session: StudySyncClient.Session,
                                endpoint: String) throws {
        guard session.schema == 1, session.accountId == batch.accountId,
              session.audience == "device", session.scope == "read-write",
              session.expiresAt > session.serverTime,
              batch.endpoint == (try checkedEndpoint(endpoint)) else {
            throw StudyError.message("요청 계정·서버와 쓰기 권한 토큰이 일치하지 않습니다.")
        }
    }

    static func acknowledge(_ requestId: String) throws {
        var batches = try read()
        guard batches.first?.id == requestId else {
            throw StudyError.message("대기열 첫 요청이 변경됐습니다. 원본을 보존합니다.")
        }
        batches.removeFirst()
        try write(batches)
    }

    /// Pure app-container fixture used by build/tests; no real user account.
    static func sampleRoundTrip() throws -> Bool {
        let account = UUID().uuidString.lowercased()
        let sample = StudySyncClient.Snapshot(schema: 1, cursor: 0, records: [])
        guard sample.records.isEmpty else { return false }
        let item = StudyTask(id: 1790000000001, type: "숙제 항목", subject: "국어",
                             title: "합성 문제", dueDate: "2026-11-02",
                             importance: 2, completed: false)
        let entry = Item(kind: "task", id: String(item.id), task: item, progress: nil)
        let batch = Batch(id: UUID().uuidString.lowercased(), accountId: account,
                          endpoint: "https://sync.example.org/", items: [entry])
        try validate(Document(format: format, schema: 1, batches: [batch]))
        try validateCurrent(batch, tasks: [item], progress: [])
        return try entry.mutation().id == String(item.id)
    }
}
