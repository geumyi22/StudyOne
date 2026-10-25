import SwiftUI

@main
struct StudyOneApp: App {
    @StateObject private var state = StudyOneStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(state)
                #if DEBUG && targetEnvironment(simulator)
                .task {
                    // Synthetic, opt-in Simulator-only network exercise.
                    // Never runs on devices or in production; no personal records.
                    await StudySyncRuntimeE2E.runIfRequested()
                }
                #endif
        }
    }
}
