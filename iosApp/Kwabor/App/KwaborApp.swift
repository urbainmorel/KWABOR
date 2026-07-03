import Shared
import SwiftUI

@main
struct KwaborApp: App {
    private let bridge = KwaborSharedBridge()

    var body: some Scene {
        WindowGroup {
            ContentView(bridge: bridge)
        }
    }
}
