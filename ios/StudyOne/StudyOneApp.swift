import SwiftUI

@main
@MainActor
struct StudyOneApp: App {
    // Pending four-store imports are recovered BEFORE the normal study
    // stores/view are used. Failure blocks normal editing until recovery.
    @StateObject private var state: StudyOneStore
    @State private var recoveryError: String?

    init() {
        var failure: String?
        do {
            try StudyFourStoreImport.recoverIfNeeded()
        } catch {
            failure = error.localizedDescription
        }
        // Explicit initialization order: a crash journal is recovered before
        // StudyOneStore reads any previously half-written UserDefaults data.
        _state = StateObject(wrappedValue: StudyOneStore())
        _recoveryError = State(initialValue: failure)
    }

    var body: some Scene {
        WindowGroup {
            if let recoveryError {
                NavigationStack {
                    VStack(spacing: 16) {
                        Image(systemName: "externaldrive.badge.exclamationmark")
                            .font(.largeTitle)
                        Text("이전 데이터 복구 확인 필요")
                            .font(.title2).bold()
                        Text(recoveryError)
                            .font(.callout)
                            .multilineTextAlignment(.center)
                        Text("기존 복구 기록을 삭제하거나 새로 가져오지 마세요. 복구가 확인되기 전까지 일반 학습 화면을 열지 않습니다.")
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                        Button("원본 복구 다시 시도") {
                            do {
                                try StudyFourStoreImport.recoverIfNeeded()
                                // A retry must refresh the already-created store
                                // before reopening the regular editing screens.
                                try state.reloadAfterFourStoreRecovery()
                                self.recoveryError = nil
                            } catch {
                                self.recoveryError = error.localizedDescription
                            }
                        }
                    }
                    .padding()
                }
            } else {
                ContentView()
                    .environmentObject(state)
                    #if DEBUG && targetEnvironment(simulator)
                    .task {
                        // Synthetic, opt-in Simulator-only network exercise.
                        // Never runs on devices or in production; no personal records.
                        await StudySyncRuntimeE2E.runIfRequested()
                        StudyFourStoreRuntimeE2E.runIfRequested()
                    }
                    #endif
            }
        }
    }
}
