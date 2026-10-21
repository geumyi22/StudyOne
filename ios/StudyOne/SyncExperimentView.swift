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
    @State private var pendingPlans: [StudySyncClient.PendingPlan] = []
    @State private var selectedPlan: StudySyncClient.PendingPlan?
    @State private var planActionApprove = false
    @State private var showPlanDecision = false

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
                            status = "서버 조회 성공 · 변경 cursor \(remote.cursor)\n"
                                + "일정 \(tasks)개 · 진도 \(progress)개\n"
                                + "개인 데이터를 전송하지 않았습니다."
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
                            let result = try candidates(snapshot: remote)
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

            Section("ChatGPT · AI 공부 계획 승인") {
                NavigationLink {
                    StudyPlanInboxView(token: token, serverAddress: serverAddress)
                } label: {
                    Label("승인 대기함 보기", systemImage: "checklist.checked")
                }
                .disabled(busy || token.count < 32 ||
                          !serverAddress.lowercased().hasPrefix("https://"))
                Text("검토·승인해야만 서버에 일정이 생성됩니다. "
                     + "현재 기기에 자동으로 기록을 덮어쓰거나 삭제하지 않습니다.")
                    .font(.caption).foregroundStyle(.secondary)
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
                        busy = false
                    }
                    do {
                        let remote = try await StudySyncClient.snapshot(token: token, address: serverAddress)
                        let latest = try candidates(snapshot: remote)
                        let encoder = JSONEncoder()
                        guard try encoder.encode(latest) == encoder.encode(expected) else {
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
        .onDisappear {
            token = ""
            pending = []
            selectedPlan = nil
            pendingPlans = []
        }
    }
}
