import Combine
import Foundation
import Shared

@MainActor
final class OnboardingCoordinator: ObservableObject {
    enum Route {
        case intro
        case restoringSession
        case authentication
        case notificationPriming
        case home
    }

    @Published private(set) var route: Route
    @Published private(set) var authState: AuthUiState?
    @Published private(set) var introVideoURL: URL?
    @Published private(set) var registrationCancellationErrorMessage: String?
    @Published private(set) var accountSignOutErrorMessage: String?
    @Published private(set) var interruptedRegistrationEmail: String?
    @Published var isAuthenticationPresented = false
    @Published var isRegistrationPresented = false
    @Published var isGuestDisclosurePresented = false
    @Published private(set) var isCancellingRegistration = false
    @Published private(set) var isSigningOutAccount = false
    @Published private(set) var isRequestingNotificationsAfterSessionRestore = false

    let bridge: KwaborSharedBridge
    let strings: OnboardingStrings
    let authController: IosAuthController
    let passwordRecoveryController: IosPasswordRecoveryController
    let registrationController: IosRegistrationController
    let registrationLocationProvider: RegistrationLocationProviding
    let registrationNotificationPermissionRequester: RegistrationNotificationPermissionRequesting
    let registrationNotificationPrimingStore: RegistrationNotificationPrimingPersisting
    let interruptedAuthJourneyStore: InterruptedAuthJourneyPersisting

    var isGuestSession: Bool {
        guestAccessGranted && !hasCompleteAccount
    }

    var requiresInterruptedRegistrationPasswordSignIn: Bool {
        interruptedAuthJourneyStore.current == .registration
    }

