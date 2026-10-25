import SwiftUI

/// Intentionally not shown in Release builds. Requires the developer's
/// localhost test server; mobile devices do not reach the Windows host via
/// 127.0.0.1 without an explicit simulator/port-forward setup.
struct SyncExperimentView: View {
    @EnvironmentObject private var tasksStore: StudyOneStore
    @Environment(\.dismiss) private var dismiss

    @State private var token = ""
    @State private var serverAddress = StudySyncClient.defaultAddress
    @State private var status = "아직 서버를 조회하지 않았습니다."
    @State private var busy = false
    @State private var pending: [StudySyncClient.Mutation] = []
    @State private var showApproval = false
    @State private var reviewedCursor: Int64?
    @State private var incomingPreview: StudySyncClient.IncomingPlan?
    @State private var incomingTasksBaseline: [StudyTask] = []
    @State private var incomingProgressBaseline: [StudyProgressItem] = []
    @State private var importTasks = true
    @State private var showImportConfirmation = false
    @State private var pendingPlans: [StudySyncClient.PendingPlan] = []
    @State private var selectedPlan: StudySyncClient.PendingPlan?
    @State private var planActionApprove = false
    @State private var showPlanDecision = false
    @State private var queueAccount = ""
    @State private var queueMode = ""
    @State private var showQueueConfirmation = false
    @State private var reviewedKind = "task"
    @State private var reviewedId = ""
    @State private var approvedChange: StudySyncClient.ReviewedChange?
    @State private var approvedAccount = ""
    @State private var showReviewedApproval = false

    private func candidates(snapshot: StudySyncClient.Snapshot) throws
            -> [StudySyncClient.Mutation] {
        let tasks = try tasksStore.checkedTasksForSync()
        // Reload from UserDefaults on every operation; never rely on a stale
        // View model instance of the separately edited progress tab.
        let progress = StudyProgressStore()
        if let problem = progress.loadError {
            throw StudyError.message(problem)
        }
        return try StudySyncClient.newOnly(
            tasks: tasks, progress: progress.items, snapshot: snapshot
        )
    }

    private func reviewRemoteMutation(deleted: Bool) {
        guard !busy else { return }
        busy = true
        status = "계정·서버의 기존 ID를 비교하고 있습니다…"
        Task {
            defer { busy = false }
            do {
                let session = try await StudySyncClient.authenticatedSession(
                    token: token, address: serverAddress)
                guard session.audience == "device", session.scope == "read-write" else {
                    throw StudyError.message("읽기·쓰기 권한의 HTTPS 기기 토큰이 필요합니다.")
                }
                let local = try tasksStore.checkedTasksForSync()
                let progress = StudyProgressStore()
                if let issue = progress.loadError { throw StudyError.message(issue) }
                let review = try await StudySyncClient.prepareReviewedChange(
                    token: token, address: serverAddress,
                    localTasks: local, localProgress: progress.items,
                    kind: reviewedKind, id: reviewedId.trimmingCharacters(in: .whitespacesAndNewlines),
                    deleted: deleted)
                approvedChange = review
                approvedAccount = session.accountId
                status = "원격: \(review.remoteSummary)\n기기: \(review.localSummary)\n"
                    + "서버 revision \(review.baseRevision). 변경 전 복구본을 저장하고 승인받습니다."
                showReviewedApproval = true
            } catch {
                approvedChange = nil
                status = "서버 ID 변경을 준비하지 못했습니다: \(error.localizedDescription)"
            }
        }
    }

