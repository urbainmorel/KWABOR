import Shared
import Foundation
import SwiftUI

@main
struct KwaborApp: App {
    private let compositionRoot: IosKwaborCompositionRoot
    @StateObject private var coordinator: OnboardingCoordinator

    @MainActor
    init() {
        let observability = FirebaseObservability()
        let compositionRoot = IosKwaborCompositionRoot(
            environmentName: KwaborConfiguration.value("KWABOR_ENVIRONMENT"),
            supabaseUrl: KwaborConfiguration.value("KWABOR_SUPABASE_URL"),
            supabasePublishableKey: KwaborConfiguration.value("KWABOR_SUPABASE_PUBLISHABLE_KEY")
        )
        self.compositionRoot = compositionRoot
        _coordinator = StateObject(
            wrappedValue: OnboardingCoordinator(
                bridge: compositionRoot.bridge,
                authController: compositionRoot.authController,
                registrationController: compositionRoot.registrationController,
                observability: observability
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            OnboardingView(coordinator: coordinator)
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
