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
        .onDisappear {
            token = ""
            pending = []
        }
    }
}
