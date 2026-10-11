import SwiftUI

@main
struct StudyOneApp: App {
    @StateObject private var state = StudyOneStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(state)
        }
    }
}