    private func prepareIncoming(tasks: Bool) {
        if busy { return }
        busy = true
        status = "서버에서 신규 \(tasks ? "일정" : "진도")을(를) 검증 중…"
        Task {
            defer { busy = false }
            do {
                let localTasks = try tasksStore.checkedTasksForSync()
                let progressStore = StudyProgressStore()
                if let problem = progressStore.loadError {
                    throw StudyError.message(problem)
                }
                let localProgress = progressStore.items
                let plan = try await StudySyncClient.previewIncoming(
                    token: token, address: serverAddress,
                    localTasks: localTasks, localProgress: localProgress)
                let additions = tasks ? plan.newTasks.count : plan.newProgress.count
                if additions == 0 {
                    status = "서버에서 가져올 신규 ID가 없습니다. 기존 기록과 내용이 다른 항목 \(plan.conflicts)개는 건너뛰었습니다."
                    return
                }
                incomingTasksBaseline = localTasks
                incomingProgressBaseline = localProgress
                incomingPreview = plan
                importTasks = tasks
                let names: [String] = tasks
                    ? plan.newTasks.prefix(8).map { $0.subject + " · " + $0.title }
                    : plan.newProgress.prefix(8).map { $0.subject + " · " + $0.unit }
                status = "가져올 \(tasks ? "일정" : "진도") \(additions)개\n"
                    + names.enumerated().map { "\($0.offset + 1). \($0.element)" }
                        .joined(separator: "\n")
                    + "\n기존 기록 충돌 \(plan.conflicts)개는 건너뜁니다."
                showImportConfirmation = true
            } catch {
                incomingPreview = nil
                status = "신규 기록 미리보기 실패 · 로컬 데이터 변경 없음: \(error.localizedDescription)"
            }
        }
    }

    private func applyIncoming() {
        guard let initial = incomingPreview, !busy else { return }
        let selectedType = importTasks
        busy = true
        status = "승인 후 서버·기기 기록을 다시 검증 중…"
        Task {
            defer {
                busy = false
                incomingPreview = nil
            }
            do {
                let localTasks = try tasksStore.checkedTasksForSync()
                let progressStore = StudyProgressStore()
                if let problem = progressStore.loadError {
                    throw StudyError.message(problem)
                }
                guard localTasks == incomingTasksBaseline,
                      progressStore.items == incomingProgressBaseline else {
                    throw StudyError.message(
                        "검토 도중 로컬 기록이 변경되어 가져오기를 차단했습니다.")
                }
                let verified = try await StudySyncClient.previewIncoming(
                    token: token, address: serverAddress,
                    localTasks: localTasks, localProgress: progressStore.items)
                guard initial.matches(verified, importingTasks: selectedType) else {
                    throw StudyError.message(
                        "검토 도중 서버 기록이 변경되어 가져오기를 차단했습니다.")
                }
                let backup: URL
                let count: Int
                if selectedType {
                    backup = try tasksStore.mergeNewTasksFromServer(
                        verified.newTasks, original: localTasks)
                    count = verified.newTasks.count
                } else {
                    backup = try progressStore.mergeNewProgressFromServer(
                        verified.newProgress, original: incomingProgressBaseline)
                    count = verified.newProgress.count
                }
                status = "서버 신규 \(selectedType ? "일정" : "진도") \(count)개를 추가했습니다.\n"
                    + "기존 ID \(verified.conflicts)개는 변경하지 않았습니다.\n"
                    + "원본 백업: \(backup.lastPathComponent)"
            } catch {
                status = "안전 검사 또는 로컬 저장 실패. 기존 데이터·백업을 확인하세요: \(error.localizedDescription)"
            }
        }
    }

