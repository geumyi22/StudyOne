import Foundation

/// Debug-only localhost protocol. Production apps must use verified HTTPS
/// and user-scoped authentication before any cross-device sync is enabled.
enum StudySyncClient {
    #if DEBUG
    static let defaultAddress = "http://127.0.0.1:8765/"
    #else
    static let defaultAddress = "https://"
    #endif

    static func checkedAddress(_ address: String) throws -> (URL, String) {
        guard let components = URLComponents(string: address),
              let scheme = components.scheme?.lowercased(),
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path == "/" else {
            throw StudyError.message("서버 주소는 루트 HTTPS 주소여야 합니다.")
        }
        if scheme == "http" {
            #if DEBUG
            guard (host == "127.0.0.1" || host == "localhost" || host == "::1"),
                  components.port == 8765 else {
                throw StudyError.message("HTTP는 Debug localhost:8765에서만 허용됩니다.")
            }
            #else
            throw StudyError.message("배포 앱에서는 HTTPS 연결만 허용합니다.")
            #endif
        } else if scheme != "https" {
            throw StudyError.message("외부 계정 서버는 HTTPS로 연결해야 합니다.")
        }
        guard let url = components.url else {
            throw StudyError.message("지원되지 않는 동기화 서버 주소입니다.")
        }
        return (url, scheme == "https" ? "v2/" : "v1/")
    }
    static let statuses: Set<String> = ["확인 필요", "미완료", "진행 중", "완료"]
    private static let maxResponse = 4_000_000

    struct Session: Decodable {
        let schema: Int
        let accountId: String
        let deviceId: String
        let scope: String
        let audience: String
        let expiresAt: Int64
        let serverTime: Int64
    }

    struct Record: Decodable {
        let kind: String
        let id: String
        let revision: Int64
        let deleted: Bool
    }

    struct Snapshot: Decodable {
        let schema: Int
        let cursor: Int64
        let records: [Record]
    }

    struct PendingPlans: Decodable {
        let schema: Int
        let proposals: [PendingPlan]
    }

    struct PendingPlan: Decodable, Identifiable {
        let id: String
        let title: String
        let state: String
        let createdAt: Int64
        let expiresAt: Int64
        let taskCount: Int
        let tasks: [StudyTask]
    }

    private struct Decision: Decodable {
        let id: String
        let state: String
    }

    enum Payload: Encodable {
        case task(StudyTask)
        case progress(StudyProgressItem)

        func encode(to encoder: Encoder) throws {
            switch self {
            case .task(let value): try value.encode(to: encoder)
            case .progress(let value): try value.encode(to: encoder)
            }
        }
    }

    struct Mutation: Encodable {
        let kind: String
        let id: String
        let baseRevision: Int
        let deleted: Bool
        let payload: Payload
    }

    private struct Batch: Encodable {
        let requestId: String
        let mutations: [Mutation]
    }

    private struct Ack: Decodable {
        let schema: Int
        let cursor: Int64
        let changes: [AckItem]
    }

    private struct AckItem: Decodable {
        let seq: Int64
        let kind: String
        let id: String
        let revision: Int64
        let deleted: Bool
        let updatedAt: Int64
    }

    private final class RefuseRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession,
                        task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    private static func checkedToken(_ token: String) throws -> String {
        guard (32...1024).contains(token.count),
              token.utf8.allSatisfy({ (33...126).contains($0) }) else {
            throw StudyError.message("로컬 테스트 토큰은 공백 없는 ASCII 32~1024자로 입력하세요.")
        }
        return token
    }

