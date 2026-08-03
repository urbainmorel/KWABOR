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
    @Published private var pendingInternalDeepLink = PendingInternalDeepLink()
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

    var pendingRootDeepLinkDestinationKey: String? {
        pendingInternalDeepLink.rootDestinationKey
    }

    var catalogDetailDeepLinkDeliveryReadyForOpening: CatalogDetailDeepLinkDelivery? {
        let action = CatalogDetailDeepLinkPostBootstrapPolicy.action(
            hasPendingListing: pendingInternalDeepLink.catalogDetailDelivery != nil,
            isIntroComplete: !shouldPresentLaunchIntro,
            isSessionRestoreComplete: sessionRestoreCompleted,
            isBlockingFlowActive: isCatalogDetailDeepLinkOpeningBlocked,
            hasAuthenticatedAccount: hasCompleteAccount,
            hasExplicitGuestAccess: guestAccessGranted
        )
        guard action == .openWhenHome else { return nil }
        return pendingInternalDeepLink.catalogDetailDelivery
    }

    var exploreViewerID: String? {
        guard hasCompleteAccount else { return nil }
        return authState?.currentSession?.userId ?? completedRegistrationSession?.userId
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

    private var isCatalogDetailDeepLinkOpeningBlocked: Bool {
        route != .home ||
        requiresProtectedAuthentication ||
        isSigningOutAccount ||
        isAuthenticationPresented ||
        isRegistrationPresented ||
        isPromoterActivationPresented ||
        shouldPresentRegistrationAfterAuthenticationDismissal ||
        authState?.hasPasswordRecoverySession == true ||
        (authState?.hasSession == true && !hasCompleteAccount)
    }

    private let observability: FirebaseObservability
    private let telemetry: OnboardingTelemetry
    private let introStore: IntroVideoPresentationStore
    private var firstLaunchCompleted: Bool
    private var sessionRestoreCompleted = false
    private var guestAccessGranted = false
    private var introDisplayTracked = false
    private var launchIntroCompleted = false
    private let launchBundledIntroRevision: Int64?
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
        telemetry = bridge.onboardingTelemetry()

        let introStore = IntroVideoPresentationStore(userDefaults: userDefaults)
        self.introStore = introStore
        let storedFirstLaunchCompleted = introStore.firstLaunchCompleted
        firstLaunchCompleted = storedFirstLaunchCompleted
        freshInstallSessionCleanupCompleted = storedFirstLaunchCompleted
        let pendingBundledIntroRevision = introStore.pendingBundledRevision()
        launchBundledIntroRevision = pendingBundledIntroRevision
        introVideoURL = pendingBundledIntroRevision == nil ? nil : bundle.url(
            forResource: bundledIntroName,
            withExtension: mp4Extension
        )
        switch PromoterActivationSessionPolicy.bootstrapAction(
            markerState: promoterActivationSessionMarkerStore.state
        ) {
        case .proceed:
            temporaryPromoterActivationSessionCleanupRequired = false
        case .clearTemporarySession:
            temporaryPromoterActivationSessionCleanupRequired = true
        }

        if temporaryPromoterActivationSessionCleanupRequired {
            route = .restoringSession
        } else if pendingBundledIntroRevision == nil {
            route = .restoringSession
        } else {
            route = .intro
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
        if skipped {
            observability.track(telemetry.skippedEvent)
        }
        resolveRoute()
        presentInterruptedRegistrationSignInIfPossible()
        presentPasswordRecoveryIfPossible()
        presentIncompleteRegistrationIfPossible()
    }

    func introPlaybackFailed() {
        guard !launchIntroCompleted else { return }
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
        let catalogDetailListingID = bridge.catalogDetailListingIdForDeepLink(
            rawUrl: url.absoluteString
        )
        if let catalogDetailListingID {
            guard InternalDeepLinkIngressPolicy.shouldRetain(
                validatedDestinationExists: true,
                isSigningOut: isSigningOutAccount,
                isDeletingAccount: isDeletingAccount
            ) else { return true }
            pendingInternalDeepLink.enqueueCatalogDetail(
                validatedListingID: catalogDetailListingID
            )
            return true
        }
        let rootDestinationKey = bridge.rootDestinationKeyForDeepLink(
            rawUrl: url.absoluteString
        )
        if let rootDestinationKey {
            guard InternalDeepLinkIngressPolicy.shouldRetain(
                validatedDestinationExists: true,
                isSigningOut: isSigningOutAccount,
                isDeletingAccount: isDeletingAccount
            ) else { return true }
            pendingInternalDeepLink.enqueueRoot(destinationKey: rootDestinationKey)
            return true
        }
        return requiresProtectedAuthentication
    }

    func consumeRootDeepLinkDestination() {
        pendingInternalDeepLink.consumeRoot()
    }

    func isCurrentCatalogDetailDeepLink(delivery: CatalogDetailDeepLinkDelivery) -> Bool {
        catalogDetailDeepLinkDeliveryReadyForOpening != nil &&
        pendingInternalDeepLink.isCurrentCatalogDetail(delivery: delivery)
    }

    @discardableResult
    func acknowledgeCatalogDetailDeepLink(delivery: CatalogDetailDeepLinkDelivery) -> Bool {
        pendingInternalDeepLink.acknowledgeCatalogDetail(delivery: delivery)
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
        pendingInternalDeepLink.clear()
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
        pendingInternalDeepLink.clear()
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
                pendingInternalDeepLink.clear()
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
            pendingInternalDeepLink.clear()
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
        pendingInternalDeepLink.clear()
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
        !launchIntroCompleted && launchBundledIntroRevision != nil
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
        if !shouldPresentLaunchIntro {
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

    private func presentIncompleteRegistrationIfPossible() {
        guard canExposeSessionDuringPromoterActivation,
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
        pendingInternalDeepLink.clear()
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
        pendingInternalDeepLink.clear()
    }

    private func markFirstLaunchCompletedIfEligible() {
        guard launchIntroCompleted,
              freshInstallSessionCleanupCompleted else {
            return
        }
        if let launchBundledIntroRevision {
            introStore.markBundledVideoPresented(revision: launchBundledIntroRevision)
        }
        guard !firstLaunchCompleted else { return }
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

private let bundledIntroName = "KwaborIntro"
private let mp4Extension = "mp4"
