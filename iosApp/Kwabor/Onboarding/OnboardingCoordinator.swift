import Combine
import Foundation
import Shared

@MainActor
final class OnboardingCoordinator: ObservableObject {
    enum Route {
        case intro
        case restoringSession
        case authentication
        case home
    }

    @Published private(set) var route: Route
    @Published private(set) var authState: AuthUiState?
    @Published private(set) var remoteIntroVideoURL: URL?
    @Published var isAuthenticationPresented = false
    @Published var isGuestDisclosurePresented = false

    let bridge: KwaborSharedBridge
    let strings: OnboardingStrings
    let authController: IosAuthController

    var isGuestSession: Bool {
        guestAccessGranted && !(authState?.isAuthenticated ?? false)
    }

    private let observability: FirebaseObservability
    private let cache: IntroVideoCache
    private let userDefaults: UserDefaults
    private var firstLaunchCompleted: Bool
    private var sessionRestoreCompleted = false
    private var guestAccessGranted = false
    private var introDisplayTracked = false
    private var remoteMediaTask: Task<Void, Never>?

    init(
        bridge: KwaborSharedBridge,
        authController: IosAuthController,
        observability: FirebaseObservability,
        cache: IntroVideoCache = IntroVideoCache(),
        userDefaults: UserDefaults = .standard
    ) {
        self.bridge = bridge
        strings = bridge.onboardingStrings()
        self.authController = authController
        self.observability = observability
        self.cache = cache
        self.userDefaults = userDefaults
        let storedFirstLaunchCompleted = userDefaults.bool(forKey: introSeenKey)
        firstLaunchCompleted = storedFirstLaunchCompleted
        route = storedFirstLaunchCompleted ? .restoringSession : .intro

        authController.observe { [weak self] state in
            guard let self else { return }
            authState = state
            if state.isAuthenticated {
                isAuthenticationPresented = false
            }
            resolveRoute()
        }
        authController.restoreSession { [weak self] _ in
            guard let self else { return }
            sessionRestoreCompleted = true
            resolveRoute()
        }
        observability.observeRemoteConfiguration { [weak self] configuration, consent in
            self?.updateRemoteMedia(configuration: configuration, consent: consent)
        }
    }

    func introDisplayed() {
        guard !introDisplayTracked else { return }
        introDisplayTracked = true
        observability.track(bridge.introVideoShownEvent())
    }

    func completeIntro(skipped: Bool) {
        guard !firstLaunchCompleted else { return }
        firstLaunchCompleted = true
        userDefaults.set(true, forKey: introSeenKey)
        if skipped {
            observability.track(bridge.introVideoSkippedEvent())
        }
        resolveRoute()
    }

    func presentAuthentication() {
        isAuthenticationPresented = true
    }

    func requestGuestAccess() {
        isGuestDisclosurePresented = true
    }

    func confirmGuestAccess() {
        isGuestDisclosurePresented = false
        guestAccessGranted = true
        resolveRoute()
    }

    func cancelGuestAccess() {
        isGuestDisclosurePresented = false
    }

    func dismissAuthentication() {
        isAuthenticationPresented = false
    }

    private func resolveRoute() {
        let routeKey = bridge.onboardingEntryKey(
            firstLaunchCompleted: firstLaunchCompleted,
            sessionRestoreCompleted: sessionRestoreCompleted,
            isAuthenticated: authState?.isAuthenticated ?? false,
            guestAccessGranted: guestAccessGranted
        )
        switch routeKey {
        case "intro":
            route = .intro
        case "restoring_session":
            route = .restoringSession
        case "home":
            route = .home
        default:
            route = .authentication
        }
    }

    private func updateRemoteMedia(
        configuration: FirebaseRemoteFeatureConfiguration,
        consent: ObservabilityConsent
    ) {
        remoteMediaTask?.cancel()
        let source = configuration.introVideo
        remoteMediaTask = Task { [weak self, cache, observability] in
            guard let self else { return }
            guard consent.remoteConfigurationAllowed, let source else {
                await cache.clear()
                guard !Task.isCancelled else { return }
                remoteIntroVideoURL = nil
                return
            }
            let resolvedURL = await cache.resolve(source: source)
            guard !Task.isCancelled else { return }
            if resolvedURL == nil {
                observability.recordDiagnostic(.introVideoIntegrityFailed)
            }
            remoteIntroVideoURL = resolvedURL
        }
    }
}

private let introSeenKey = "kwabor.first_launch.intro_seen_v1"