    private static func request(_ token: String, endpoint: String,
                                body: Data? = nil,
                                address: String = defaultAddress) async throws -> Data {
        let safeToken = try checkedToken(token)
        let (base, prefix) = try checkedAddress(address)
        let normal = endpoint == "snapshot" || endpoint == "changes" || endpoint == "session"
        let inbox = endpoint == "proposals"
        let actionParts = endpoint.split(separator: "/").map(String.init)
        let decision = actionParts.count == 3 && actionParts[0] == "proposals"
            && UUID(uuidString: actionParts[1]) != nil
            && (actionParts[2] == "approve" || actionParts[2] == "reject")
        guard normal || inbox || decision else {
            throw StudyError.message("지원되지 않는 동기화 경로입니다.")
        }
        if (inbox || decision || endpoint == "session") && prefix != "v2/" {
            throw StudyError.message("AI 학습 계획 승인은 HTTPS 계정 서버에서만 지원됩니다.")
        }
        if let body, body.count > 262_144 {
            throw StudyError.message("전송 데이터 크기가 제한을 초과했습니다.")
        }

        // No credentials, cookies or cache persisted in the app.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 15
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: RefuseRedirect(),
                                 delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        var req = URLRequest(url: base.appendingPathComponent(prefix + endpoint))
        req.httpMethod = body == nil ? "GET" : "POST"
        req.setValue("Bearer " + safeToken, forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.cachePolicy = .reloadIgnoringLocalCacheData
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (responseBody, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw StudyError.message("로컬 동기화 서버의 응답을 확인할 수 없습니다.")
        }
        switch http.statusCode {
        case 200: break
        case 401: throw StudyError.message("동기화 인증이 거절되었습니다.")
        case 403: throw StudyError.message("읽기 전용 토큰은 계획 승인 권한이 없습니다.")
        case 409: throw StudyError.message("계획이 이미 변경되었거나 서버 기록과 충돌했습니다. 다시 조회하세요.")
        default: throw StudyError.message("로컬 서버 오류: HTTP \(http.statusCode)")
        }
        guard responseBody.count <= maxResponse else {
            throw StudyError.message("서버 응답이 크기 제한을 초과했습니다.")
        }
        return responseBody
    }

    static func snapshot(token: String, address: String = defaultAddress) async throws -> Snapshot {
        let bytes = try await request(token, endpoint: "snapshot", address: address)
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: bytes)
        guard snapshot.schema == 1, snapshot.cursor >= 0,
              snapshot.records.count <= 7000 else {
            throw StudyError.message("지원되지 않는 서버 데이터입니다.")
        }
        var seen = Set<String>()
        for record in snapshot.records {
            guard (record.kind == "task" || record.kind == "progress"),
                  record.revision > 0, !record.deleted,
                  !record.id.isEmpty,
                  seen.insert(record.kind + "#" + record.id).inserted else {
                throw StudyError.message("서버 기록 형식이나 ID에 오류가 있습니다.")
            }
        }
        return snapshot
    }

    static func authenticatedSession(token: String, address: String) async throws -> Session {
        let (_, prefix) = try checkedAddress(address)
        guard prefix == "v2/" else {
            throw StudyError.message("실제 계정 연결에는 HTTPS 서버가 필요합니다.")
        }
        let result = try await request(token, endpoint: "session", address: address)
        let value = try JSONDecoder().decode(Session.self, from: result)
        guard value.schema == 1, UUID(uuidString: value.accountId) != nil,
              UUID(uuidString: value.deviceId) != nil,
              value.expiresAt > value.serverTime,
              value.audience == "device" else {
            throw StudyError.message("계정 인증 응답을 확인할 수 없습니다.")
        }
        return value
    }

    /// A 200 OK alone is not proof that the server committed these records.
    /// Fail closed on reordered, mismatched, truncated, forged or stale receipts.
    static func verifyChangeAck(_ raw: Data, mutations: [Mutation]) throws {
        guard (1...50).contains(mutations.count) else {
            throw StudyError.message("서버 요청의 크기가 올바르지 않습니다.")
        }
        let reply = try JSONDecoder().decode(Ack.self, from: raw)
        guard reply.schema == 1, reply.changes.count == mutations.count else {
            throw StudyError.message("서버가 일부 변경만 확인했습니다.")
        }
        var last: Int64 = 0
        for (requested, accepted) in zip(mutations, reply.changes) {
            guard accepted.seq > last,
                  accepted.kind == requested.kind,
                  accepted.id == requested.id,
                  accepted.revision == Int64(requested.baseRevision + 1),
                  accepted.deleted == requested.deleted,
                  accepted.updatedAt >= 0 else {
                throw StudyError.message("서버 승인 ID·수정 버전·순서를 확인할 수 없습니다.")
            }
            last = accepted.seq
        }
        guard reply.cursor == last else {
            throw StudyError.message("서버 승인 커서가 마지막 기록과 다릅니다.")
        }
    }

