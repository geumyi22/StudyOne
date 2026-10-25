import Foundation

#if DEBUG && targetEnvironment(simulator)
/// Executed by the actual StudyOne iOS Simulator process, not a server mock.
/// Uses the real StudySyncClient URLSession / encode / decode implementation.
/// No UserDefaults, Keychain, StudyProgressStore or real study data are touched.
enum StudySyncRuntimeE2E {
    private static let mode = "loopback-v1-synthetic"
    private static let resultName = "studyone-ci-ios-network-result.txt"

    static func runIfRequested() async {
        let environment = ProcessInfo.processInfo.environment
        guard environment["STUDYONE_RUN_APP_NETWORK_E2E"] == mode else { return }
        let resultURL = FileManager.default.temporaryDirectory.appendingPathComponent(resultName)
        do {
            let token = environment["STUDYONE_E2E_TOKEN"] ?? ""
            let address = "http://127.0.0.1:8765/"
            let first = try await StudySyncClient.snapshot(token: token, address: address)
            guard first.records.isEmpty, first.cursor == 0 else {
                throw StudyError.message("시험 서버가 초기 상태가 아닙니다.")
            }

            let task = StudyTask(
                id: 1790093110001, type: "숙제 항목", subject: "과학",
                title: "시뮬레이터 통신 합성 과제", dueDate: "2026-11-05",
                importance: 2, completed: false
            )
            let progress = try StudyProgressItem.create(
                subject: "역사", material: "합성 시험 교재",
                unit: "실제 앱 통신 테스트", status: .todo, inExamScope: true
            )
            let pending = try StudySyncClient.newOnly(
                tasks: [task], progress: [progress], snapshot: first
            )
            guard pending.count == 2,
                  pending.allSatisfy({ $0.baseRevision == 0 }) else {
                throw StudyError.message("실제 iOS 클라이언트의 전송 후보가 다릅니다.")
            }
            let applied = try await StudySyncClient.uploadNew(
                token: token, pending: pending, address: address
            )
            guard applied == 2 else {
                throw StudyError.message("서버 저장 확인 항목 수가 일치하지 않습니다.")
            }

            let second = try await StudySyncClient.snapshot(
                token: token, address: address
            )
            guard second.cursor == 2, second.records.count == 2,
                  Set(second.records.map { $0.id })
                    == Set([String(task.id), progress.id]),
                  try StudySyncClient.newOnly(
                    tasks: [task], progress: [progress], snapshot: second
                  ).isEmpty else {
                throw StudyError.message("기기가 보낸 ID가 서버 재조회에서 누락됐습니다.")
            }

            // The same app client must decode the actual received JSON and
            // stage BOTH records for a DIFFERENT, empty client store.
            let remote = try await StudySyncClient.previewIncoming(
                token: token, address: address, localTasks: [], localProgress: []
            )
            guard remote.cursor == 2, remote.newTasks.count == 1,
                  remote.newProgress.count == 1,
                  remote.newTasks[0] == task,
                  remote.newProgress[0].id == progress.id,
                  remote.newProgress[0].status == .todo,
                  remote.conflicts == 0 else {
                throw StudyError.message("두 번째 앱 클라이언트의 수신 기록 검증 실패")
            }

            // A divergent existing ID must be detected and not imported.
            let divergent = StudyTask(
                id: task.id, type: task.type, subject: task.subject,
                title: "다른 기기에서 수정한 합성 기록", dueDate: task.dueDate,
                importance: 2, completed: false
            )
            let conflict = try await StudySyncClient.previewIncoming(
                token: token, address: address,
                localTasks: [divergent], localProgress: [progress]
            )
            guard conflict.conflicts == 1,
                  conflict.newTasks.isEmpty, conflict.newProgress.isEmpty else {
                throw StudyError.message("동일 ID 다른 내용 충돌을 감지하지 못했습니다.")
            }
            let unchanged = try await StudySyncClient.snapshot(
                token: token, address: address
            )
            guard unchanged.cursor == 2 else {
                throw StudyError.message("충돌 미리보기가 서버를 변경했습니다.")
            }

            // Explicit authentication failure: no false success for invalid token.
            do {
                _ = try await StudySyncClient.snapshot(
                    token: "InvalidSyntheticBearerTokenOnlyForAppE2E0123456789",
                    address: address
                )
                throw StudyError.message("잘못된 인증 토큰이 허용됐습니다.")
            } catch let error as StudyError {
                if case .message(let message) = error,
                   message == "잘못된 인증 토큰이 허용됐습니다." {
                    throw error
                }
            }

            let result = "IOS_APP_V1_NETWORK_E2E_PASSED task=1 progress=1 "
                + "cursor=2 conflict=1 denied=1 local_data_unchanged=1"
            try result.write(to: resultURL, atomically: true, encoding: .utf8)
        } catch {
            let safe = "IOS_APP_V1_NETWORK_E2E_FAILED "
                + String(describing: type(of: error))
            try? safe.write(to: resultURL, atomically: true, encoding: .utf8)
        }
    }
}
#endif
