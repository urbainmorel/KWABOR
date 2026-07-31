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
    @Published private(set) var promoterActivationContext: PromoterActivationContext?
    @Published private(set) var promoterActivationErrorMessage: String?
    @Published private(set) var pendingRootDeepLinkDestinationKey: String?
    @Published private(set) var interruptedRegistrationEmail: String?
    @Published var isAuthenticationPresented = false
    @Published var isRegistrationPresented = false
    @Published var isPromoterActivationPresented = false
    @Published var isGuestDisclosurePresented = false
    @Published private(set) var isCancellingRegistration = false
    @Published private(set) var isSigningOutAccount = false
    @Published private(set) var sessionRestoreFailed = false
    @Published private(set) var isDeletingAccount = false
    @Published private(set) var isRequestingNotificationsAfterSessionRestore = false

    let bridge: KwaborSharedBridge
    let strings: OnboardingStrings
    let authController: IosAuthController
    let passwordRecoveryController: IosPasswordRecoveryController
    let registrationController: IosRegistrationController
    let federatedIdentityHintStore: FederatedIdentityHintPersisting
    let promoterActivationDestinationStore: PromoterActivationDestinationPersisting
    let registrationLocationProvider: RegistrationLocationProviding
    let registrationNotificationPermissionRequester: RegistrationNotificationPermissionRequesting
    let registrationNotificationPrimingStore: RegistrationNotificationPrimingPersisting
    let interruptedAuthJourneyStore: InterruptedAuthJourneyPersisting

    var isGuestSession: Bool {
        guestAccessGranted && !hasCompleteAccount
    }

    private var canExposeSessionDuringPromoterActivation: Bool {
        PromoterActivationSessionPolicy.canExposeSession(
            cleanupRequired: temporaryPromoterActivationSessionCleanupRequired,
            activationCallbackInProgress: isHandlingPromoterActivationCallback
        )
    }

    var requiresInterruptedRegistrationPasswordSignIn: Bool {
        interruptedAuthJourneyStore.current == .registration
    }

    var requiresProtectedAuthentication: Bool {
        !sessionRestoreCompleted ||
        sessionRestoreFailed ||
        isDeletingAccount ||
        !canExposeSessionDuringPromoterActivation ||
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
    private var remoteMediaTask: Task<Void, Never>?
    private var remoteMediaPurgeTask: Task<Void, Never>?
    private var remoteMediaRevisionInFlight: Int64?
    private var queuedRemoteMedia: [FirebaseRemoteIntroVideo] = []
    private var remoteMediaPurgeRequired = false
    private var remoteMediaPurgeGeneration: Int64?
    private var remoteMediaDisablePending = false
    private var launchPendingRemoteMediaInvalidated = false
    private var completedRegistrationSession: AuthSession?
    private var shouldPresentRegistrationAfterAuthenticationDismissal = false
    private var isRevokingInterruptedRegistrationSession = false
    private var isReplacingInterruptedRegistrationSession = false
    private var isHandlingPromoterActivationCallback = false
    private var promoterActivationSessionImported = false
    private var promoterActivationCallbackMarkerArmed = false
    private var promoterActivationCallbackGeneration = 0
    private var promoterActivationCallbackQueue = PromoterActivationCallbackQueue()
    private var temporaryPromoterActivationSessionCleanupRequired = false
    private var isClearingTemporaryPromoterActivationSessionAtBootstrap = false
    private var freshInstallSessionCleanupCompleted: Bool
    private let promoterActivationSessionMarkerStore: PromoterActivationSessionMarkerPersisting

    init(
        bridge: KwaborSharedBridge,
        authController: IosAuthController,
        passwordRecoveryController: IosPasswordRecoveryController,
        registrationController: IosRegistrationController,
        observability: FirebaseObservability,
        federatedIdentityHintStore: FederatedIdentityHintPersisting = KeychainFederatedIdentityHintStore(),
        promoterActivationDestinationStore: PromoterActivationDestinationPersisting =
            KeychainPromoterActivationDestinationStore(),
        registrationLocationProvider: RegistrationLocationProviding? = nil,
        registrationNotificationPermissionRequester: RegistrationNotificationPermissionRequesting? = nil,
        registrationNotificationPrimingStore: RegistrationNotificationPrimingPersisting? = nil,
        cache: IntroVideoCache = IntroVideoCache(),
        userDefaults: UserDefaults = .standard,
        interruptedAuthJourneyStore: InterruptedAuthJourneyPersisting? = nil,
        promoterActivationSessionMarkerStore: PromoterActivationSessionMarkerPersisting =
            FilePromoterActivationSessionMarkerStore(),
        bundle: Bundle = .main
    ) {
        self.bridge = bridge
        strings = bridge.onboardingStrings()
        self.authController = authController
        self.passwordRecoveryController = passwordRecoveryController
        self.registrationController = registrationController
        self.federatedIdentityHintStore = federatedIdentityHintStore
        self.promoterActivationDestinationStore = promoterActivationDestinationStore
        self.observability = observability
        self.registrationLocationProvider = registrationLocationProvider ?? CoreLocationRegistrationService()
        self.registrationNotificationPermissionRequester = registrationNotificationPermissionRequester ??
            UserNotificationRegistrationService()
        self.registrationNotificationPrimingStore = registrationNotificationPrimingStore ??
            UserDefaultsRegistrationNotificationPrimingStore(userDefaults: userDefaults)
        self.interruptedAuthJourneyStore = interruptedAuthJourneyStore ??
            UserDefaultsInterruptedAuthJourneyStore(userDefaults: userDefaults)
        self.promoterActivationSessionMarkerStore = promoterActivationSessionMarkerStore
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
        switch PromoterActivationSessionPolicy.bootstrapAction(
            markerState: promoterActivationSessionMarkerStore.state
        ) {
        case .proceed:
            temporaryPromoterActivationSessionCleanupRequired = false
        case .clearTemporarySession:
            temporaryPromoterActivationSessionCleanupRequired = true
        }

        let hadPendingVideo = introStore.hasPendingVideo
        let storedPendingVideo = introStore.pendingVideoNewerThanLastPresented()
        let remotePolicyRequiresPurge = !observability.consent.remoteConfigurationAllowed ||
            observability.remoteConfiguration.introVideoStatus == .disabled
        var pendingPurgeGeneration = introStore.pendingRemoteMediaPurgeGeneration
        var purgeRequiredAtLaunch = pendingPurgeGeneration != nil
        if remotePolicyRequiresPurge {
            purgeRequiredAtLaunch = true
            pendingPurgeGeneration = introStore.requireRemoteMediaPurge() ?? pendingPurgeGeneration
        }
        let pendingAtLaunch: PendingIntroVideo?
        if observability.consent.remoteConfigurationAllowed,
           storedFirstLaunchCompleted,
           let storedPendingVideo,
           !purgeRequiredAtLaunch,
           observability.remoteConfiguration.introVideoStatus.preservesValidatedPendingVideo {
            pendingAtLaunch = storedPendingVideo
        } else {
            pendingAtLaunch = nil
        }
        let purgeRejectedPendingBeforeObservation = hadPendingVideo && pendingAtLaunch == nil
        if purgeRejectedPendingBeforeObservation {
            purgeRequiredAtLaunch = true
            pendingPurgeGeneration = introStore.requireRemoteMediaPurge() ?? pendingPurgeGeneration
        }
        remoteMediaPurgeRequired = purgeRequiredAtLaunch
        remoteMediaPurgeGeneration = pendingPurgeGeneration

        if temporaryPromoterActivationSessionCleanupRequired {
            route = .restoringSession
            introVideoURL = nil
            launchIntroDecisionCompleted = pendingAtLaunch == nil
        } else if storedFirstLaunchCompleted {
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
            processPendingPromoterActivationCallbackIfPossible()
        }
        startSessionBootstrap()

        if let pendingAtLaunch {
            Task { [weak self, cache] in
                let resolution = await cache.resolveCached(
                    revision: pendingAtLaunch.revision,
                    sha256: pendingAtLaunch.sha256
                )
                guard let self else { return }
                finishLaunchIntroDecision(pending: pendingAtLaunch, resolution: resolution)
            }
        }

        startObservingRemoteConfiguration()
        if remoteMediaPurgeRequired {
            scheduleRemoteMediaPurgeIfNeeded()
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
        if let launchIntroRevision {
            introStore.markRemoteVideoPresented(revision: launchIntroRevision)
        }
        introVideoURL = nil
    }

    func presentAuthentication() {
        guard !requiresProtectedAuthentication else { return }
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
        guard canExposeSessionDuringPromoterActivation else {
            shouldPresentRegistrationAfterAuthenticationDismissal = false
            isAuthenticationPresented = false
            return
        }
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
        federatedIdentityHintStore.clearPendingHints()
        interruptedAuthJourneyStore.clearRegistration()
        registrationCancellationErrorMessage = nil
        completedRegistrationSession = session
        guestAccessGranted = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        registrationController.reset()
        resolveRoute()
        refreshSessionState()
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

    func signInWithFederatedCredential(
        _ credential: FederatedAuthCredential,
        onCompleted: @escaping (Bool) -> Void
    ) {
        let replacesInterruptedRegistration = interruptedAuthJourneyStore.current == .registration
        if replacesInterruptedRegistration {
            isReplacingInterruptedRegistrationSession = true
            completedRegistrationSession = nil
            guestAccessGranted = false
            isRegistrationPresented = false
        } else {
            interruptedAuthJourneyStore.mark(.socialAuthentication)
        }
        authController.signInWithSocialIdToken(
            request: credential.sharedRequest
        ) { [weak self] completed in
            guard let self else { return }
            let didComplete = completed.boolValue
            if replacesInterruptedRegistration {
                finishInterruptedRegistrationPasswordSignIn(completed: didComplete)
            } else if !didComplete {
                interruptedAuthJourneyStore.clear(.socialAuthentication)
            }
            onCompleted(didComplete)
        }
    }

    func refreshSessionState() {
        restoreSessionAfterBootstrap()
    }

    @discardableResult
    func handleIncomingUrl(_ url: URL) -> Bool {
        let isAuthenticationUrl = PromoterActivationLinkRoutingPolicy.targetsActivationHost(
            scheme: url.scheme,
            host: url.host
        )
        if isAuthenticationUrl {
            guard !sessionRestoreFailed, !isDeletingAccount else { return true }
            let hasExplicitFragment = url.fragment != nil || url.absoluteString.contains("#")
            let linkAccepted = PromoterActivationLinkRoutingPolicy.acceptsCallback(
                scheme: url.scheme,
                host: url.host,
                path: url.path,
                hasExplicitFragment: hasExplicitFragment
            )
            switch PromoterActivationSessionPolicy.callbackPreparationAction(
                linkAccepted: linkAccepted
            ) {
            case .rejectBeforeShared:
                promoterActivationErrorMessage = strings.authPromoterInviteInvalid
                return true
            case .queueForBootstrap:
                _ = promoterActivationCallbackQueue.enqueue(url)
                processPendingPromoterActivationCallbackIfPossible()
                return true
            }
        }
        guard !requiresProtectedAuthentication else {
            return true
        }
        guard let routeKey = bridge.rootDestinationKeyForDeepLink(rawUrl: url.absoluteString) else {
            return false
        }
        pendingRootDeepLinkDestinationKey = routeKey
        return true
    }

    func consumeRootDeepLinkDestination() {
        pendingRootDeepLinkDestinationKey = nil
    }

    func completePromoterActivation(_ result: PromoterActivationResult) {
        guard let promoterActivationContext else {
            promoterActivationErrorMessage = strings.authPromoterInviteInvalid
            return
        }
        let destination = PromoterActivationDestination(
            organizationId: result.organizationId,
            listingId: result.listingId,
            businessName: promoterActivationContext.businessName
        )
        guard promoterActivationDestinationStore.save(destination) else {
            promoterActivationErrorMessage = strings.authUnavailable
            return
        }
        if promoterActivationSessionImported {
            guard promoterActivationSessionMarkerStore.clear() else {
                promoterActivationErrorMessage = strings.authUnavailable
                return
            }
            temporaryPromoterActivationSessionCleanupRequired = false
        }
        federatedIdentityHintStore.clearPendingHints()
        completedRegistrationSession = result.session
        guestAccessGranted = false
        self.promoterActivationContext = nil
        promoterActivationSessionImported = false
        promoterActivationErrorMessage = nil
        isPromoterActivationPresented = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        pendingRootDeepLinkDestinationKey = nil
        resolveRoute()
        processPendingPromoterActivationCallbackIfPossible()
    }

    func cancelPromoterActivation() {
        guard !isHandlingPromoterActivationCallback else { return }
        guard promoterActivationSessionImported else {
            closePromoterActivation()
            return
        }
        isHandlingPromoterActivationCallback = true
        authController.signOut { [weak self] completed in
            guard let self else { return }
            isHandlingPromoterActivationCallback = false
            guard completed.boolValue else {
                promoterActivationErrorMessage = authState?.errorMessage ?? strings.authUnavailable
                return
            }
            GoogleSignInBootstrap.clearLocalSession()
            completedRegistrationSession = nil
            guard promoterActivationSessionMarkerStore.clear() else {
                promoterActivationErrorMessage = strings.authUnavailable
                resolveRoute()
                return
            }
            temporaryPromoterActivationSessionCleanupRequired = false
            closePromoterActivation()
        }
    }

    func dismissPromoterActivationError() {
        promoterActivationErrorMessage = nil
        if temporaryPromoterActivationSessionCleanupRequired {
            clearTemporaryPromoterActivationSessionBeforeBootstrap()
            return
        }
        processPendingPromoterActivationCallbackIfPossible()
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
                federatedIdentityHintStore.clearPendingHints()
                promoterActivationDestinationStore.clear()
                GoogleSignInBootstrap.clearLocalSession()
                completedRegistrationSession = nil
                isAuthenticationPresented = false
                isRegistrationPresented = false
                pendingRootDeepLinkDestinationKey = nil
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

    func accountDeletionStateChanged(isInProgress: Bool) {
        guard isDeletingAccount != isInProgress else { return }
        isDeletingAccount = isInProgress
        if isInProgress {
            invalidatePromoterActivationCallbacksForAccountDeletion()
        } else if temporaryPromoterActivationSessionCleanupRequired {
            clearTemporaryPromoterActivationSessionBeforeBootstrap()
            return
        }
        resolveRoute()
    }

    func accountDeletionCompleted() {
        promoterActivationCallbackGeneration += 1
        promoterActivationCallbackQueue.clear()
        isHandlingPromoterActivationCallback = false
        promoterActivationContext = nil
        promoterActivationSessionImported = false
        promoterActivationCallbackMarkerArmed = false
        isPromoterActivationPresented = false
        let markerCleared = promoterActivationSessionMarkerStore.clear()
        temporaryPromoterActivationSessionCleanupRequired = !markerCleared
        if !markerCleared {
            sessionRestoreCompleted = false
            sessionRestoreFailed = true
        }
        isDeletingAccount = false
        federatedIdentityHintStore.clearPendingHints()
        promoterActivationDestinationStore.clear()
        GoogleSignInBootstrap.clearLocalSession()
        completedRegistrationSession = nil
        guestAccessGranted = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        pendingRootDeepLinkDestinationKey = nil
        resolveRoute()
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
                federatedIdentityHintStore.clearPendingHints()
                GoogleSignInBootstrap.clearLocalSession()
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
        guard canExposeSessionDuringPromoterActivation else {
            isGuestDisclosurePresented = false
            guestAccessGranted = false
            resolveRoute()
            return
        }
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
        guard canExposeSessionDuringPromoterActivation else {
            isGuestDisclosurePresented = false
            guestAccessGranted = false
            resolveRoute()
            return
        }
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
        guard canExposeSessionDuringPromoterActivation else {
            shouldPresentRegistrationAfterAuthenticationDismissal = false
            isAuthenticationPresented = false
            return
        }
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
        guard canExposeSessionDuringPromoterActivation else { return false }
        guard AuthSessionBootstrapPolicy.canExposeAuthenticatedSession(
            freshInstallCleanupCompleted: freshInstallSessionCleanupCompleted
        ) else { return false }
        guard interruptedAuthJourneyStore.current != .registration else { return false }
        return (authState?.isAuthenticated ?? false) || completedRegistrationSession != nil
    }

    private func handleAuthState(_ state: AuthUiState) {
        guard canExposeSessionDuringPromoterActivation else {
            completedRegistrationSession = nil
            guestAccessGranted = false
            isAuthenticationPresented = false
            isRegistrationPresented = false
            return
        }
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
            interruptedAuthJourneyStore.clearRegistration()
            interruptedRegistrationEmail = nil
            resumeIncompleteRegistration(session)
            return
        }
        presentInterruptedRegistrationSignInIfPossible()
    }

    private func applyStandardAuthState(_ state: AuthUiState) {
        if state.isAuthenticated {
            federatedIdentityHintStore.clearPendingHints()
            interruptedAuthJourneyStore.clearRegistration()
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
        resumeRegistrationController(session)
        if launchIntroDecisionCompleted, !shouldPresentLaunchIntro {
            isRegistrationPresented = true
        }
    }

    private func resolveRoute() {
        guard !isDeletingAccount else {
            return
        }
        guard canExposeSessionDuringPromoterActivation else {
            route = .restoringSession
            return
        }
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
        resolution: CachedIntroVideoResolution
    ) {
        guard !launchIntroDecisionCompleted else { return }
        let remoteMediaIsAllowed = observability.consent.remoteConfigurationAllowed &&
            !launchPendingRemoteMediaInvalidated &&
            !remoteMediaDisablePending &&
            !remoteMediaPurgeRequired &&
            observability.remoteConfiguration.introVideoStatus.preservesValidatedPendingVideo
        switch (remoteMediaIsAllowed, resolution) {
        case let (true, .available(resolvedURL)):
            introVideoURL = resolvedURL
            launchIntroRevision = pending.revision
        case (true, .transientFailure):
            observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
        case (true, .missingOrInvalid):
            let pendingWasCleared = introStore.clearPendingVideo(ifRevision: pending.revision)
            if pendingWasCleared,
               let source = observability.remoteConfiguration.introVideo,
               source.revision == pending.revision,
               source.sha256 == pending.sha256 {
                enqueueRemoteMediaIfNewer(source)
            }
        case (false, _):
            break
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
            launchPendingRemoteMediaInvalidated = true
            remoteMediaDisablePending = false
            purgePendingRemoteMedia()
            return
        }
        switch configuration.introVideoStatus {
        case .unavailable:
            return
        case .disabled:
            requireRemoteMediaPurge()
            queuedRemoteMedia.removeAll()
            launchPendingRemoteMediaInvalidated = true
            remoteMediaDisablePending = true
            processDeferredRemoteMediaIfPossible()
        case .invalid:
            observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
        case let .candidate(source):
            enqueueRemoteMediaIfNewer(source)
        }
    }

    private func startObservingRemoteConfiguration() {
        observability.observeRemoteConfiguration { [weak self] configuration, consent in
            self?.updateRemoteMedia(configuration: configuration, consent: consent)
        }
    }

    private func processDeferredRemoteMediaIfPossible() {
        guard launchIntroDecisionCompleted, !shouldPresentLaunchIntro else { return }
        if remoteMediaDisablePending {
            remoteMediaDisablePending = false
            purgePendingRemoteMedia(preservingQueuedMedia: true)
            startNextRemoteMediaResolutionIfNeeded()
            return
        }
        startNextRemoteMediaResolutionIfNeeded()
    }

    private func enqueueRemoteMediaIfNewer(_ source: FirebaseRemoteIntroVideo) {
        let latestScheduledRevision = max(
            remoteMediaRevisionInFlight ?? noRemoteRevision,
            queuedRemoteMedia.last?.revision ?? noRemoteRevision
        )
        let latestKnownRevision = max(introStore.latestKnownRemoteRevision, latestScheduledRevision)
        guard source.revision > latestKnownRevision else { return }

        queuedRemoteMedia.append(source)
        processDeferredRemoteMediaIfPossible()
    }

    private func startNextRemoteMediaResolutionIfNeeded() {
        if remoteMediaPurgeRequired {
            scheduleRemoteMediaPurgeIfNeeded()
            return
        }
        guard launchIntroDecisionCompleted,
              !shouldPresentLaunchIntro,
              !remoteMediaDisablePending,
              observability.consent.remoteConfigurationAllowed,
              remoteMediaTask == nil,
              !queuedRemoteMedia.isEmpty else {
            return
        }
        let source = queuedRemoteMedia.removeFirst()
        remoteMediaRevisionInFlight = source.revision
        let telemetry = self.telemetry
        remoteMediaTask = Task { [weak self, cache, observability] in
            guard !Task.isCancelled else { return }
            let resolvedURL = await cache.resolve(source: source)
            guard let self, !Task.isCancelled,
                  remoteMediaRevisionInFlight == source.revision else {
                return
            }
            remoteMediaTask = nil
            remoteMediaRevisionInFlight = nil
            defer { startNextRemoteMediaResolutionIfNeeded() }
            guard observability.consent.remoteConfigurationAllowed,
                  source.revision > introStore.latestKnownRemoteRevision else {
                return
            }
            guard resolvedURL != nil else {
                observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
                return
            }
            guard introStore.savePendingVideoIfNewer(source) else {
                observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
                return
            }
        }
    }

    private func purgePendingRemoteMedia(preservingQueuedMedia: Bool = false) {
        requireRemoteMediaPurge()
        remoteMediaTask?.cancel()
        remoteMediaTask = nil
        remoteMediaRevisionInFlight = nil
        if !preservingQueuedMedia {
            queuedRemoteMedia.removeAll()
        }
        scheduleRemoteMediaPurgeIfNeeded(restart: true)
    }

    private func requireRemoteMediaPurge() {
        remoteMediaPurgeRequired = true
        remoteMediaPurgeGeneration = introStore.requireRemoteMediaPurge() ?? remoteMediaPurgeGeneration
    }

    private func scheduleRemoteMediaPurgeIfNeeded(restart: Bool = false) {
        guard remoteMediaPurgeRequired else { return }
        if restart {
            remoteMediaPurgeTask?.cancel()
            remoteMediaPurgeTask = nil
        } else if remoteMediaPurgeTask != nil {
            return
        }
        if remoteMediaPurgeGeneration == nil {
            remoteMediaPurgeGeneration = introStore.requireRemoteMediaPurge()
        }
        guard let purgeGeneration = remoteMediaPurgeGeneration else {
            observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
            return
        }
        remoteMediaPurgeTask = Task { [weak self, cache] in
            guard let self else { return }
            let pendingMetadataWasCleared = introStore.clearPendingVideo()
            let cacheWasCleared = await clearIntroVideoCacheWithRetry(cache)
            guard !Task.isCancelled else { return }
            remoteMediaPurgeTask = nil
            guard pendingMetadataWasCleared,
                  cacheWasCleared,
                  introStore.acknowledgeRemoteMediaPurge(generation: purgeGeneration) else {
                observability.recordDiagnostic(telemetry.integrityDiagnosticCode)
                return
            }
            remoteMediaPurgeRequired = false
            remoteMediaPurgeGeneration = nil
            startNextRemoteMediaResolutionIfNeeded()
        }
    }

    private func presentIncompleteRegistrationIfPossible() {
        guard canExposeSessionDuringPromoterActivation,
              launchIntroDecisionCompleted,
              !shouldPresentLaunchIntro,
              authState?.isLoading == false,
              authState?.isAuthenticated == false,
              authState?.hasPasswordRecoverySession == false,
              let session = authState?.currentSession else {
            return
        }
        guestAccessGranted = false
        isAuthenticationPresented = false
        resumeRegistrationController(session)
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

    private func startSessionBootstrap() {
        if temporaryPromoterActivationSessionCleanupRequired {
            clearTemporaryPromoterActivationSessionBeforeBootstrap()
            return
        }
        switch PromoterActivationSessionPolicy.bootstrapAction(
            markerState: promoterActivationSessionMarkerStore.state
        ) {
        case .proceed:
            startStandardSessionBootstrap()
        case .clearTemporarySession:
            temporaryPromoterActivationSessionCleanupRequired = true
            resolveRoute()
            clearTemporaryPromoterActivationSessionBeforeBootstrap()
        }
    }

    private func startStandardSessionBootstrap() {
        switch AuthSessionBootstrapPolicy.action(hasInstallationMarker: firstLaunchCompleted) {
        case .clearLocalSession:
            clearFreshInstallSessionBeforeRestore()
        case .restoreSession:
            restoreSessionAfterBootstrap()
        }
    }

    private func clearTemporaryPromoterActivationSessionBeforeBootstrap() {
        guard !isClearingTemporaryPromoterActivationSessionAtBootstrap else { return }
        isClearingTemporaryPromoterActivationSessionAtBootstrap = true
        authController.signOut { [weak self] completed in
            guard let self else { return }
            isClearingTemporaryPromoterActivationSessionAtBootstrap = false
            guard completed.boolValue else {
                promoterActivationErrorMessage = authState?.errorMessage ?? strings.authUnavailable
                resolveRoute()
                return
            }
            GoogleSignInBootstrap.clearLocalSession()
            completedRegistrationSession = nil
            guestAccessGranted = false
            isAuthenticationPresented = false
            isRegistrationPresented = false
            guard promoterActivationSessionMarkerStore.clear() else {
                promoterActivationErrorMessage = strings.authUnavailable
                resolveRoute()
                return
            }
            temporaryPromoterActivationSessionCleanupRequired = false
            promoterActivationSessionImported = false
            promoterActivationErrorMessage = nil
            startStandardSessionBootstrap()
        }
    }

    private func processPendingPromoterActivationCallbackIfPossible() {
        let bootstrapCompleted = sessionRestoreCompleted && freshInstallSessionCleanupCompleted
        guard !sessionRestoreFailed, !isDeletingAccount else { return }
        guard let callbackURL = promoterActivationCallbackQueue.beginNextIfReady(
            sessionBootstrapCompleted: bootstrapCompleted,
            authOperationLoading: authState?.isLoading != false,
            callbackInProgress: isHandlingPromoterActivationCallback,
            cleanupRequired: temporaryPromoterActivationSessionCleanupRequired,
            activationPresented: isPromoterActivationPresented
        ) else {
            return
        }
        beginPromoterActivationCallback(callbackURL)
    }

    private func beginPromoterActivationCallback(_ callbackURL: URL) {
        guard !isDeletingAccount else { return }
        let callbackGeneration = promoterActivationCallbackGeneration
        isHandlingPromoterActivationCallback = true
        promoterActivationSessionImported = false
        promoterActivationCallbackMarkerArmed = false
        promoterActivationContext = nil
        promoterActivationErrorMessage = nil
        isPromoterActivationPresented = false
        isGuestDisclosurePresented = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        resolveRoute()

        let markerAction = PromoterActivationSessionPolicy.callbackMarkerAction(
            hasExistingSession: authState?.currentSession != nil,
            hasPkceCode: PromoterActivationLinkRoutingPolicy.hasPkceCode(callbackURL)
        )
        switch markerAction {
        case .callSharedWithoutMarker:
            callSharedPromoterActivationCallback(
                callbackURL,
                callbackGeneration: callbackGeneration
            )
        case .persistBeforeShared:
            persistPromoterActivationMarkerThenCallShared(
                callbackURL,
                callbackGeneration: callbackGeneration
            )
        }
    }

    private func persistPromoterActivationMarkerThenCallShared(
        _ callbackURL: URL,
        callbackGeneration: Int
    ) {
        temporaryPromoterActivationSessionCleanupRequired = true
        let persistenceSucceeded = promoterActivationSessionMarkerStore.persist()
        promoterActivationCallbackMarkerArmed = persistenceSucceeded
        let rollbackSucceeded = persistenceSucceeded || promoterActivationSessionMarkerStore.clear()
        let markerPersistenceAction = PromoterActivationSessionPolicy.markerPersistenceAction(
            persistenceSucceeded: persistenceSucceeded,
            rollbackSucceeded: rollbackSucceeded
        )
        switch markerPersistenceAction {
        case .callShared:
            callSharedPromoterActivationCallback(
                callbackURL,
                callbackGeneration: callbackGeneration
            )
        case .exposeErrorWithoutCallingShared:
            temporaryPromoterActivationSessionCleanupRequired = false
            isHandlingPromoterActivationCallback = false
            _ = promoterActivationCallbackQueue.completeInFlight()
            promoterActivationErrorMessage = strings.authUnavailable
            resolveRoute()
        case .clearTemporarySessionBeforeExposingError:
            _ = promoterActivationCallbackQueue.completeInFlight()
            failClosedPromoterActivationCallback()
        }
    }

    private func callSharedPromoterActivationCallback(
        _ callbackURL: URL,
        callbackGeneration: Int
    ) {
        authController.handlePromoterActivationCallback(callbackUrl: callbackURL.absoluteString) { [weak self] context in
            guard let self else { return }
            guard callbackGeneration == promoterActivationCallbackGeneration,
                  !isDeletingAccount else {
                return
            }
            _ = promoterActivationCallbackQueue.completeInFlight()
            handlePromoterActivationCallbackResult(context)
        }
    }

    private func handlePromoterActivationCallbackResult(
        _ context: PromoterActivationContext?
    ) {
        let resolutionAction = PromoterActivationSessionPolicy.callbackResolutionAction(
            contextAvailable: context != nil,
            sessionImportedForActivation: context?.sessionImportedForActivation ?? false,
            markerArmed: promoterActivationCallbackMarkerArmed
        )
        switch resolutionAction {
        case .keepMarkerBeforeExposingReady:
            guard let context,
                  promoterActivationSessionMarkerStore.state == .marked else {
                failClosedPromoterActivationCallback()
                return
            }
            completedRegistrationSession = nil
            guestAccessGranted = false
            isHandlingPromoterActivationCallback = false
            promoterActivationSessionImported = true
            promoterActivationCallbackMarkerArmed = false
            promoterActivationContext = context
            isPromoterActivationPresented = true
            resolveRoute()
        case .clearMarkerBeforeExposingReady:
            guard let context else {
                failClosedPromoterActivationCallback()
                return
            }
            guard clearProvisionalPromoterActivationSessionMarker() else {
                failClosedPromoterActivationCallback()
                return
            }
            isHandlingPromoterActivationCallback = false
            promoterActivationSessionImported = false
            promoterActivationCallbackMarkerArmed = false
            promoterActivationContext = context
            isPromoterActivationPresented = true
            resolveRoute()
        case .exposeReadyWithoutMarker:
            guard let context else {
                failClosedPromoterActivationCallback()
                return
            }
            isHandlingPromoterActivationCallback = false
            promoterActivationSessionImported = false
            promoterActivationCallbackMarkerArmed = false
            promoterActivationContext = context
            isPromoterActivationPresented = true
            resolveRoute()
        case .exposeErrorWithoutMarker:
            isHandlingPromoterActivationCallback = false
            promoterActivationSessionImported = false
            promoterActivationCallbackMarkerArmed = false
            promoterActivationContext = nil
            isPromoterActivationPresented = false
            promoterActivationErrorMessage = authState?.errorMessage ?? strings.authPromoterInviteInvalid
            resolveRoute()
        case .clearTemporarySessionBeforeExposingError:
            failClosedPromoterActivationCallback()
        }
    }

    private func clearProvisionalPromoterActivationSessionMarker() -> Bool {
        guard promoterActivationSessionMarkerStore.clear() else { return false }
        temporaryPromoterActivationSessionCleanupRequired = false
        promoterActivationCallbackMarkerArmed = false
        return true
    }

    private func failClosedPromoterActivationCallback() {
        temporaryPromoterActivationSessionCleanupRequired = true
        if promoterActivationSessionMarkerStore.state != .marked {
            _ = promoterActivationSessionMarkerStore.persist()
        }
        promoterActivationContext = nil
        promoterActivationSessionImported = false
        promoterActivationCallbackMarkerArmed = false
        isPromoterActivationPresented = false
        completedRegistrationSession = nil
        guestAccessGranted = false
        shouldPresentRegistrationAfterAuthenticationDismissal = false
        isGuestDisclosurePresented = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        pendingRootDeepLinkDestinationKey = nil
        resolveRoute()
        authController.signOut { [weak self] completed in
            guard let self else { return }
            var markerCleared = false
            if completed.boolValue {
                GoogleSignInBootstrap.clearLocalSession()
                markerCleared = promoterActivationSessionMarkerStore.clear()
            }
            switch PromoterActivationSessionPolicy.failClosedCleanupAction(
                signOutSucceeded: completed.boolValue,
                markerCleared: markerCleared
            ) {
            case .cleanupCompleted:
                temporaryPromoterActivationSessionCleanupRequired = false
            case .keepCleanupRequired:
                temporaryPromoterActivationSessionCleanupRequired = true
            }
            isHandlingPromoterActivationCallback = false
            promoterActivationErrorMessage = authState?.errorMessage ?? strings.authUnavailable
            resolveRoute()
        }
    }

    private func clearFreshInstallSessionBeforeRestore() {
        sessionRestoreCompleted = false
        sessionRestoreFailed = false
        federatedIdentityHintStore.clearPendingHints()
        promoterActivationDestinationStore.clear()
        GoogleSignInBootstrap.clearLocalSession()
        authController.signOut { [weak self] completed in
            guard let self else { return }
            guard completed.boolValue else {
                completedRegistrationSession = nil
                guestAccessGranted = false
                isAuthenticationPresented = false
                isRegistrationPresented = false
                sessionRestoreCompleted = false
                sessionRestoreFailed = true
                resolveRoute()
                return
            }
            freshInstallSessionCleanupCompleted = true
            markFirstLaunchCompletedIfEligible()
            restoreSessionAfterBootstrap()
        }
    }

    private func restoreSessionAfterBootstrap() {
        sessionRestoreCompleted = false
        sessionRestoreFailed = false
        resolveRoute()
        authController.restoreSession { [weak self] result in
            guard let self else { return }
            guard result.isReady else {
                completedRegistrationSession = nil
                guestAccessGranted = false
                isAuthenticationPresented = false
                isRegistrationPresented = false
                sessionRestoreCompleted = false
                sessionRestoreFailed = true
                resolveRoute()
                return
            }
            sessionRestoreCompleted = true
            sessionRestoreFailed = false
            resolveRoute()
            presentInterruptedRegistrationSignInIfPossible()
            processPendingPromoterActivationCallbackIfPossible()
        }
    }

    func retrySessionRestore() {
        guard sessionRestoreFailed, !isDeletingAccount else { return }
        sessionRestoreFailed = false
        if temporaryPromoterActivationSessionCleanupRequired {
            clearTemporaryPromoterActivationSessionBeforeBootstrap()
        } else if !freshInstallSessionCleanupCompleted {
            clearFreshInstallSessionBeforeRestore()
        } else {
            restoreSessionAfterBootstrap()
        }
    }

    private func invalidatePromoterActivationCallbacksForAccountDeletion() {
        promoterActivationCallbackGeneration += 1
        promoterActivationCallbackQueue.clear()
        let callbackCouldHaveImportedSession =
            promoterActivationCallbackMarkerArmed ||
            promoterActivationSessionImported ||
            promoterActivationSessionMarkerStore.state != .absent
        if callbackCouldHaveImportedSession {
            temporaryPromoterActivationSessionCleanupRequired = true
            if promoterActivationSessionMarkerStore.state != .marked {
                _ = promoterActivationSessionMarkerStore.persist()
            }
        }
        isHandlingPromoterActivationCallback = false
        promoterActivationContext = nil
        promoterActivationSessionImported = false
        promoterActivationCallbackMarkerArmed = false
        promoterActivationErrorMessage = nil
        isPromoterActivationPresented = false
        isGuestDisclosurePresented = false
        isAuthenticationPresented = false
        isRegistrationPresented = false
        pendingRootDeepLinkDestinationKey = nil
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
        guard canExposeSessionDuringPromoterActivation,
              interruptedAuthJourneyStore.current == .registration,
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
        guard canExposeSessionDuringPromoterActivation,
              launchIntroDecisionCompleted,
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

    private func closePromoterActivation() {
        promoterActivationContext = nil
        promoterActivationSessionImported = false
        promoterActivationErrorMessage = nil
        isPromoterActivationPresented = false
        resolveRoute()
        processPendingPromoterActivationCallbackIfPossible()
    }

    private func resumeRegistrationController(_ session: AuthSession) {
        if interruptedAuthJourneyStore.current?.resumesAtIdentity == true {
            interruptedAuthJourneyStore.mark(.socialRegistrationIdentity)
            let hints = federatedIdentityHintStore.pendingHints()
            registrationController.resumeIncompleteSocialSession(
                session: session,
                suggestedFirstName: hints?.firstName,
                suggestedLastName: hints?.lastName
            )
        } else {
            registrationController.resumeIncompleteSession(session: session)
        }
    }
}

struct PendingIntroVideo: Codable {
    let revision: Int64
    let sha256: String
}

private struct RemoteMediaPurgeState: Codable, Equatable {
    let requiredGeneration: Int64
    let acknowledgedGeneration: Int64

    static let empty = RemoteMediaPurgeState(requiredGeneration: 0, acknowledgedGeneration: 0)
    static let failClosed = RemoteMediaPurgeState(requiredGeneration: 1, acknowledgedGeneration: 0)
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

    var pendingRemoteMediaPurgeGeneration: Int64? {
        let state = readRemoteMediaPurgeState()
        return state.requiredGeneration > state.acknowledgedGeneration ? state.requiredGeneration : nil
    }

    var hasPendingVideo: Bool {
        userDefaults.object(forKey: pendingRemoteVideoKey) != nil ||
            userDefaults.object(forKey: pendingRemoteRevisionKey) != nil ||
            userDefaults.object(forKey: pendingRemoteSHA256Key) != nil
    }

    func pendingVideoNewerThanLastPresented() -> PendingIntroVideo? {
        guard let pending = readPendingVideo(),
              pending.revision > lastPresentedRemoteRevision,
              pending.sha256.range(of: sha256Pattern, options: .regularExpression) != nil else {
            return nil
        }
        guard persistPendingVideo(pending) else {
            return nil
        }
        return pending
    }

    func savePendingVideoIfNewer(_ source: FirebaseRemoteIntroVideo) -> Bool {
        guard source.revision > latestKnownRemoteRevision else { return false }
        return persistPendingVideo(
            PendingIntroVideo(revision: source.revision, sha256: source.sha256)
        )
    }

    func markRemoteVideoPresented(revision: Int64) {
        let presentedRevision = max(lastPresentedRemoteRevision, revision)
        userDefaults.set(presentedRevision, forKey: lastPresentedRemoteRevisionKey)
        if let pendingRevision = readPendingVideo()?.revision,
           pendingRevision <= presentedRevision {
            clearPendingVideo()
        }
    }

    @discardableResult
    func clearPendingVideo(ifRevision revision: Int64? = nil) -> Bool {
        if let revision,
           readPendingVideo()?.revision != revision {
            return true
        }
        userDefaults.removeObject(forKey: pendingRemoteVideoKey)
        userDefaults.removeObject(forKey: pendingRemoteRevisionKey)
        userDefaults.removeObject(forKey: pendingRemoteSHA256Key)
        return !hasPendingVideo
    }

    func requireRemoteMediaPurge() -> Int64? {
        if let pendingRemoteMediaPurgeGeneration {
            return pendingRemoteMediaPurgeGeneration
        }
        let state = readRemoteMediaPurgeState()
        let currentGeneration = max(state.requiredGeneration, state.acknowledgedGeneration)
        guard currentGeneration < Int64.max else { return nil }
        let updatedState = RemoteMediaPurgeState(
            requiredGeneration: currentGeneration + 1,
            acknowledgedGeneration: state.acknowledgedGeneration
        )
        return persistRemoteMediaPurgeState(updatedState) ? updatedState.requiredGeneration : nil
    }

    func acknowledgeRemoteMediaPurge(generation: Int64) -> Bool {
        let state = readRemoteMediaPurgeState()
        guard generation > 0, generation <= state.requiredGeneration else { return false }
        return persistRemoteMediaPurgeState(
            RemoteMediaPurgeState(
                requiredGeneration: state.requiredGeneration,
                acknowledgedGeneration: max(state.acknowledgedGeneration, generation)
            )
        )
    }

    private func readPendingVideo() -> PendingIntroVideo? {
        if let payload = userDefaults.data(forKey: pendingRemoteVideoKey) {
            return try? JSONDecoder().decode(PendingIntroVideo.self, from: payload)
        }
        guard userDefaults.object(forKey: pendingRemoteRevisionKey) != nil,
              let sha256 = userDefaults.string(forKey: pendingRemoteSHA256Key) else {
            return nil
        }
        return PendingIntroVideo(
            revision: Int64(userDefaults.integer(forKey: pendingRemoteRevisionKey)),
            sha256: sha256
        )
    }

    private func persistPendingVideo(_ pending: PendingIntroVideo) -> Bool {
        guard let payload = try? JSONEncoder().encode(pending) else { return false }
        userDefaults.set(payload, forKey: pendingRemoteVideoKey)
        return userDefaults.data(forKey: pendingRemoteVideoKey) == payload
    }

    private func readRemoteMediaPurgeState() -> RemoteMediaPurgeState {
        guard let payload = userDefaults.data(forKey: remoteMediaPurgeStateKey) else {
            return .empty
        }
        guard let state = try? JSONDecoder().decode(RemoteMediaPurgeState.self, from: payload),
              state.requiredGeneration >= 0,
              state.acknowledgedGeneration >= 0,
              state.acknowledgedGeneration <= state.requiredGeneration else {
            return .failClosed
        }
        return state
    }

    private func persistRemoteMediaPurgeState(_ state: RemoteMediaPurgeState) -> Bool {
        guard let payload = try? JSONEncoder().encode(state) else { return false }
        userDefaults.set(payload, forKey: remoteMediaPurgeStateKey)
        return userDefaults.data(forKey: remoteMediaPurgeStateKey) == payload
    }
}

private func clearIntroVideoCacheWithRetry(_ cache: IntroVideoCache) async -> Bool {
    for delay in remoteMediaPurgeRetryDelaysNanoseconds {
        if delay > 0 {
            do {
                try await Task.sleep(nanoseconds: delay)
            } catch {
                return false
            }
        }
        guard !Task.isCancelled else { return false }
        if await cache.clear() {
            return true
        }
    }
    return false
}

private let bundledIntroName = "KwaborIntro"
private let mp4Extension = "mp4"
private let introSeenKey = "kwabor.first_launch.intro_seen_v1"
private let pendingRemoteVideoKey = "kwabor.intro.pending_remote_video_v2"
private let pendingRemoteRevisionKey = "kwabor.intro.pending_remote_revision"
private let pendingRemoteSHA256Key = "kwabor.intro.pending_remote_sha256"
private let lastPresentedRemoteRevisionKey = "kwabor.intro.last_presented_remote_revision"
private let remoteMediaPurgeStateKey = "kwabor.intro.remote_media_purge_state_v1"
private let noRemoteRevision: Int64 = 0
private let sha256Pattern = "^[a-f0-9]{64}$"
private let remoteMediaPurgeRetryDelaysNanoseconds: [UInt64] = [0, 200_000_000, 800_000_000]