    /// A stable server request UUID is persisted outside this function.
    /// After a lost response, the same batch body can safely be retried.
    static func uploadQueuedBatch(token: String, address: String,
                                  batch: StudySyncOutbox.Batch) async throws -> Int {
        let (_, prefix) = try checkedAddress(address)
        guard prefix == "v2/", UUID(uuidString: batch.id) != nil,
              (1...50).contains(batch.items.count) else {
            throw StudyError.message("올바르지 않은 HTTPS 대기 요청입니다.")
        }
        let mutations = try batch.items.map { try $0.mutation() }
        let data = try JSONEncoder().encode(Batch(requestId: batch.id, mutations: mutations))
        let result = try await request(token, endpoint: "changes",
                                       body: data, address: address)
        try verifyChangeAck(result, mutations: mutations)
        return mutations.count
    }

    // Phase33: one deliberately selected existing server ID. The normal
    // create-only batch type remains unchanged and cannot delete anything.
    struct ReviewedChange {
        let kind: String
        let id: String
        let baseRevision: Int64
        let deleted: Bool
        let payload: Payload?
        let cursor: Int64
        let remoteSummary: String
        let localSummary: String
        let remoteRecord: Data

        func sameApprovedMutation(as other: ReviewedChange) throws -> Bool {
            guard kind == other.kind, id == other.id,
                  baseRevision == other.baseRevision, deleted == other.deleted,
                  cursor == other.cursor,
                  remoteSummary == other.remoteSummary,
                  localSummary == other.localSummary else { return false }
            let old = try JSONEncoder().encode(ReviewedMutation(change: self))
            let new = try JSONEncoder().encode(ReviewedMutation(change: other))
            return old == new
        }
    }

    private struct ReviewedMutation: Encodable {
        let kind: String
        let id: String
        let baseRevision: Int64
        let deleted: Bool
        let payload: Payload?
        enum CodingKeys: String, CodingKey {
            case kind, id, baseRevision, deleted, payload
        }
        init(change: ReviewedChange) {
            kind = change.kind
            id = change.id
            baseRevision = change.baseRevision
            deleted = change.deleted
            payload = change.payload
        }
        func encode(to encoder: Encoder) throws {
            var fields = encoder.container(keyedBy: CodingKeys.self)
            try fields.encode(kind, forKey: .kind)
            try fields.encode(id, forKey: .id)
            try fields.encode(baseRevision, forKey: .baseRevision)
            try fields.encode(deleted, forKey: .deleted)
            if let payload { try fields.encode(payload, forKey: .payload) }
            else { try fields.encodeNil(forKey: .payload) }
        }
    }

    private struct ReviewedRequest: Encodable {
        let requestId: String
        let mutations: [ReviewedMutation]
    }

