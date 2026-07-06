import Shared
import Foundation
import SwiftUI

@main
struct KwaborApp: App {
    private let bridge = KwaborSharedBridge(
        supabaseUrl: KwaborConfiguration.value("KWABOR_SUPABASE_URL"),
        supabasePublishableKey: KwaborConfiguration.value("KWABOR_SUPABASE_PUBLISHABLE_KEY")
    )

    var body: some Scene {
        WindowGroup {
            ContentView(bridge: bridge)
        }
    }
}

private enum KwaborConfiguration {
    static func value(_ key: String) -> String? {
        if let environmentValue = ProcessInfo.processInfo.environment[key], !environmentValue.isEmpty {
            return environmentValue
        }
        if let bundleValue = Bundle.main.object(forInfoDictionaryKey: key) as? String, !bundleValue.isEmpty {
            return bundleValue
        }
        return nil
    }
}