    var body: some View {
        Form {
            Section {
                Label("로컬 서버만 연결", systemImage: "lock.shield")
                    .font(.headline)
                Text("보안 계정 서버의 HTTPS 주소와 장치별 토큰이 필요합니다. Debug 시뮬레이터에서만 127.0.0.1:8765 HTTP 실험을 지원합니다. 기존 데이터의 자동 양방향 갱신은 아직 구현되지 않았습니다.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("로컬 서버 인증") {
                TextField("HTTPS 서버 주소 (예: https://example.com/)", text: $serverAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Text("HTTPS는 유효한 도메인·공인 인증서가 필요합니다.")
                    .font(.caption).foregroundStyle(.secondary)
                SecureField("테스트 Bearer 토큰 (저장되지 않음)", text: $token)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("토큰은 앱 설정이나 파일에 저장하지 않습니다. 외부에 공유하지 마세요.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("서버 확인") {
                Button {
                    busy = true
                    status = "로컬 서버 조회 중…"
                    Task {
                        defer { busy = false }
                        do {
                            let remote = try await StudySyncClient.snapshot(token: token, address: serverAddress)
                            let tasks = remote.records.filter { $0.kind == "task" }.count
                            let progress = remote.records.filter { $0.kind == "progress" }.count
                            let local = try tasksStore.checkedTasksForSync()
                            let store = StudyProgressStore()
                            if let issue = store.loadError { throw StudyError.message(issue) }
                            let comparison = try await StudySyncClient.previewIncoming(
                                token: token, address: serverAddress,
                                localTasks: local, localProgress: store.items)
                            guard comparison.cursor == remote.cursor else {
                                throw StudyError.message("서버가 대조 중 변경됐습니다. 다시 확인하세요.")
                            }
                            let outgoing = try StudySyncClient.newOnly(
                                tasks: local, progress: store.items, snapshot: remote)
                            status = "서버 cursor \(remote.cursor) · 일정 \(tasks)개 · 진도 \(progress)개\n"
                                + "서버에만 있는 일정 \(comparison.newTasks.count)개 · 진도 \(comparison.newProgress.count)개\n"
                                + "기기에만 있는 새 기록 \(outgoing.count)개\n"
                                + "일치 \(comparison.unchanged)개 · 같은 ID의 내용 충돌 \(comparison.conflicts)개\n"
                                + "충돌은 자동 변경하지 않습니다. 읽기 전용 조회입니다."
                        } catch {
                            status = "로컬 서버 연결 실패: \(error.localizedDescription)"
                        }
                    }
                } label: {
                    Label("서버 기록 수 확인 (데이터 전송 없음)",
                          systemImage: "magnifyingglass")
                }
                .disabled(busy || token.count < 32)

                Button {
                    busy = true
                    status = "로컬 데이터를 검사하고 서버 ID와 비교 중…"
                    Task {
                        defer { busy = false }
                        do {
                            let remote = try await StudySyncClient.snapshot(token: token, address: serverAddress)
                            let local = try tasksStore.checkedTasksForSync()
                            let store = StudyProgressStore()
                            if let issue = store.loadError { throw StudyError.message(issue) }
                            let compared = try await StudySyncClient.previewIncoming(
                                token: token, address: serverAddress,
                                localTasks: local, localProgress: store.items)
                            guard compared.cursor == remote.cursor else {
                                throw StudyError.message("조회하는 동안 서버가 변경됐습니다.")
                            }
                            guard compared.conflicts == 0 else {
                                throw StudyError.message("같은 ID의 다른 내용 \(compared.conflicts)개를 발견해 업로드를 중단했습니다.")
                            }
                            let result = try StudySyncClient.newOnly(
                                tasks: local, progress: store.items, snapshot: remote)
                            reviewedCursor = remote.cursor
                            pending = result
                            if result.isEmpty {
                                status = "업로드할 새 항목이 없습니다. 기존 서버 데이터를 보존했습니다."
                            } else {
                                status = "업로드 후보 \(result.count)개. 아직 전송하지 않았습니다."
                                showApproval = true
                            }
                        } catch {
                            pending = []
                            status = "업로드 준비 실패. 전송하지 않았습니다: \(error.localizedDescription)"
                        }
                    }
                } label: {
                    Label("서버에 없는 새 항목만 전송", systemImage: "arrow.up.doc")
                }
                .disabled(busy || token.count < 32)

                if busy { ProgressView("확인 중…") }
            }

            Section("서버에서 신규 기록만 가져오기") {
                Text("기존 ID는 덮어쓰지 않으며, 서버의 새 항목만 선택적으로 추가합니다. 승인 직전 데이터 재검증과 앱 내부 원본 백업을 수행합니다.")
                    .font(.caption).foregroundStyle(.secondary)

                Button {
                    prepareIncoming(tasks: true)
                } label: {
                    Label("서버의 신규 일정만 가져오기", systemImage: "tray.and.arrow.down")
                }
                .disabled(busy || token.count < 32)

                Button {
                    prepareIncoming(tasks: false)
                } label: {
                    Label("서버의 신규 진도만 가져오기", systemImage: "square.and.arrow.down")
                }
                .disabled(busy || token.count < 32)
            }

            Section("기존 ID 서버 수정·삭제 · 건별 승인") {
                Picker("대상 종류", selection: $reviewedKind) {
                    Text("일정").tag("task")
                    Text("진도").tag("progress")
                }
                .pickerStyle(.segmented)
                TextField("정확한 일정 ID 또는 진도 UUID", text: $reviewedId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("HTTPS 계정 서버의 기존 ID 하나만 선택합니다. 로컬 기록과 서버 값을 비교하고 승인하기 전까지 서버를 수정하지 않습니다. 삭제는 기기에 같은 ID가 없는 경우에만 가능하며 기존 원격 값은 보호된 복구 파일로 저장합니다.")
                    .font(.caption).foregroundStyle(.secondary)
                Button {
                    reviewRemoteMutation(deleted: false)
                } label: {
                    Label("현재 기기 내용으로 기존 서버 ID 수정 · 확인", systemImage: "square.and.pencil")
                }
                .disabled(busy || token.count < 32 || reviewedId.isEmpty)
                Button(role: .destructive) {
                    reviewRemoteMutation(deleted: true)
                } label: {
                    Label("선택한 서버 ID 삭제 · 별도 확인", systemImage: "trash")
                }
                .disabled(busy || token.count < 32 || reviewedId.isEmpty)
            }

            Section("오프라인 신규 학습 기록 대기열") {
                TextField("대상 계정 UUID (v2/session accountId)", text: $queueAccount)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("HTTPS 서버 주소와 계정 UUID를 대기열에 고정합니다. 토큰은 파일에 저장하지 않으며, 보관된 과목·진도 내용은 iOS 앱 내부에서 기기 잠금으로 보호합니다.")
                    .font(.caption).foregroundStyle(.secondary)
                Button {
                    queueMode = "save"
                    showQueueConfirmation = true
                } label: {
                    Label("새 기록을 오프라인 대기열에 보관",
                          systemImage: "tray.and.arrow.down")
                }
                .disabled(busy)
                Button {
                    queueMode = "send"
                    showQueueConfirmation = true
                } label: {
                    Label("첫 대기 요청 HTTPS 수동 재전송", systemImage: "paperplane")
                }
                .disabled(busy || token.count < 32)
                Button {
                    do {
                        let batches = try StudySyncOutbox.read()
                        status = "대기열: \(batches.count)개 요청 · "
                            + "\(batches.reduce(0) { $0 + $1.items.count })개 신규 기록"
                    } catch {
                        status = "대기열 읽기 실패: \(error.localizedDescription)"
                    }
                } label: {
                    Label("대기열 상태 확인", systemImage: "list.number")
                }
                .disabled(busy)
                Button(role: .destructive) {
                    queueMode = "discard"
                    showQueueConfirmation = true
                } label: {
                    Label("첫 대기 요청 폐기 · 별도 승인", systemImage: "trash")
                }
                .disabled(busy)
                Text("이 대기열은 신규 기록 전용입니다. 기존 서버 ID의 변경·삭제는 위의 별도 건별 검토·승인 메뉴에서만 수행합니다.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("AI 공부 계획 승인 대기함") {
                Text("AI가 제안한 계획은 기기에서 승인하기 전까지 실제 일정으로 등록되지 않습니다. HTTPS 계정 서버에서만 이용할 수 있습니다.")
                    .font(.caption).foregroundStyle(.secondary)

                Button {
                    busy = true
                    status = "AI 공부 계획을 확인 중…"
                    Task {
                        defer { busy = false }
                        do {
                            let result = try await StudySyncClient.pendingPlans(
                                token: token, address: serverAddress)
                            pendingPlans = result
                            status = "승인 대기 중인 AI 공부 계획 \(result.count)개"
                        } catch {
                            pendingPlans = []
                            status = "AI 계획 조회 실패: \(error.localizedDescription)"
                        }
                    }
                } label: {
                    Label("승인 대기 공부 계획 다시 읽기",
                          systemImage: "text.book.closed")
                }
                .disabled(busy || token.count < 32)

                if pendingPlans.isEmpty {
                    Text("아직 승인 대기 목록을 조회하지 않았거나 대기 중인 계획이 없습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                ForEach(pendingPlans) { plan in
                    DisclosureGroup {
                        Text("승인 전에는 실제 공부 일정이 변경되지 않습니다.")
                            .font(.caption).foregroundStyle(.secondary)
                        ForEach(plan.tasks, id: \.id) { task in
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(task.dueDate) · \(task.subject) · \(task.type)")
                                    .font(.caption).foregroundStyle(.secondary)
                                Text(task.title)
                                    .font(.subheadline)
                            }
                        }
                        Button("선택한 계획 승인 · 서버 일정 등록") {
                            selectedPlan = plan
                            planActionApprove = true
                            showPlanDecision = true
                        }
                        .disabled(busy)
                        Button("선택한 계획 거부", role: .destructive) {
                            selectedPlan = plan
                            planActionApprove = false
                            showPlanDecision = true
                        }
                        .disabled(busy)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(plan.title).font(.headline)
                            Text("과목별 공부 항목 \(plan.taskCount)개 · 승인 대기")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section("최근 결과") {
                Text(status)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            Section("보호 및 제한") {
                Text("서버에 이미 존재하는 일정·진도 ID는 건너뛰며, 기존 서버 기록을 수정하거나 삭제하지 않습니다. 전송 승인 전과 직전에 서버와 로컬 데이터가 동일한지 다시 확인합니다.")
                Text("전송 중 연결이 끊기면 일부 배치가 저장됐을 수 있습니다. 서버 목록을 다시 확인한 뒤 진행하세요. 기존 로컬 기록은 변경되지 않습니다.")
                Text("원격 HTTPS 서버와 토큰을 수동 입력해야 사용 가능합니다. 실시간 자동 동기화와 기존 기록 갱신·삭제는 아직 지원하지 않습니다.")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .navigationTitle("로컬 동기화 실험")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            approvedChange?.deleted == true
                ? "선택한 기존 서버 기록을 삭제하시겠습니까?"
                : "선택한 기존 서버 기록을 수정하시겠습니까?",
            isPresented: $showReviewedApproval, titleVisibility: .visible
        ) {
            Button("HTTPS 계정·기록 재검증 후 " +
                   (approvedChange?.deleted == true ? "서버 삭제" : "서버 수정"),
                   role: approvedChange?.deleted == true ? .destructive : nil) {
                guard let first = approvedChange else { return }
                busy = true
                Task {
                    defer {
                        busy = false
                        approvedChange = nil
                    }
                    do {
                        let session = try await StudySyncClient.authenticatedSession(
                            token: token, address: serverAddress)
                        guard session.accountId == approvedAccount,
                              session.audience == "device",
                              session.scope == "read-write" else {
                            throw StudyError.message("승인 후 계정 또는 쓰기 권한이 변경됐습니다.")
                        }
                        let local = try tasksStore.checkedTasksForSync()
                        let progress = StudyProgressStore()
                        if let issue = progress.loadError {
                            throw StudyError.message(issue)
                        }
                        let next = try await StudySyncClient.prepareReviewedChange(
                            token: token, address: serverAddress,
                            localTasks: local, localProgress: progress.items,
                            kind: first.kind, id: first.id, deleted: first.deleted)
                        guard try next.sameApprovedMutation(as: first) else {
                            throw StudyError.message("승인 중 서버·기기 기록이 변경됐습니다.")
                        }
                        let backup = try StudySyncClient.saveReviewedRecovery(
                            next, session: session, endpoint: serverAddress)
                        try await StudySyncClient.uploadReviewedChange(
                            token: token, address: serverAddress, reviewed: next)
                        status = "기존 ID \(first.id) 서버 "
                            + (first.deleted ? "삭제" : "수정")
                            + " 확인 · 복구 파일 \(backup)\n기기 원본은 변경하지 않았습니다."
                    } catch {
                        status = "서버 변경 실패 또는 응답 미확인. 다시 시도하기 전에 서버를 조회하세요: "
                            + error.localizedDescription
                    }
                }
            }
            Button("취소", role: .cancel) { approvedChange = nil }
        } message: {
            Text("계정 \(approvedAccount) · "
                 + (approvedChange?.remoteSummary ?? "")
                 + " → " + (approvedChange?.localSummary ?? "")
                 + ". 승인 후 로컬·원격 값을 다시 확인하고 기존 원격 JSON을 복구 폴더에 보존합니다.")
        }
        .confirmationDialog(
            queueMode == "save" ? "학습 기록을 기기에 보관하시겠습니까?"
            : queueMode == "send" ? "첫 대기 요청을 서버에 전송하시겠습니까?"
            : "첫 대기 요청을 폐기하시겠습니까?",
            isPresented: $showQueueConfirmation, titleVisibility: .visible
        ) {
            Button(queueMode == "save" ? "로컬 대기열 보관"
                   : queueMode == "send" ? "HTTPS 계정 확인 후 수동 전송"
                   : "대기 요청만 폐기",
                   role: queueMode == "discard" ? .destructive : nil) {
                let mode = queueMode
                busy = true
                Task {
                    defer { busy = false }
                    do {
                        switch mode {
                        case "save":
                            let tasks = try tasksStore.checkedTasksForSync()
                            let progress = StudyProgressStore()
                            if let issue = progress.loadError {
                                throw StudyError.message(issue)
                            }
                            let amount = try StudySyncOutbox.enqueue(
                                tasks: tasks, progress: progress.items,
                                account: queueAccount, endpoint: serverAddress)
                            status = "새 기록 \(amount)개를 기기 대기열에 보관했습니다. 서버로 전송하지 않았습니다."
                        case "send":
                            guard let first = try StudySyncOutbox.read().first else {
                                throw StudyError.message("오프라인 대기열이 비어 있습니다.")
                            }
                            guard first.accountId == queueAccount.lowercased() else {
                                throw StudyError.message("입력한 계정이 첫 대기 요청과 다릅니다.")
                            }
                            let localTasks = try tasksStore.checkedTasksForSync()
                            let progress = StudyProgressStore()
                            if let issue = progress.loadError {
                                throw StudyError.message(issue)
                            }
                            try StudySyncOutbox.validateCurrent(first,
                                tasks: localTasks, progress: progress.items)
                            let session = try await StudySyncClient.authenticatedSession(
                                token: token, address: serverAddress)
                            try StudySyncOutbox.validateSession(first,
                                session: session, endpoint: serverAddress)
                            // Recheck local contents and authenticated identity
                            // after the network call and before any write.
                            let latest = try tasksStore.checkedTasksForSync()
                            let latestProgress = StudyProgressStore()
                            if let issue = latestProgress.loadError {
                                throw StudyError.message(issue)
                            }
                            try StudySyncOutbox.validateCurrent(first,
                                tasks: latest, progress: latestProgress.items)
                            guard try StudySyncOutbox.read().first?.id == first.id else {
                                throw StudyError.message("대기열이 변경되어 전송을 차단했습니다.")
                            }
                            let sent = try await StudySyncClient.uploadQueuedBatch(
                                token: token, address: serverAddress, batch: first)
                            try StudySyncOutbox.acknowledge(first.id)
                            status = "서버에서 신규 기록 \(sent)개를 확인했습니다. 처리한 대기 요청은 삭제했습니다."
                        case "discard":
                            guard let first = try StudySyncOutbox.read().first else {
                                throw StudyError.message("대기열이 비어 있습니다.")
                            }
                            try StudySyncOutbox.acknowledge(first.id)
                            status = "첫 대기 요청을 폐기했습니다. 실제 일정·진도는 보존했습니다."
                        default:
                            throw StudyError.message("지원하지 않는 대기열 동작입니다.")
                        }
                    } catch {
                        status = "요청이 실패했거나 서버 결과를 확인하지 못했습니다. "
                            + "대기열·요청 ID는 보존합니다: \(error.localizedDescription)"
                    }
                }
            }
            Button("취소", role: .cancel) { queueMode = "" }
        } message: {
            Text(queueMode == "send"
                ? "동일 계정·HTTPS 서버·쓰기 권한을 확인하고 신규 항목만 전송합니다. 서버가 응답하지 않으면 같은 요청 ID를 보존합니다."
                : queueMode == "save"
                ? "기기 내부에 암호화된 토큰 없이 개인 학습 기록을 저장합니다. 외부 서버 업로드는 하지 않습니다."
                : "대기열의 첫 요청만 제거합니다. 현재 학습 원본은 삭제되지 않습니다.")
        }
        .confirmationDialog(
            "새 학습 기록 \(pending.count)개를 로컬 서버로 보낼까요?",
            isPresented: $showApproval, titleVisibility: .visible
        ) {
            Button("신규 기록만 서버에 전송", role: .destructive) {
                let expected = pending
                busy = true
                status = "전송 직전 변경 사항을 확인 중…"
                Task {
                    defer {
                        pending = []
                        reviewedCursor = nil
                        busy = false
                    }
                    do {
                        let remote = try await StudySyncClient.snapshot(token: token, address: serverAddress)
                        let latest = try candidates(snapshot: remote)
                        let encoder = JSONEncoder()
                        guard remote.cursor == reviewedCursor,
                              try encoder.encode(latest) == encoder.encode(expected) else {
                            throw StudyError.message(
                                "확인 중 기록이 변경되었습니다. 전송하지 않고 다시 조회합니다."
                            )
                        }
                        let sent = try await StudySyncClient.uploadNew(token: token, pending: latest, address: serverAddress)
                        status = "새 항목 \(sent)개를 로컬 서버로 전송했습니다. "
                            + "기존 기록은 덮어쓰거나 삭제하지 않았습니다."
                    } catch {
                        status = "전송 중단: \(error.localizedDescription)"
                    }
                }
            }
            Button("취소", role: .cancel) {
                pending = []
                status = "전송 취소 · 데이터 업로드 없음"
            }
        } message: {
            Text("일정·과목·교재·진도 정보가 이 기기의 SQLite 서버에 저장됩니다. 기존 기록은 수정하지 않습니다.")
        }
        .confirmationDialog(
            "이 공부 계획을 \(planActionApprove ? "승인" : "거부")하시겠습니까?",
            isPresented: $showPlanDecision,
            titleVisibility: .visible
        ) {
            Button(planActionApprove
                   ? "승인 · 실제 서버 일정 추가"
                   : "거부 · 실제 일정 변경 없음",
                   role: .destructive) {
                guard let selected = selectedPlan else { return }
                let approval = planActionApprove
                busy = true
                status = "현재 서버에 대기 중인 계획과 비교 중…"
                Task {
                    defer {
                        busy = false
                        selectedPlan = nil
                    }
                    do {
                        let current = try await StudySyncClient.pendingPlans(
                            token: token, address: serverAddress)
                        guard let latest = current.first(where: { $0.id == selected.id }),
                              latest.state == "pending",
                              latest.title == selected.title,
                              latest.expiresAt == selected.expiresAt,
                              latest.tasks == selected.tasks else {
                            throw StudyError.message(
                                "서버 계획이 변경되었습니다. 다시 조회 후 결정하세요.")
                        }
                        _ = try await StudySyncClient.decidePlan(
                            token: token, address: serverAddress,
                            id: selected.id, approve: approval)
                        pendingPlans = try await StudySyncClient.pendingPlans(
                            token: token, address: serverAddress)
                        status = approval
                            ? "계획 승인 완료. HTTPS 서버에 공부 일정이 등록됐습니다. 이 iPhone의 로컬 목록에는 자동 반영되지 않습니다."
                            : "계획을 거부했습니다. 기존 일정은 변경되지 않았습니다."
                    } catch {
                        status = "계획 처리 상태를 확인하지 못했습니다. 다시 목록을 조회하세요: \(error.localizedDescription)"
                    }
                }
            }
            Button("취소", role: .cancel) { selectedPlan = nil }
        } message: {
            Text("이 기기의 계정 토큰으로 결정합니다. 승인하면 서버에 실제 숙제 항목이 생성됩니다.")
        }
        .confirmationDialog(
            "서버 신규 \(importTasks ? "일정" : "진도")을(를) 추가하시겠습니까?",
            isPresented: $showImportConfirmation,
            titleVisibility: .visible
        ) {
            Button("백업 후 신규 항목만 추가") {
                applyIncoming()
            }
            Button("취소", role: .cancel) {
                incomingPreview = nil
                status = "가져오기를 취소했습니다. 기기의 기존 기록은 그대로입니다."
            }
        } message: {
            Text("서버에서만 존재하는 항목만 추가합니다. 기존 ID의 기록은 변경하거나 삭제하지 않습니다.")
        }
        .onDisappear {
            token = ""
            pending = []
            incomingPreview = nil
            incomingTasksBaseline = []
            incomingProgressBaseline = []
            selectedPlan = nil
            pendingPlans = []
        }
    }
}