    var requiresProtectedAuthentication: Bool {
        !AuthSessionBootstrapPolicy.canExposeAuthenticatedSession(
            freshInstallCleanupCompleted: freshInstallSessionCleanupCompleted
        ) || requiresInterruptedRegistrationPasswordSignIn
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
    private var completedRegistrationSession: AuthSession?
    private var shouldPresentRegistrationAfterAuthenticationDismissal = false
    private var isRevokingInterruptedRegistrationSession = false
    private var isReplacingInterruptedRegistrationSession = false
    private var freshInstallSessionCleanupCompleted: Bool

    init(
        bridge: KwaborSharedBridge,
        authController: IosAuthController,
        passwordRecoveryController: IosPasswordRecoveryController,
        registrationController: IosRegistrationController,
        observability: FirebaseObservability,
        registrationLocationProvider: RegistrationLocationProviding? = nil,
        registrationNotificationPermissionRequester: RegistrationNotificationPermissionRequesting? = nil,
        registrationNotificationPrimingStore: RegistrationNotificationPrimingPersisting? = nil,
        cache: IntroVideoCache = IntroVideoCache(),
        userDefaults: UserDefaults = .standard,
        interruptedAuthJourneyStore: InterruptedAuthJourneyPersisting? = nil,
        bundle: Bundle = .main
    ) {
        self.bridge = bridge
        strings = bridge.onboardingStrings()
        self.authController = authController
        self.passwordRecoveryController = passwordRecoveryController
        self.registrationController = registrationController
        self.observability = observability
        self.registrationLocationProvider = registrationLocationProvider ?? CoreLocationRegistrationService()
        self.registrationNotificationPermissionRequester = registrationNotificationPermissionRequester ??
            UserNotificationRegistrationService()
        self.registrationNotificationPrimingStore = registrationNotificationPrimingStore ??
            UserDefaultsRegistrationNotificationPrimingStore(userDefaults: userDefaults)
        self.interruptedAuthJourneyStore = interruptedAuthJourneyStore ??
            UserDefaultsInterruptedAuthJourneyStore(userDefaults: userDefaults)
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
        freshInstallSessionCleanupCompleted = storedFirstLaunchCompleted

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
            if !state.isLoading {
                handleAuthState(state)
            }
            resolveRoute()
        }
        switch AuthSessionBootstrapPolicy.action(hasInstallationMarker: storedFirstLaunchCompleted) {
        case .clearLocalSession:
            clearFreshInstallSessionBeforeRestore()
        case .restoreSession:
            restoreSessionAfterBootstrap()
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
        markFirstLaunchCompletedIfEligible()
        if let launchIntroRevision {
            introStore.markRemoteVideoPresented(revision: launchIntroRevision)
        }
        if skipped {
            observability.track(telemetry.skippedEvent)
        }
        resolveRoute()
        presentInterruptedRegistrationSignInIfPossible()
        presentPasswordRecoveryIfPossible()
        presentIncompleteRegistrationIfPossible()
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

    func presentRegistration() {
        guard !requiresProtectedAuthentication else { return }
        registrationCancellationErrorMessage = nil
        if authState?.hasSession != true {
            registrationController.reset()
        }
        isRegistrationPresented = true
    }

    func presentRegistrationFromAuthentication() {
        guard !requiresProtectedAuthentication else { return }
        guard !shouldPresentRegistrationAfterAuthenticationDismissal else { return }
        shouldPresentRegistrationAfterAuthenticationDismissal = true
        isAuthenticationPresented = false
    }

    func authenticationPresentationDismissed() {
        guard !requiresProtectedAuthentication else {
            shouldPresentRegistrationAfterAuthenticationDismissal = false
            isAuthenticationPresented = true
            return
        }
        guard shouldPresentRegistrationAfterAuthenticationDismissal else { return }
        shouldPresentRegistrationAfterAuthenticationDismissal = false
        presentRegistration()
    }

    func applyRegistrationObservabilityConsent(_ consent: ObservabilityConsent) {
        observability.updateConsent(consent)
    }

    func completeRegistration(_ session: AuthSession) {
        registrationCancellationErrorMessage = nil
        completedRegistrationSession = session
        guestAccessGranted = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        registrationController.reset()
        resolveRoute()
        authController.restoreSession { _ in }
    }

    func handleExistingRegistrationAccount(email: String?) {
        interruptedAuthJourneyStore.mark(.registration)
        interruptedRegistrationEmail = normalizedEmail(email)
        completedRegistrationSession = nil
        guestAccessGranted = false
        isRegistrationPresented = false
        registrationController.reset()
        scheduleInterruptedRegistrationRevocation()
        resolveRoute()
    }

    func signIn(
        email: String,
        password: String,
        onCompleted: @escaping (Bool) -> Void
    ) {
        let replacesInterruptedRegistration = interruptedAuthJourneyStore.current == .registration
        if replacesInterruptedRegistration {
            isReplacingInterruptedRegistrationSession = true
            completedRegistrationSession = nil
            guestAccessGranted = false
            isRegistrationPresented = false
        }
        authController.signInWithEmail(email: email, password: password) { [weak self] completed in
            guard let self else { return }
            let didComplete = completed.boolValue
            if replacesInterruptedRegistration {
                finishInterruptedRegistrationPasswordSignIn(completed: didComplete)
            }
            onCompleted(didComplete)
        }
    }

    func refreshSessionState() {
        authController.restoreSession { _ in }
    }

    func signOutCurrentAccount() {
        guard !isSigningOutAccount else { return }
        accountSignOutErrorMessage = nil
        isSigningOutAccount = true
        guestAccessGranted = true
        shouldPresentRegistrationAfterAuthenticationDismissal = false
        authController.signOut { [weak self] completed in
            guard let self else { return }
            isSigningOutAccount = false
            if completed.boolValue {
                completedRegistrationSession = nil
                isAuthenticationPresented = false
                isRegistrationPresented = false
                registrationController.reset()
            } else {
                guestAccessGranted = false
                accountSignOutErrorMessage = authState?.errorMessage ?? strings.authUnavailable
            }
            resolveRoute()
        }
    }

    func clearAccountSignOutError() {
        accountSignOutErrorMessage = nil
    }

    func cancelRegistration(requiresSignOut: Bool) {
        guard !isCancellingRegistration else { return }
        registrationCancellationErrorMessage = nil
        guard requiresSignOut else {
            registrationController.reset()
            isRegistrationPresented = false
            resolveRoute()
            return
        }
        isCancellingRegistration = true
        authController.signOut { [weak self] completed in
            guard let self else { return }
            isCancellingRegistration = false
            if completed.boolValue {
                completedRegistrationSession = nil
                registrationController.reset()
                isRegistrationPresented = false
                resolveRoute()
            } else {
                registrationCancellationErrorMessage = authState?.errorMessage ?? strings.authUnavailable
            }
        }
    }

    func registrationPresentationDismissed() {
        guard !isRegistrationPresented, authState?.hasSession != true else { return }
        registrationController.reset()
    }

    func requestGuestAccess() {
        guard !requiresProtectedAuthentication else {
            isGuestDisclosurePresented = false
            guestAccessGranted = false
            isAuthenticationPresented = true
            resolveRoute()
            return
        }
        isGuestDisclosurePresented = true
    }

    func confirmGuestAccess() {
        guard !requiresProtectedAuthentication else {
            isGuestDisclosurePresented = false
            guestAccessGranted = false
            isAuthenticationPresented = true
            resolveRoute()
            return
        }
        isGuestDisclosurePresented = false
        guestAccessGranted = true
        resolveRoute()
    }

    func cancelGuestAccess() {
        isGuestDisclosurePresented = false
    }

    func dismissAuthentication() {
        guard !requiresProtectedAuthentication else {
            isAuthenticationPresented = true
            return
        }
        shouldPresentRegistrationAfterAuthenticationDismissal = false
        isAuthenticationPresented = false
    }

    func enableNotificationsAfterSessionRestore() {
        guard route == .notificationPriming,
              !isRequestingNotificationsAfterSessionRestore else {
            return
        }
        isRequestingNotificationsAfterSessionRestore = true
        let requester = registrationNotificationPermissionRequester
        Task { [weak self, requester] in
            _ = await requester.requestPermission()
            guard let self else { return }
            completeNotificationsAfterSessionRestore()
        }
    }

    func skipNotificationsAfterSessionRestore() {
        guard route == .notificationPriming,
              !isRequestingNotificationsAfterSessionRestore else {
            return
        }
        completeNotificationsAfterSessionRestore()
    }

    private var shouldPresentLaunchIntro: Bool {
        guard launchIntroDecisionCompleted, !launchIntroCompleted else { return false }
        return !firstLaunchCompleted || launchIntroRevision != nil
    }

    private var hasCompleteAccount: Bool {
        guard AuthSessionBootstrapPolicy.canExposeAuthenticatedSession(
            freshInstallCleanupCompleted: freshInstallSessionCleanupCompleted
        ) else { return false }
        guard interruptedAuthJourneyStore.current != .registration else { return false }
        return (authState?.isAuthenticated ?? false) || completedRegistrationSession != nil
    }

    private func handleAuthState(_ state: AuthUiState) {
        guard AuthSessionBootstrapPolicy.canExposeAuthenticatedSession(
            freshInstallCleanupCompleted: freshInstallSessionCleanupCompleted
        ) else {
            completedRegistrationSession = nil
            guestAccessGranted = false
            isRegistrationPresented = false
            return
        }
        if state.hasPasswordRecoverySession {
            completedRegistrationSession = nil
            guestAccessGranted = false
            isRegistrationPresented = false
            passwordRecoveryController.resumeVerifiedSession(email: state.currentSession?.email)
            presentPasswordRecoveryIfPossible()
            return
        }
        if interruptedAuthJourneyStore.current == .registration {
            handleInterruptedRegistrationAuthState(state)
            return
        }
        applyStandardAuthState(state)
    }

    private func handleInterruptedRegistrationAuthState(_ state: AuthUiState) {
        completedRegistrationSession = nil
        guestAccessGranted = false
        isRegistrationPresented = false
        if isReplacingInterruptedRegistrationSession {
            isAuthenticationPresented = true
            return
        }
        if state.isAuthenticated {
            interruptedRegistrationEmail = normalizedEmail(state.currentSession?.email) ?? interruptedRegistrationEmail
            scheduleInterruptedRegistrationRevocation()
            return
        }
        if state.hasSession, let session = state.currentSession {
            interruptedAuthJourneyStore.clear(.registration)
            interruptedRegistrationEmail = nil
            resumeIncompleteRegistration(session)
            return
        }
        presentInterruptedRegistrationSignInIfPossible()
    }

    private func applyStandardAuthState(_ state: AuthUiState) {
        if state.isAuthenticated {
            completedRegistrationSession = state.currentSession
            isAuthenticationPresented = false
            isRegistrationPresented = false
        } else if state.hasSession, let session = state.currentSession {
            completedRegistrationSession = nil
            guestAccessGranted = false
            resumeIncompleteRegistration(session)
        } else {
            completedRegistrationSession = nil
        }
    }

    private func resumeIncompleteRegistration(_ session: AuthSession) {
        guestAccessGranted = false
        isAuthenticationPresented = false
        registrationController.resumeIncompleteSession(session: session)
        if launchIntroDecisionCompleted, !shouldPresentLaunchIntro {
            isRegistrationPresented = true
        }
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
        if sessionRestoreCompleted,
           hasCompleteAccount,
           !registrationNotificationPrimingStore.isResolved {
            route = .notificationPriming
            return
        }

        let routeKey = bridge.onboardingEntryKey(
            firstLaunchCompleted: firstLaunchCompleted,
            sessionRestoreCompleted: sessionRestoreCompleted,
            isAuthenticated: hasCompleteAccount,
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

    private func completeNotificationsAfterSessionRestore() {
        registrationNotificationPrimingStore.markResolved()
        isRequestingNotificationsAfterSessionRestore = false
        resolveRoute()
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
        presentInterruptedRegistrationSignInIfPossible()
        presentPasswordRecoveryIfPossible()
        presentIncompleteRegistrationIfPossible()
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

    private func presentIncompleteRegistrationIfPossible() {
        guard launchIntroDecisionCompleted,
              !shouldPresentLaunchIntro,
              authState?.isLoading == false,
              authState?.isAuthenticated == false,
              authState?.hasPasswordRecoverySession == false,
              let session = authState?.currentSession else {
            return
        }
        guestAccessGranted = false
        isAuthenticationPresented = false
        registrationController.resumeIncompleteSession(session: session)
        isRegistrationPresented = true
    }

    private func scheduleInterruptedRegistrationRevocation() {
        guard interruptedAuthJourneyStore.current == .registration,
              !isRevokingInterruptedRegistrationSession,
              !isReplacingInterruptedRegistrationSession else {
            return
        }
        isRevokingInterruptedRegistrationSession = true
        completedRegistrationSession = nil
        guestAccessGranted = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        Task { [weak self] in
            self?.revokeInterruptedRegistrationSession()
        }
    }

    private func clearFreshInstallSessionBeforeRestore() {
        authController.signOut { [weak self] completed in
            guard let self else { return }
            guard completed.boolValue else {
                completedRegistrationSession = nil
                guestAccessGranted = false
                isAuthenticationPresented = false
                isRegistrationPresented = false
                sessionRestoreCompleted = true
                resolveRoute()
                return
            }
            freshInstallSessionCleanupCompleted = true
            markFirstLaunchCompletedIfEligible()
            restoreSessionAfterBootstrap()
        }
    }

    private func restoreSessionAfterBootstrap() {
        authController.restoreSession { [weak self] _ in
            guard let self else { return }
            sessionRestoreCompleted = true
            resolveRoute()
            presentInterruptedRegistrationSignInIfPossible()
        }
    }

    private func markFirstLaunchCompletedIfEligible() {
        guard launchIntroCompleted,
              freshInstallSessionCleanupCompleted,
              !firstLaunchCompleted else {
            return
        }
        firstLaunchCompleted = true
        introStore.firstLaunchCompleted = true
    }

    private func revokeInterruptedRegistrationSession() {
        authController.signOut { [weak self] _ in
            guard let self else { return }
            isRevokingInterruptedRegistrationSession = false
            completedRegistrationSession = nil
            guestAccessGranted = false
            isRegistrationPresented = false
            resolveRoute()
            presentInterruptedRegistrationSignInIfPossible()
        }
    }

    private func finishInterruptedRegistrationPasswordSignIn(completed: Bool) {
        isReplacingInterruptedRegistrationSession = false
        completedRegistrationSession = nil
        guestAccessGranted = false
        isRegistrationPresented = false
        guard completed else {
            isAuthenticationPresented = true
            resolveRoute()
            return
        }
        interruptedAuthJourneyStore.clear(.registration)
        interruptedRegistrationEmail = nil
        if let authState {
            applyStandardAuthState(authState)
        }
        resolveRoute()
    }

    private func presentInterruptedRegistrationSignInIfPossible() {
        guard interruptedAuthJourneyStore.current == .registration,
              sessionRestoreCompleted,
              launchIntroDecisionCompleted,
              !shouldPresentLaunchIntro,
              authState?.hasPasswordRecoverySession != true,
              !isRevokingInterruptedRegistrationSession else {
            return
        }
        completedRegistrationSession = nil
        guestAccessGranted = false
        isRegistrationPresented = false
        isAuthenticationPresented = true
    }

    private func presentPasswordRecoveryIfPossible() {
        guard launchIntroDecisionCompleted,
              !shouldPresentLaunchIntro,
              authState?.isLoading == false,
              authState?.hasPasswordRecoverySession == true else {
            return
        }
        guestAccessGranted = false
        isRegistrationPresented = false
        isAuthenticationPresented = true
    }

    private func normalizedEmail(_ email: String?) -> String? {
        let candidate = email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return candidate.isEmpty ? nil : candidate
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
