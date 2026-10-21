import SwiftUI

/// A manual, review-first inbox. No changes to local tasks or cloud tasks
/// occur when this view is opened. An explicit confirmed action is required.
struct StudyPlanInboxView: View {
    let token: String
    let serverAddress: String

    @State private var plans: [StudySyncClient.PendingPlan] = []
    @State private var loading = false
    @State private var message = "승인 대기 중인 공부 계획을 조회하세요."
    @State private var selected: StudySyncClient.PendingPlan?
    @State private var approving = false
    @State private var showConfirm = false

    private func reload() {
        guard !loading else { return }
        loading = true
        Task {
            defer { loading = false }
            do {
                plans = try await StudySyncClient.pendingPlans(
                    token: token, address: serverAddress
                )
                message = plans.isEmpty
                    ? "승인 대기 중인 공부 계획이 없습니다."
                    : "AI 제안 \(plans.count)개 · 승인 전에는 실제 일정이 변경되지 않습니다."
            } catch {
                plans = []
                message = "조회 실패: \(error.localizedDescription)"
            }
        }
    }

    private func confirm(_ plan: StudySyncClient.PendingPlan, approve: Bool) {
        selected = plan
        approving = approve
        showConfirm = true
    }

    private func decide() {
        guard let initial = selected, !loading else { return }
        loading = true
        Task {
            defer {
                selected = nil
                loading = false
            }
            do {
                let current = try await StudySyncClient.pendingPlans(
                    token: token, address: serverAddress
                )
                guard let updated = current.first(where: { $0.id == initial.id }) else {
                    throw StudyError.message("이미 처리된 계획입니다. 목록을 다시 확인하세요.")
                }
                let original = try JSONEncoder().encode(initial.tasks)
                let recent = try JSONEncoder().encode(updated.tasks)
                guard original == recent, initial.title == updated.title,
                      initial.taskCount == updated.taskCount else {
                    throw StudyError.message("내용이 변경되어 승인 또는 거부를 중단했습니다.")
                }
                _ = try await StudySyncClient.decidePlan(
                    token: token, address: serverAddress,
                    id: initial.id, approve: approving
                )
                plans = try await StudySyncClient.pendingPlans(
                    token: token, address: serverAddress
                )
                message = approving
                    ? "서버에 공부 계획을 승인했습니다. 현재 iPhone의 일정은 자동으로 변경되지 않습니다."
                    : "공부 계획을 거부했습니다. 실제 일정은 추가되지 않았습니다."
            } catch {
                message = "결정을 확인하지 못했습니다. 서버 목록을 다시 조회하세요. \(error.localizedDescription)"
            }
        }
    }

    var body: some View {
        Form {
            Section {
                Text("계획을 선택해 날짜·과목·세부 항목을 확인하세요. 승인해야만 서버에 새 일정이 추가됩니다.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button {
                    reload()
                } label: {
                    Label("서버 승인 대기함 새로고침", systemImage: "arrow.clockwise")
                }
                .disabled(loading)
                if loading { ProgressView("확인 중…") }
                Text(message)
                    .font(.caption).foregroundStyle(.secondary)
            }

            ForEach(plans) { plan in
                Section {
                    Text(plan.title).font(.headline)
                    Text("\(plan.taskCount)개 공부 항목 · 아직 미승인")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(plan.tasks, id: \.id) { task in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(task.title).font(.subheadline.bold())
                            Text("\(task.dueDate) · \(task.type) · \(task.subject)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    HStack {
                        Button {
                            confirm(plan, approve: true)
                        } label: {
                            Label("전체 승인", systemImage: "checkmark.circle")
                        }
                        .disabled(loading)
                        Spacer()
                        Button(role: .destructive) {
                            confirm(plan, approve: false)
                        } label: {
                            Label("전체 거부", systemImage: "xmark.circle")
                        }
                        .disabled(loading)
                    }
                }
            }

            Section("안전 안내") {
                Text("서버에서 승인하면 실제 서버 일정에 추가됩니다. 앱의 기존 숙제를 덮어쓰지 않습니다. 서버 읽기 전용 토큰으로는 승인하거나 거부할 수 없습니다.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("AI 계획 승인 대기함")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
        .confirmationDialog(
            approving ? "이 공부 계획을 서버 일정에 추가할까요?" : "이 공부 계획을 거부할까요?",
            isPresented: $showConfirm,
            titleVisibility: .visible
        ) {
            Button(approving ? "검토한 계획 승인" : "계획 거부",
                   role: approving ? nil : .destructive) {
                decide()
            }
            Button("취소", role: .cancel) {
                selected = nil
            }
        } message: {
            Text(selected.map { "\($0.title) · \($0.taskCount)개 항목" } ?? "")
        }
    }
}