    static func prepareReviewedChange(
        token: String, address: String,
        localTasks: [StudyTask], localProgress: [StudyProgressItem],
        kind: String, id: String, deleted: Bool
    ) async throws -> ReviewedChange {
        let (_, prefix) = try checkedAddress(address)
        guard prefix == "v2/" else {
            throw StudyError.message("기존 기록 변경·삭제는 HTTPS 계정 서버에서만 허용합니다.")
        }
        guard kind == "task" || kind == "progress", !id.isEmpty else {
            throw StudyError.message("일정 또는 진도의 정확한 ID를 입력하세요.")
        }
        // First validate all remote records, not just the selected one.
        let validated = try await previewIncoming(
            token: token, address: address,
            localTasks: localTasks, localProgress: localProgress)
        let data = try await request(token, endpoint: "snapshot", address: address)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cursor = root["cursor"] as? Int64, cursor == validated.cursor,
              let rows = root["records"] as? [[String: Any]],
              rows.count <= 7000 else {
            throw StudyError.message("비교 중 서버 기록이 변경됐습니다.")
        }
        let selected = rows.filter {
            ($0["kind"] as? String) == kind && ($0["id"] as? String) == id
        }
        guard selected.count == 1,
              let rowRevision = selected[0]["revision"] as? Int64,
              rowRevision > 0,
              (selected[0]["deleted"] as? Bool) == false,
              let document = selected[0]["payload"] as? [String: Any] else {
            throw StudyError.message("서버에 활성화된 대상 ID가 하나만 있어야 합니다.")
        }
        let payloadData = try JSONSerialization.data(withJSONObject: document)
        let oldRecord = try JSONSerialization.data(withJSONObject: selected[0])
        var remoteDescription: String
        var localDescription: String
        var candidate: Payload?
        if kind == "task" {
            guard let numeric = Int64(id), numeric > 0, String(numeric) == id else {
                throw StudyError.message("일정 ID가 올바르지 않습니다.")
            }
            let remote = try JSONDecoder().decode(StudyTask.self, from: payloadData)
            guard remote.id == numeric else { throw StudyError.message("서버 일정 ID 불일치") }
            let local = localTasks.first(where: { $0.id == numeric })
            remoteDescription = "[\(remote.subject)] \(remote.title) · \(remote.dueDate)"
            if deleted {
                guard local == nil else {
                    throw StudyError.message("기기에 같은 ID가 남아 있어 서버 삭제를 차단합니다.")
                }
                localDescription = "이 기기에는 없는 기록 · 원격만 삭제"
            } else {
                guard let local else { throw StudyError.message("기기에 수정할 일정이 없습니다.") }
                guard local != remote else { throw StudyError.message("서버와 동일한 일정입니다.") }
                candidate = .task(local)
                localDescription = "[\(local.subject)] \(local.title) · \(local.dueDate)"
            }
        } else {
            guard UUID(uuidString: id) != nil else {
                throw StudyError.message("진도 UUID가 올바르지 않습니다.")
            }
            let remote = try JSONDecoder().decode(StudyProgressItem.self, from: payloadData)
            guard remote.id.lowercased() == id.lowercased(),
                  remote.revision == rowRevision else {
                throw StudyError.message("서버 진도 수정 버전이 올바르지 않습니다.")
            }
            let local = localProgress.first { $0.id.lowercased() == id.lowercased() }
            remoteDescription = "[\(remote.subject)] \(remote.material) · \(remote.unit) · \(remote.status.rawValue)"
            if deleted {
                guard local == nil else {
                    throw StudyError.message("기기에 같은 진도 UUID가 남아 있어 서버 삭제를 차단합니다.")
                }
                localDescription = "이 기기에는 없는 진도 · 원격만 삭제"
            } else {
                guard let local else { throw StudyError.message("기기에 수정할 진도가 없습니다.") }
                guard local.subject != remote.subject ||
                      local.material != remote.material ||
                      local.unit != remote.unit ||
                      local.status != remote.status ||
                      local.inExamScope != remote.inExamScope else {
                    throw StudyError.message("서버와 동일한 진도입니다.")
                }
                candidate = .progress(local)
                localDescription = "[\(local.subject)] \(local.material) · \(local.unit) · \(local.status.rawValue)"
            }
        }
        return ReviewedChange(
            kind: kind, id: id, baseRevision: rowRevision, deleted: deleted,
            payload: candidate, cursor: cursor,
            remoteSummary: remoteDescription, localSummary: localDescription,
            remoteRecord: oldRecord)
    }

