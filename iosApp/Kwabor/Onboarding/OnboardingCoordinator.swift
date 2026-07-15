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
    @Published private(set) var introVideoURL: URL?
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
    private let telemetry: OnboardingTelemetry
    private let introStore: IntroVideoPresentationStore
    private let bundledIntroVideoURL: URL?
    private var firstLaunchCompleted: Bool
    private var sessionRestoreCompleted = false
    private var guestAccessGranted = false
    private var introDisplayTracked = false
    private var launchIntroDecisionCompleted: Bool
    private var launchIntroCompleted = false
    private var launchIntroRevision: Int64?
    private var bundledFallbackAttempted = false
    private var deferredRemoteMedia: DeferredRemoteMedia?
    private var remoteMediaTask: Task<Void, Never>?
    private var remoteMediaRevisionInFlight: Int64?

    init(
        bridge: KwaborSharedBridge,
        authController: IosAuthController,
        observability: FirebaseObservability,
        cache: IntroVideoCache = IntroVideoCache(),
        userDefaults: UserDefaults = .standard,
        bundle: Bundle = .main
    ) {
        self.bridge = bridge
        strings = bridge.onboardingStrings()
        self.authController = authController
        self.observability = observability
        self.cache = cache
        telemetry = bridge.onboardingTelemetry()
        let bundledIntroVideoURL = bundle.url(
            forResource: bundledIntroName,
            withExtension: mp4Extension
        )
        self.bundledIntroVideoURL = bundledIntroVideoURL

        let introStore = IntroVideoPresentationStore(userDefaults: userDefaults)
        self.introStore = introStore
        let storedFirstLaunchCompleted = introStore.firstLaunchCompleted
        firstLaunchCompleted = storedFirstLaunchCompleted

        let hadPendingVideo = introStore.hasPendingVideo
        let storedPendingVideo = introStore.pendingVideoNewerThanLastPresented()
        let activeRemoteVideo = observability.remoteConfiguration.introVideo
        let pendingAtLaunch: PendingIntroVideo?
        if observability.consent.remoteConfigurationAllowed,
           storedFirstLaunchCompleted,
           let storedPendingVideo,
           let activeRemoteVideo,
           storedPendingVideo.matches(activeRemoteVideo) {
            pendingAtLaunch = storedPendingVideo
        } else {
            pendingAtLaunch = nil
            introStore.clearPendingVideo()
        }
        let purgeRejectedPendingBeforeObservation = hadPendingVideo && pendingAtLaunch == nil

        if storedFirstLaunchCompleted {
            route = .restoringSession
            introVideoURL = nil
            launchIntroDecisionCompleted = pendingAtLaunch == nil
        } else {
            route = .intro
            introVideoURL = bundledIntroVideoURL
            launchIntroDecisionCompleted = true
        }

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

        if let pendingAtLaunch {
            Task { [weak self, cache] in
                let resolvedURL = await cache.resolveCached(
                    revision: pendingAtLaunch.revision,
                    sha256: pendingAtLaunch.sha256
                )
                guard let self else { return }
                finishLaunchIntroDecision(pending: pendingAtLaunch, resolvedURL: resolvedURL)
            }
        }

        if purgeRejectedPendingBeforeObservation {
            Task { [weak self, cache] in
                await cache.clear()
                self?.startObservingRemoteConfiguration()
            }
        } else {
            startObservingRemoteConfiguration()
        }
    }

    func introDisplayed() {
        guard !introDisplayTracked else { return }
        introDisplayTracked = true
        observability.track(telemetry.shownEvent)
    }

    func completeIntro(skipped: Bool) {
        guard !launchIntroCompleted else { return }
        launchIntroCompleted = true
        if !firstLaunchCompleted {
            firstLaunchCompleted = true
            introStore.firstLaunchCompleted = true
        }
        if let launchIntroRevision {
            introStore.markRemoteVideoPresented(revision: launchIntroRevision)
        }
        if skipped {
            observability.track(telemetry.skippedEvent)
        }
        resolveRoute()
        processDeferredRemoteMediaIfPossible()
    }

    func introPlaybackFailed() {
        guard !launchIntroCompleted else { return }
        if launchIntroRevision != nil,
           !bundledFallbackAttempted,
           let bundledIntroVideoURL {
            bundledFallbackAttempted = true
            introVideoURL = bundledIntroVideoURL
        } else {
            introVideoURL = nil
        }
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

    private var shouldPresentLaunchIntro: Bool {
        guard launchIntroDecisionCompleted, !launchIntroCompleted else { return false }
        return !firstLaunchCompleted || launchIntroRevision != nil
    }

    private func resolveRoute() {
        guard launchIntroDecisionCompleted else {
            route = .restoringSession
            return
        }
        if shouldPresentLaunchIntro {
            route = .intro
            return
        }

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

    private func finishLaunchIntroDecision(
        pending: PendingIntroVideo,
        resolvedURL: URL?
    ) {
        guard !launchIntroDecisionCompleted else { return }
        if observability.consent.remoteConfigurationAllowed,
           let activeRemoteVideo = observability.remoteConfiguration.introVideo,
           pending.matches(activeRemoteVideo),
           let resolvedURL {
            introVideoURL = resolvedURL
            launchIntroRevision = pending.revision
        } else {
            introStore.clearPendingVideo(ifRevision: pending.revision)
        }
        launchIntroDecisionCompleted = true
        resolveRoute()
        processDeferredRemoteMediaIfPossible()
    }

    private func updateRemoteMedia(
        configuration: FirebaseRemoteFeatureConfiguration,
        consent: ObservabilityConsent
    ) {
        guard consent.remoteConfigurationAllowed else {
            deferredRemoteMedia = nil
            purgePendingRemoteMedia()
            return
        }
        deferredRemoteMedia = DeferredRemoteMedia(configuration: configuration, consent: consent)
        processDeferredRemoteMediaIfPossible()
    }

    private func startObservingRemoteConfiguration() {
        observability.observeRemoteConfiguration { [weak self] configuration, consent in
            self?.updateRemoteMedia(configuration: configuration, consent: consent)
        }
    }

    private func processDeferredRemoteMediaIfPossible() {
        guard launchIntroDecisionCompleted, !shouldPresentLaunchIntro,
              let deferredRemoteMedia else {
            return
        }
        self.deferredRemoteMedia = nil

        guard deferredRemoteMedia.consent.remoteConfigurationAllowed else {
            purgePendingRemoteMedia()
            return
        }
        guard let source = deferredRemoteMedia.configuration.introVideo else {
            purgePendingRemoteMedia()
            return
        }

        let latestKnownRevision = max(
            introStore.latestKnownRemoteRevision,
            remoteMediaRevisionInFlight ?? noRemoteRevision
        )
        guard source.revision > latestKnownRevision else { return }

        remoteMediaTask?.cancel()
        remoteMediaRevisionInFlight = source.revision
        introStore.clearPendingVideo()
        let telemetry = self.telemetry
        remoteMediaTask = Task { [weak self, cache, observability] in
            let resolvedURL = await cache.resolve(source: source)
            guard let self, !Task.isCancelled,
                  remoteMediaRevisionInFlight == source.revision else {
                return
            }
            remoteMediaRevisionInFlight = nil
            guard observability.consent.remoteConfigurationAllowed,
                  source.revision > introStore.latestKnownRemoteRevision else {
                return
            }
            guard resolvedURL != nil else {
                observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
                return
            }
            introStore.savePendingVideoIfNewer(source)
        }
    }

    private func purgePendingRemoteMedia() {
        remoteMediaTask?.cancel()
        remoteMediaRevisionInFlight = nil
        introStore.clearPendingVideo()
        remoteMediaTask = Task { [cache] in
            await cache.clear()
        }
    }
}

private struct DeferredRemoteMedia {
    let configuration: FirebaseRemoteFeatureConfiguration
    let consent: ObservabilityConsent
}

struct PendingIntroVideo {
    let revision: Int64
    let sha256: String

    func matches(_ source: FirebaseRemoteIntroVideo) -> Bool {
        revision == source.revision && sha256 == source.sha256
    }
}

struct IntroVideoPresentationStore {
    let userDefaults: UserDefaults

    var firstLaunchCompleted: Bool {
        get { userDefaults.bool(forKey: introSeenKey) }
        nonmutating set { userDefaults.set(newValue, forKey: introSeenKey) }
    }

    var lastPresentedRemoteRevision: Int64 {
        Int64(userDefaults.integer(forKey: lastPresentedRemoteRevisionKey))
    }

    var validPendingRemoteRevision: Int64? {
        pendingVideoNewerThanLastPresented()?.revision
    }

    var latestKnownRemoteRevision: Int64 {
        max(lastPresentedRemoteRevision, validPendingRemoteRevision ?? noRemoteRevision)
    }

    var hasPendingVideo: Bool {
        userDefaults.object(forKey: pendingRemoteRevisionKey) != nil ||
            userDefaults.object(forKey: pendingRemoteSHA256Key) != nil
    }

    func pendingVideoNewerThanLastPresented() -> PendingIntroVideo? {
        let revision = Int64(userDefaults.integer(forKey: pendingRemoteRevisionKey))
        guard revision > lastPresentedRemoteRevision,
              let sha256 = userDefaults.string(forKey: pendingRemoteSHA256Key),
              sha256.range(of: sha256Pattern, options: .regularExpression) != nil else {
            clearPendingVideo()
            return nil
        }
        return PendingIntroVideo(revision: revision, sha256: sha256)
    }

    func savePendingVideoIfNewer(_ source: FirebaseRemoteIntroVideo) {
        guard source.revision > latestKnownRemoteRevision else { return }
        userDefaults.set(source.revision, forKey: pendingRemoteRevisionKey)
        userDefaults.set(source.sha256, forKey: pendingRemoteSHA256Key)
    }

    func markRemoteVideoPresented(revision: Int64) {
        let presentedRevision = max(lastPresentedRemoteRevision, revision)
        userDefaults.set(presentedRevision, forKey: lastPresentedRemoteRevisionKey)
        if Int64(userDefaults.integer(forKey: pendingRemoteRevisionKey)) <= presentedRevision {
            clearPendingVideo()
        }
    }

    func clearPendingVideo(ifRevision revision: Int64? = nil) {
        if let revision,
           Int64(userDefaults.integer(forKey: pendingRemoteRevisionKey)) != revision {
            return
        }
        userDefaults.removeObject(forKey: pendingRemoteRevisionKey)
        userDefaults.removeObject(forKey: pendingRemoteSHA256Key)
    }
}

private let bundledIntroName = "KwaborIntro"
private let mp4Extension = "mp4"
private let introSeenKey = "kwabor.first_launch.intro_seen_v1"
private let pendingRemoteRevisionKey = "kwabor.intro.pending_remote_revision"
private let pendingRemoteSHA256Key = "kwabor.intro.pending_remote_sha256"
private let lastPresentedRemoteRevisionKey = "kwabor.intro.last_presented_remote_revision"
private let noRemoteRevision: Int64 = 0
private let sha256Pattern = "^[a-f0-9]{64}$"