    @MainActor
    static func saveReviewedRecovery(_ review: ReviewedChange,
                                     session: Session, endpoint: String) throws -> String {
        let (_, prefix) = try checkedAddress(endpoint)
        guard prefix == "v2/" else {
            throw StudyError.message("HTTPS 계정 서버에서만 원격 복구본을 저장합니다.")
        }
        let folder = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
            .appendingPathComponent("StudyOne/SyncRemoteRecovery", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        guard let old = try JSONSerialization.jsonObject(with: review.remoteRecord) as? [String: Any] else {
            throw StudyError.message("서버 원본 복구 파일을 읽을 수 없습니다.")
        }
        let contents: [String: Any] = [
            "format": "studyone-remote-recovery", "schema": 1,
            "accountId": session.accountId, "endpoint": endpoint,
            "cursor": review.cursor, "record": old
        ]
        let bytes = try JSONSerialization.data(withJSONObject: contents)
        guard bytes.count <= 2_000_000 else {
            throw StudyError.message("서버 기록 복구본이 너무 큽니다.")
        }
        let name = UUID().uuidString.lowercased() + ".json"
        let url = folder.appendingPathComponent(name)
        try bytes.write(to: url, options: .atomic)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen], ofItemAtPath: url.path)
        return name
    }

    static func uploadReviewedChange(
        token: String, address: String, reviewed: ReviewedChange) async throws {
        let (_, prefix) = try checkedAddress(address)
        guard prefix == "v2/", reviewed.baseRevision >= 1 else {
            throw StudyError.message("HTTPS 계정 서버의 기존 수정 버전이 필요합니다.")
        }
        let packet = ReviewedRequest(requestId: UUID().uuidString.lowercased(),
                                      mutations: [ReviewedMutation(change: reviewed)])
        let bytes = try JSONEncoder().encode(packet)
        let reply = try await request(token, endpoint: "changes",
                                      body: bytes, address: address)
        let ack = try JSONDecoder().decode(Ack.self, from: reply)
        guard ack.schema == 1, ack.changes.count == 1,
              let item = ack.changes.first,
              item.seq > 0, ack.cursor == item.seq,
              item.kind == reviewed.kind, item.id == reviewed.id,
              item.revision == reviewed.baseRevision + 1,
              item.deleted == reviewed.deleted, item.updatedAt >= 0 else {
            throw StudyError.message("기존 기록 변경 서버 승인 내용을 확인할 수 없습니다.")
        }
    }

    static func newOnly(tasks: [StudyTask], progress: [StudyProgressItem],
                        snapshot: Snapshot) throws -> [Mutation] {
        guard snapshot.schema == 1, tasks.count <= 5000, progress.count <= 2000 else {
            throw StudyError.message("로컬 또는 서버 데이터 크기가 올바르지 않습니다.")
        }
        let remote = Set(snapshot.records.map { $0.kind + "#" + $0.id })
        var seen = Set<String>()
        var pending: [Mutation] = []
        let dateFormatter = ISO8601DateFormatter()
        dateFormatter.formatOptions = [.withFullDate]
        for item in tasks {
            let id = String(item.id)
            guard item.id > 0, (1...3).contains(item.importance),
                  !item.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  item.type.count <= 100,
                  item.subject.count <= 60, item.title.count <= 500,
                  !item.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  item.dueDate.count == 10,
                  let parsed = dateFormatter.date(from: item.dueDate),
                  dateFormatter.string(from: parsed) == item.dueDate,
                  seen.insert("task#" + id).inserted else {
                throw StudyError.message("일정이 중복되거나 형식이 잘못되었습니다. 전송하지 않습니다.")
            }
            if !remote.contains("task#" + id) {
                pending.append(Mutation(kind: "task", id: id, baseRevision: 0,
                                        deleted: false, payload: .task(item)))
            }
        }
        for item in progress {
            let id = item.id
            guard item.valid, statuses.contains(item.status.rawValue),
                  seen.insert("progress#" + id).inserted else {
                throw StudyError.message("진도 항목이 중복되거나 손상되었습니다. 전송하지 않습니다.")
            }
            if !remote.contains("progress#" + id) {
                pending.append(Mutation(kind: "progress", id: id, baseRevision: 0,
                                        deleted: false, payload: .progress(item)))
            }
        }
        return pending
    }

    /// No retries and no overwrite; if network result is unknown, re-read.
    static func uploadNew(token: String, pending: [Mutation],
                          address: String = defaultAddress) async throws -> Int {
        var sent = 0
        let encoder = JSONEncoder()
        for offset in stride(from: 0, to: pending.count, by: 50) {
            let part = Array(pending[offset..<min(offset + 50, pending.count)])
            let payload = Batch(requestId: UUID().uuidString.lowercased(),
                                mutations: part)
            let bytes = try encoder.encode(payload)
            do {
                let result = try await request(token, endpoint: "changes",
                                               body: bytes, address: address)
                try verifyChangeAck(result, mutations: part)
                sent += part.count
            } catch {
                throw StudyError.message("전송이 중단되었습니다. \(sent)개 전송 후 추가 저장이 "
                                         + "발생했을 수 있으니 서버를 다시 조회하세요.")
            }
        }
        return sent
    }

    struct IncomingPlan {
        let newTasks: [StudyTask]
        let newProgress: [StudyProgressItem]
        let conflicts: Int
        let unchanged: Int
        let cursor: Int64

        func matches(_ other: IncomingPlan, importingTasks: Bool) -> Bool {
            cursor == other.cursor && conflicts == other.conflicts
                && (importingTasks ? newTasks == other.newTasks
                                  : newProgress == other.newProgress)
        }
    }

    /// Read and validate every remote payload before presenting any import.
    /// Existing IDs are counted as conflicts or unchanged; never overwritten.
    static func previewIncoming(token: String, address: String,
                                localTasks: [StudyTask],
                                localProgress: [StudyProgressItem]) async throws -> IncomingPlan {
        guard localTasks.count <= 5000, localProgress.count <= 2000 else {
            throw StudyError.message("로컬 일정·진도 저장 제한을 초과했습니다.")
        }
        let bytes = try await request(token, endpoint: "snapshot", address: address)
        guard let root = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let schema = root["schema"] as? Int, schema == 1,
              let cursor = root["cursor"] as? Int64, cursor >= 0,
              let records = root["records"] as? [[String: Any]],
              records.count <= 7000 else {
            throw StudyError.message("동기화 서버 스냅샷이 올바르지 않습니다.")
        }
        var taskIds: [Int64: StudyTask] = [:]
        var progressIds: [String: StudyProgressItem] = [:]
        for item in localTasks {
            guard taskIds.updateValue(item, forKey: item.id) == nil else {
                throw StudyError.message("로컬 일정 ID가 중복되어 가져오기를 차단했습니다.")
            }
        }
        for item in localProgress {
            guard progressIds.updateValue(item, forKey: item.id.lowercased()) == nil else {
                throw StudyError.message("로컬 진도 ID가 중복되어 가져오기를 차단했습니다.")
            }
        }
        var seen = Set<String>()
        var newTasks: [StudyTask] = []
        var newProgress: [StudyProgressItem] = []
        var conflicts = 0
        var unchanged = 0
        let decoder = JSONDecoder()
        let dateParser = ISO8601DateFormatter()
        dateParser.formatOptions = [.withFullDate]

        for record in records {
            guard Set(record.keys) == Set(["kind", "id", "revision",
                                           "deleted", "updatedAt", "payload"]),
                  let kind = record["kind"] as? String,
                  kind == "task" || kind == "progress",
                  let id = record["id"] as? String,
                  let revision = record["revision"] as? Int64, revision >= 1,
                  let deleted = record["deleted"] as? Bool, !deleted,
                  let payload = record["payload"] as? [String: Any],
                  seen.insert(kind + "#" + id.lowercased()).inserted else {
                throw StudyError.message("서버에 중복 또는 손상된 기록이 있습니다.")
            }
            let expectedFields: Set<String> = kind == "task"
                ? ["id", "type", "subject", "title", "dueDate", "importance", "completed"]
                : ["id", "subject", "material", "unit", "status",
                   "inExamScope", "revision", "updatedAt"]
            guard Set(payload.keys) == expectedFields else {
                throw StudyError.message("서버 기록에 예상하지 못한 필드가 포함됐습니다.")
            }
            let encoded = try JSONSerialization.data(withJSONObject: payload)
            if kind == "task" {
                let candidate = try decoder.decode(StudyTask.self, from: encoded)
                guard candidate.id > 0, String(candidate.id) == id,
                      !candidate.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      candidate.type.count <= 100, candidate.subject.count <= 60,
                      !candidate.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      candidate.title.count <= 500,
                      (1...3).contains(candidate.importance),
                      candidate.dueDate.count == 10,
                      let parsed = dateParser.date(from: candidate.dueDate),
                      dateParser.string(from: parsed) == candidate.dueDate else {
                    throw StudyError.message("서버 일정의 ID 또는 값이 유효하지 않습니다.")
                }
                if let existing = taskIds[candidate.id] {
                    if existing == candidate { unchanged += 1 }
                    else { conflicts += 1 }
                } else {
                    newTasks.append(candidate)
                }
            } else {
                let candidate = try decoder.decode(StudyProgressItem.self, from: encoded)
                guard candidate.valid,
                      candidate.id.lowercased() == id.lowercased(),
                      candidate.revision == revision else {
                    throw StudyError.message("서버 진도 ID 또는 수정 이력이 유효하지 않습니다.")
                }
                if let existing = progressIds[candidate.id.lowercased()] {
                    // Server updates revision/updatedAt when it accepts a write.
                    // Those transport metadata values do NOT imply a meaningful
                    // conflicting user edit. Android and Windows already compare
                    // the five user-controlled fields instead of timestamps.
                    let sameUserContent =
                        existing.subject == candidate.subject &&
                        existing.material == candidate.material &&
                        existing.unit == candidate.unit &&
                        existing.status == candidate.status &&
                        existing.inExamScope == candidate.inExamScope
                    if sameUserContent { unchanged += 1 }
                    else { conflicts += 1 }
                } else {
                    newProgress.append(candidate)
                }
            }
        }
        guard newTasks.count + localTasks.count <= 5000,
              newProgress.count + localProgress.count <= 2000 else {
            throw StudyError.message("가져오기 후 데이터 수가 허용 범위를 초과합니다.")
        }
        return IncomingPlan(newTasks: newTasks, newProgress: newProgress,
                            conflicts: conflicts, unchanged: unchanged, cursor: cursor)
    }

    /// Read-only preview of the authenticated account's proposals.
    static func pendingPlans(token: String, address: String) async throws -> [PendingPlan] {
        let bytes = try await request(token, endpoint: "proposals", address: address)
        let box = try JSONDecoder().decode(PendingPlans.self, from: bytes)
        guard box.schema == 1, box.proposals.count <= 20 else {
            throw StudyError.message("서버 승인 대기 계획 응답을 확인할 수 없습니다.")
        }
        var ids = Set<String>()
        for plan in box.proposals {
            guard UUID(uuidString: plan.id) != nil,
                  ids.insert(plan.id.lowercased()).inserted,
                  plan.state == "pending",
                  !plan.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  plan.title.count <= 100,
                  plan.expiresAt > plan.createdAt,
                  plan.taskCount == plan.tasks.count,
                  (1...30).contains(plan.taskCount) else {
                throw StudyError.message("승인 대기 공부 계획이 올바르지 않습니다.")
            }
            var taskIds = Set<Int64>()
            for task in plan.tasks {
                guard task.id > 0,
                      taskIds.insert(task.id).inserted,
                      !task.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      task.type.count <= 100,
                      task.subject.count <= 60,
                      !task.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      task.title.count <= 500,
                      (1...3).contains(task.importance) else {
                    throw StudyError.message("공부 계획의 항목이 손상되었습니다.")
                }
            }
        }
        return box.proposals
    }

    /// Must only be invoked from a direct device-user confirmation action.
    static func decidePlan(token: String, address: String, id: String,
                           approve: Bool) async throws -> String {
        guard let parsed = UUID(uuidString: id), parsed.uuidString.lowercased() == id.lowercased() else {
            throw StudyError.message("계획 ID를 확인하세요.")
        }
        let endpoint = "proposals/" + id + (approve ? "/approve" : "/reject")
        let bytes = try await request(token, endpoint: endpoint,
                                      body: Data("{}".utf8), address: address)
        let response = try JSONDecoder().decode(Decision.self, from: bytes)
        let state = approve ? "approved" : "rejected"
        guard response.id.lowercased() == id.lowercased(), response.state == state else {
            throw StudyError.message("계획 결정 결과를 확인하지 못했습니다.")
        }
        return state
    }

    /// Local-only deterministic test, no network and no user information.
    static func selfTest() -> Bool {
        let task = StudyTask(id: 1730000000000, type: "숙제 항목",
                             subject: "수학", title: "모의 문제",
                             dueDate: "2026-10-22", importance: 2, completed: false)
        let item = try? StudyProgressItem.create(
            subject: "과학", material: "오투", unit: "7-2",
            status: .todo, inExamScope: true)
        guard let item else { return false }
        do {
            let empty = Snapshot(schema: 1, cursor: 0, records: [])
            let initial = try newOnly(tasks: [task], progress: [item], snapshot: empty)
            guard initial.count == 2, initial.allSatisfy({ $0.baseRevision == 0 }) else {
                return false
            }
            func receipt(_ id: String, _ revision: Int, _ cursor: Int) throws -> Data {
                try JSONSerialization.data(withJSONObject: [
                    "schema": 1, "cursor": cursor,
                    "changes": [
                        ["seq": 1, "kind": "task", "id": String(task.id),
                         "revision": 1, "deleted": false, "updatedAt": 1790000000000] as [String: Any],
                        ["seq": 2, "kind": "progress", "id": id,
                         "revision": revision, "deleted": false, "updatedAt": 1790000000000] as [String: Any]
                    ]
                ])
            }
            try verifyChangeAck(receipt(item.id, 1, 2), mutations: initial)
            for (id, revision, cursor) in [
                (UUID().uuidString.lowercased(), 1, 2),
                (item.id, 9, 2),
                (item.id, 1, 44)
            ] {
                do {
                    try verifyChangeAck(receipt(id, revision, cursor), mutations: initial)
                    return false
                } catch { /* forged receipt correctly refused */ }
            }
            // Phase33: a normal edit serializes the user's task, while an
            // explicitly approved server tombstone MUST emit payload:null.
            let revisionReview = ReviewedChange(
                kind: "task", id: String(task.id), baseRevision: 7, deleted: false,
                payload: .task(task), cursor: 9, remoteSummary: "old",
                localSummary: "new", remoteRecord: Data("{}".utf8))
            let encodedUpdate = try JSONEncoder().encode(
                ReviewedMutation(change: revisionReview))
            guard let updateObject = try JSONSerialization.jsonObject(
                with: encodedUpdate) as? [String: Any],
                  (updateObject["baseRevision"] as? Int) == 7,
                  (updateObject["deleted"] as? Bool) == false,
                  updateObject["payload"] as? [String: Any] != nil else {
                return false
            }
            let deletionReview = ReviewedChange(
                kind: "task", id: String(task.id), baseRevision: 7, deleted: true,
                payload: nil, cursor: 9, remoteSummary: "old",
                localSummary: "(absent)", remoteRecord: Data("{}".utf8))
            let encodedDelete = try JSONEncoder().encode(
                ReviewedMutation(change: deletionReview))
            guard let deleteObject = try JSONSerialization.jsonObject(
                with: encodedDelete) as? [String: Any],
                  (deleteObject["deleted"] as? Bool) == true,
                  deleteObject["payload"] is NSNull,
                  try revisionReview.sameApprovedMutation(as: revisionReview),
                  !(try revisionReview.sameApprovedMutation(as: deletionReview)) else {
                return false
            }
            let one = Snapshot(schema: 1, cursor: 1,
                               records: [Record(kind: "task", id: String(task.id),
                                                revision: 9, deleted: false)])
            let filtered = try newOnly(tasks: [task], progress: [item], snapshot: one)
            guard filtered.count == 1, filtered[0].kind == "progress" else { return false }
            let both = Snapshot(schema: 1, cursor: 2,
                                records: one.records +
                                [Record(kind: "progress", id: item.id,
                                        revision: 2, deleted: false)])
            return try newOnly(tasks: [task], progress: [item], snapshot: both).isEmpty
        } catch { return false }
    }
}
