import Foundation

private func expect(
    _ condition: @autoclosure () -> Bool,
    _ message: String
) {
    guard condition() else {
        fatalError(message)
    }
}

private func callbackURL(tokenCharacter: String, includesPkceCode: Bool) -> URL {
    let token = String(repeating: tokenCharacter, count: 64)
    let suffix = includesPkceCode ? "&code=pkce-code-value" : ""
    guard let url = URL(string: "kwabor://auth/promoter-activate?token=\(token)\(suffix)") else {
        fatalError("The test callback URL must be valid.")
    }
    return url
}

private let callbackA = callbackURL(tokenCharacter: "a", includesPkceCode: true)
private let callbackB = callbackURL(tokenCharacter: "b", includesPkceCode: false)

private var queue = PromoterActivationCallbackQueue()
expect(
    PromoterActivationSessionPolicy.callbackPreparationAction(
        linkAccepted: true
    ) == .queueForBootstrap,
    "An accepted callback must enter the bootstrap queue."
)
expect(
    !PromoterActivationLinkRoutingPolicy.acceptsCallback(
        scheme: "kwabor",
        host: "auth",
        path: "/promoter-activate",
        hasExplicitFragment: true
    ),
    "Implicit-flow fragments must be rejected before entering the queue."
)
expect(queue.enqueue(callbackA), "The first callback must be queued.")
expect(!queue.enqueue(callbackA), "A queued callback must be de-duplicated.")
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: false,
        authOperationLoading: false,
        callbackInProgress: false,
        cleanupRequired: false,
        activationPresented: false
    ) == nil,
    "A callback must wait for session bootstrap."
)
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: true,
        authOperationLoading: true,
        callbackInProgress: false,
        cleanupRequired: false,
        activationPresented: false
    ) == nil,
    "A callback must wait for the current auth operation."
)
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: true,
        authOperationLoading: false,
        callbackInProgress: false,
        cleanupRequired: true,
        activationPresented: false
    ) == nil,
    "A callback must wait while fail-closed cleanup is required."
)
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: true,
        authOperationLoading: false,
        callbackInProgress: false,
        cleanupRequired: false,
        activationPresented: false
    ) == callbackA,
    "The callback must be dequeued once bootstrap is ready."
)
expect(!queue.enqueue(callbackA), "An in-flight callback must be de-duplicated.")
expect(queue.enqueue(callbackB), "A distinct callback must remain queued.")
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: true,
        authOperationLoading: false,
        callbackInProgress: false,
        cleanupRequired: false,
        activationPresented: false
    ) == nil,
    "An in-flight callback must not be started twice."
)
expect(queue.completeInFlight(), "The in-flight callback must complete exactly once.")
expect(!queue.completeInFlight(), "A completed callback must not complete twice.")
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: true,
        authOperationLoading: false,
        callbackInProgress: false,
        cleanupRequired: false,
        activationPresented: false
    ) == callbackB,
    "The next distinct callback must run after the first completion."
)
queue.clear()
expect(
    queue.beginNextIfReady(
        sessionBootstrapCompleted: true,
        authOperationLoading: false,
        callbackInProgress: false,
        cleanupRequired: false,
        activationPresented: false
    ) == nil,
    "Account deletion must be able to invalidate queued and in-flight callbacks."
)

expect(
    PromoterActivationSessionPolicy.callbackMarkerAction(
        hasExistingSession: true,
        hasPkceCode: true
    ) == .callSharedWithoutMarker,
    "A restored session must never arm temporary-session cleanup."
)
expect(
    PromoterActivationSessionPolicy.callbackMarkerAction(
        hasExistingSession: false,
        hasPkceCode: true
    ) == .persistBeforeShared,
    "A PKCE exchange without an existing session must arm cleanup before shared code."
)
expect(
    PromoterActivationSessionPolicy.callbackMarkerAction(
        hasExistingSession: false,
        hasPkceCode: false
    ) == .callSharedWithoutMarker,
    "A token-only callback cannot import a session and must not arm cleanup."
)
expect(
    PromoterActivationSessionPolicy.markerPersistenceAction(
        persistenceSucceeded: false,
        rollbackSucceeded: true
    ) == .exposeErrorWithoutCallingShared,
    "A successful marker rollback may expose an error without calling shared code."
)
expect(
    PromoterActivationSessionPolicy.markerPersistenceAction(
        persistenceSucceeded: false,
        rollbackSucceeded: false
    ) == .clearTemporarySessionBeforeExposingError,
    "An uncertain marker state must trigger immediate fail-closed cleanup."
)
expect(
    PromoterActivationSessionPolicy.callbackResolutionAction(
        contextAvailable: true,
        sessionImportedForActivation: false,
        markerArmed: false
    ) == .exposeReadyWithoutMarker,
    "An existing-session callback must not clear a provisional marker that was never armed."
)
expect(
    PromoterActivationSessionPolicy.callbackResolutionAction(
        contextAvailable: true,
        sessionImportedForActivation: true,
        markerArmed: false
    ) == .clearTemporarySessionBeforeExposingError,
    "An unexpected imported session without a marker must be cleared fail-closed."
)
expect(
    PromoterActivationSessionPolicy.callbackResolutionAction(
        contextAvailable: false,
        sessionImportedForActivation: true,
        markerArmed: true
    ) == .clearTemporarySessionBeforeExposingError,
    "A callback error after provisional import must sign out before exposing the error."
)
expect(
    !PromoterActivationSessionPolicy.canExposeSession(
        cleanupRequired: true,
        activationCallbackInProgress: false,
        activationPresented: false
    ),
    "A failed cleanup must keep every temporary session hidden between retries."
)
expect(
    !PromoterActivationSessionPolicy.canExposeSession(
        cleanupRequired: false,
        activationCallbackInProgress: true,
        activationPresented: false
    ),
    "A callback in progress must keep its provisional session hidden."
)
expect(
    !PromoterActivationSessionPolicy.canExposeSession(
        cleanupRequired: false,
        activationCallbackInProgress: false,
        activationPresented: true
    ),
    "A presented activation must keep the shared session hidden until local completion succeeds."
)
expect(
    PromoterActivationSessionPolicy.canExposeSession(
        cleanupRequired: false,
        activationCallbackInProgress: false,
        activationPresented: false
    ),
    "Session visibility may resume only when no callback or cleanup is active."
)
expect(
    PromoterActivationSessionPolicy.canCompleteActivation(
        resultUserID: "account-a",
        authenticatedUserID: "account-a",
        isAuthenticated: true,
        isAuthenticationLoading: false,
        cleanupInProgress: false,
        callbackInProgress: false
    ),
    "Activation completion requires the result to match the live authenticated account."
)
expect(
    !PromoterActivationSessionPolicy.canCompleteActivation(
        resultUserID: "account-a",
        authenticatedUserID: nil,
        isAuthenticated: false,
        isAuthenticationLoading: false,
        cleanupInProgress: false,
        callbackInProgress: false
    ),
    "A retained activation result must not recreate a session after sign-out."
)
expect(
    !PromoterActivationSessionPolicy.canCompleteActivation(
        resultUserID: "account-a",
        authenticatedUserID: "account-b",
        isAuthenticated: true,
        isAuthenticationLoading: false,
        cleanupInProgress: false,
        callbackInProgress: false
    ),
    "An activation result must never bind a different authenticated account."
)
expect(
    !PromoterActivationSessionPolicy.canCompleteActivation(
        resultUserID: "account-a",
        authenticatedUserID: "account-a",
        isAuthenticated: true,
        isAuthenticationLoading: true,
        cleanupInProgress: false,
        callbackInProgress: false
    ),
    "A retained activation result must not complete while sign-out is loading."
)
expect(
    !PromoterActivationSessionPolicy.canCompleteActivation(
        resultUserID: "account-a",
        authenticatedUserID: "account-a",
        isAuthenticated: true,
        isAuthenticationLoading: false,
        cleanupInProgress: true,
        callbackInProgress: false
    ),
    "A retained activation result must not complete while cleanup is in progress."
)
expect(
    PromoterActivationSessionPolicy.failClosedCleanupAction(
        signOutSucceeded: false,
        markerCleared: false
    ) == .keepCleanupRequired,
    "A failed sign-out must keep fail-closed cleanup retryable."
)
expect(
    PromoterActivationSessionPolicy.failClosedCleanupAction(
        signOutSucceeded: true,
        markerCleared: false
    ) == .keepCleanupRequired,
    "A failed marker cleanup must keep fail-closed cleanup retryable."
)
expect(
    PromoterActivationSessionPolicy.failClosedCleanupAction(
        signOutSucceeded: true,
        markerCleared: true
    ) == .cleanupCompleted,
    "Cleanup may finish only after sign-out and marker removal both succeed."
)
expect(
    PromoterActivationSessionPolicy.bootstrapAction(
        markerState: .marked
    ) == .clearTemporarySession,
    "A persisted fail-closed marker must resume cleanup after an app restart."
)
expect(
    PromoterActivationLinkRoutingPolicy.hasPkceCode(callbackA),
    "The platform policy must recognize a PKCE callback."
)
expect(
    !PromoterActivationLinkRoutingPolicy.hasPkceCode(callbackB),
    "A token-only callback must not be classified as PKCE."
)

private func isolatedDefaults(label: String) -> (defaults: UserDefaults, suiteName: String) {
    let suiteName = "com.kwabor.policy-tests.\(label).\(UUID().uuidString)"
    guard let defaults = UserDefaults(suiteName: suiteName) else {
        fatalError("The isolated UserDefaults suite must be available.")
    }
    defaults.removePersistentDomain(forName: suiteName)
    return (defaults, suiteName)
}

private let legacyMigrationDefaults = isolatedDefaults(label: "legacy-intro")
legacyMigrationDefaults.defaults.set(true, forKey: "kwabor.first_launch.intro_seen_v1")
let migratedIntroStore = IntroVideoPresentationStore(userDefaults: legacyMigrationDefaults.defaults)
expect(
    migratedIntroStore.lastPresentedBundledRevision == 1,
    "A completed legacy first launch must migrate to the fixed bundled revision 1 baseline."
)
expect(
    migratedIntroStore.pendingBundledRevision() == nil,
    "The initial bundled revision must not replay after legacy first-launch migration."
)
expect(
    migratedIntroStore.pendingBundledRevision(currentRevision: 2) == 2,
    "A strictly newer bundled revision must be presented once after an app update."
)
migratedIntroStore.markBundledVideoPresented(revision: 2)
expect(
    migratedIntroStore.pendingBundledRevision(currentRevision: 2) == nil,
    "A bundled revision already presented must not replay."
)
expect(
    migratedIntroStore.pendingBundledRevision(currentRevision: 3) == 3,
    "Skipping directly to a later bundled revision must still present that newer revision."
)
legacyMigrationDefaults.defaults.removePersistentDomain(forName: legacyMigrationDefaults.suiteName)

private let freshInstallDefaults = isolatedDefaults(label: "fresh-intro")
let freshIntroStore = IntroVideoPresentationStore(userDefaults: freshInstallDefaults.defaults)
expect(
    freshIntroStore.pendingBundledRevision() == 1,
    "A fresh installation must present the initial bundled revision."
)
freshIntroStore.markBundledVideoPresented(revision: 1)
expect(
    freshIntroStore.pendingBundledRevision() == nil,
    "The initial bundled revision must be consumed exactly once."
)
freshInstallDefaults.defaults.removePersistentDomain(forName: freshInstallDefaults.suiteName)

private let cleanupDefaults = isolatedDefaults(label: "remote-intro-cleanup")
legacyRemoteIntroPreferenceKeys.forEach { key in
    cleanupDefaults.defaults.set("legacy", forKey: key)
}
cleanupDefaults.defaults.set(true, forKey: "kwabor.first_launch.intro_seen_v1")
cleanupDefaults.defaults.set(true, forKey: "kwabor.observability.remote_configuration_allowed")
let cleanupRoot = FileManager.default.temporaryDirectory.appending(
    path: "kwabor-policy-tests-\(UUID().uuidString)",
    directoryHint: .isDirectory
)
let legacyCacheDirectory = cleanupRoot.appending(
    path: legacyRemoteIntroCacheDirectoryName,
    directoryHint: .isDirectory
)
do {
    try FileManager.default.createDirectory(at: legacyCacheDirectory, withIntermediateDirectories: true)
    try Data([0x00]).write(to: legacyCacheDirectory.appending(path: "intro-legacy.mp4"))
} catch {
    fatalError("The legacy cache fixture must be writable: \(error)")
}
expect(
    cleanLegacyRemoteIntroStorage(
        fileManager: .default,
        userDefaults: cleanupDefaults.defaults,
        cacheDirectory: legacyCacheDirectory
    ),
    "Legacy remote intro storage cleanup must complete."
)
expect(
    legacyRemoteIntroPreferenceKeys.allSatisfy {
        cleanupDefaults.defaults.object(forKey: $0) == nil
    },
    "Every app-owned remote intro preference must be removed."
)
expect(
    !FileManager.default.fileExists(atPath: legacyCacheDirectory.path),
    "The app-owned remote intro cache directory must be removed."
)
expect(
    cleanupDefaults.defaults.bool(forKey: "kwabor.first_launch.intro_seen_v1"),
    "Legacy cleanup must preserve first-launch state."
)
expect(
    cleanupDefaults.defaults.bool(forKey: "kwabor.observability.remote_configuration_allowed"),
    "Legacy cleanup must preserve generic Remote Config consent."
)
expect(
    cleanupDefaults.defaults.bool(forKey: legacyRemoteIntroCleanupCompletedKey),
    "Legacy cleanup must persist its one-time completion marker."
)
expect(
    cleanLegacyRemoteIntroStorage(
        fileManager: .default,
        userDefaults: cleanupDefaults.defaults,
        cacheDirectory: nil
    ),
    "A completed legacy cleanup must remain idempotent."
)
cleanupDefaults.defaults.removePersistentDomain(forName: cleanupDefaults.suiteName)
try? FileManager.default.removeItem(at: cleanupRoot)

private let unavailableCacheDefaults = isolatedDefaults(label: "unavailable-cache-root")
expect(
    cleanLegacyRemoteIntroStorage(
        fileManager: .default,
        userDefaults: unavailableCacheDefaults.defaults,
        cacheDirectory: nil
    ),
    "An unavailable cache root must be treated as an already absent legacy cache."
)
expect(
    unavailableCacheDefaults.defaults.bool(forKey: legacyRemoteIntroCleanupCompletedKey),
    "Cleanup without a cache root must still persist its completion marker."
)
unavailableCacheDefaults.defaults.removePersistentDomain(forName: unavailableCacheDefaults.suiteName)

private var paginationGuard = ExplorePaginationGuard()
expect(
    !paginationGuard.shouldLoadNext(
        cursor: "cursor-1",
        canLoadMore: true,
        isNearEnd: false,
        hasAppendError: false
    ),
    "Pagination must not start before the threshold is reached."
)
expect(
    paginationGuard.shouldLoadNext(
        cursor: "cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "The first eligible cursor must start one append."
)
expect(
    !paginationGuard.shouldLoadNext(
        cursor: "cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "A visible cursor must never trigger duplicate appends."
)
expect(
    !paginationGuard.shouldLoadNext(
        cursor: "cursor-2",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: true
    ),
    "Automatic pagination must stop on an append error."
)
expect(
    paginationGuard.shouldRetry(cursor: "cursor-1", canLoadMore: true),
    "An explicit retry must be allowed for the current cursor."
)
paginationGuard.reset()
expect(
    paginationGuard.shouldLoadNext(
        cursor: "cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "A refresh or filter change must reset the cursor guard."
)

private var guidePaginationGuard = ExplorePaginationGuard()
expect(
    !guidePaginationGuard.shouldLoadNext(
        cursor: nil,
        canLoadMore: false,
        isNearEnd: true,
        hasAppendError: false
    ),
    "Guide discovery must not append when the shared state has no next page."
)
expect(
    !guidePaginationGuard.shouldLoadNext(
        cursor: "guide-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: true
    ),
    "Guide discovery must wait for an explicit retry after an append failure."
)
expect(
    guidePaginationGuard.shouldRetry(cursor: "guide-cursor-1", canLoadMore: true),
    "Guide discovery must allow one explicit retry for the failed cursor."
)
guidePaginationGuard.reset()
expect(
    guidePaginationGuard.shouldLoadNext(
        cursor: "guide-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "A guide filter change must allow the new query to reuse an opaque cursor value."
)

expect(
    ExploreRemoteImageURLPolicy.acceptedURL(
        "https://cdn.kwabor.example/media/card.jpg?width=720"
    ) != nil,
    "An HTTPS image URL with a CDN query must be accepted."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("http://cdn.kwabor.example/card.jpg") == nil,
    "An insecure image URL must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL(" https://cdn.kwabor.example/card.jpg") == nil,
    "An image URL containing whitespace must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://user@cdn.kwabor.example/card.jpg") == nil,
    "An image URL containing user information must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://cdn.kwabor.example/card.jpg#fragment") == nil,
    "An image URL fragment must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://cdn.kwabor.example:443/card.jpg") != nil,
    "The canonical HTTPS port must be accepted."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://cdn.kwabor.example:444/card.jpg") == nil,
    "A non-HTTPS port must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://localhost/card.jpg") == nil,
    "Localhost must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://assets.internal/card.jpg") == nil,
    "Internal DNS suffixes must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://127.0.0.1/card.jpg") == nil,
    "Private and loopback IPv4-shaped hosts must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://CDN.kwabor.example/card.jpg") == nil,
    "Non-canonical uppercase hosts must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL("https://-cdn.kwabor.example/card.jpg") == nil,
    "DNS labels with an invalid leading character must be rejected."
)
expect(
    ExploreRemoteImageURLPolicy.acceptedURL(
        "https://cdn.kwabor.example/" + String(repeating: "a", count: 2_100)
    ) == nil,
    "Oversized image URLs must be rejected before parsing."
)

private func catalogDetailPostBootstrapAction(
    pending: PendingInternalDeepLink,
    isIntroComplete: Bool = true,
    isSessionRestoreComplete: Bool = true,
    isBlockingFlowActive: Bool = false,
    hasAuthenticatedAccount: Bool = false,
    hasExplicitGuestAccess: Bool = false
) -> CatalogDetailDeepLinkPostBootstrapAction {
    CatalogDetailDeepLinkPostBootstrapPolicy.action(
        hasPendingListing: pending.catalogDetailListingID != nil,
        isIntroComplete: isIntroComplete,
        isSessionRestoreComplete: isSessionRestoreComplete,
        isBlockingFlowActive: isBlockingFlowActive,
        hasAuthenticatedAccount: hasAuthenticatedAccount,
        hasExplicitGuestAccess: hasExplicitGuestAccess
    )
}

private func requireCatalogDetailDelivery(
    _ pending: PendingInternalDeepLink,
    _ message: String
) -> CatalogDetailDeepLinkDelivery {
    guard let delivery = pending.catalogDetailDelivery else {
        fatalError(message)
    }
    return delivery
}

private let deepLinkListingID = "11111111-2222-4333-8444-555555555555"
private let replacementDeepLinkListingID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
private var pendingDeepLink = PendingInternalDeepLink()
expect(
    InternalDeepLinkIngressPolicy.shouldRetain(
        validatedDestinationExists: true,
        isSigningOut: false,
        isDeletingAccount: false
    ),
    "A validated navigation link must be retainable in a stable authentication state."
)
expect(
    !InternalDeepLinkIngressPolicy.shouldRetain(
        validatedDestinationExists: true,
        isSigningOut: true,
        isDeletingAccount: false
    ) &&
        !InternalDeepLinkIngressPolicy.shouldRetain(
            validatedDestinationExists: true,
            isSigningOut: false,
            isDeletingAccount: true
        ) &&
        !InternalDeepLinkIngressPolicy.shouldRetain(
            validatedDestinationExists: false,
            isSigningOut: false,
            isDeletingAccount: false
        ),
    "Sign-out, account deletion and invalid destinations must reject ingress."
)
expect(
    pendingDeepLink.rootDestinationKey == nil &&
        pendingDeepLink.catalogDetailListingID == nil,
    "Internal deep-link state must start empty."
)
expect(
    catalogDetailPostBootstrapAction(pending: pendingDeepLink) == .wait,
    "Post-bootstrap routing must wait when no listing deep link is pending."
)
pendingDeepLink.enqueueRoot(destinationKey: "profile")
expect(
    pendingDeepLink.rootDestinationKey == "profile" &&
        pendingDeepLink.catalogDetailListingID == nil,
    "A root deep link must be the only pending navigation target."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
expect(
    pendingDeepLink.rootDestinationKey == nil &&
        pendingDeepLink.catalogDetailListingID == deepLinkListingID,
    "A listing deep link must replace a pending root destination atomically."
)
private let initialDeepLinkDelivery = requireCatalogDetailDelivery(
    pendingDeepLink,
    "The first valid listing must create a delivery."
)
expect(
    catalogDetailPostBootstrapAction(
        pending: pendingDeepLink,
        isIntroComplete: false
    ) == .wait,
    "A listing deep link must remain pending while the launch intro is visible."
)
expect(
    catalogDetailPostBootstrapAction(
        pending: pendingDeepLink,
        isSessionRestoreComplete: false
    ) == .wait,
    "A listing deep link must remain pending until session bootstrap completes."
)
expect(
    catalogDetailPostBootstrapAction(pending: pendingDeepLink) == .wait,
    "E3 must remain visible until the user explicitly authenticates or confirms guest access."
)
private let pendingBeforeGuestDisclosureCancellation = pendingDeepLink
expect(
    catalogDetailPostBootstrapAction(pending: pendingDeepLink) == .wait &&
        pendingDeepLink == pendingBeforeGuestDisclosureCancellation,
    "Cancelling the explicit guest disclosure must preserve the pending listing without reopening it."
)
expect(
    catalogDetailPostBootstrapAction(
        pending: pendingDeepLink,
        hasAuthenticatedAccount: true
    ) == .openWhenHome,
    "An authenticated account must open the pending listing after bootstrap."
)
expect(
    catalogDetailPostBootstrapAction(
        pending: pendingDeepLink,
        hasExplicitGuestAccess: true
    ) == .openWhenHome,
    "Explicitly confirmed guest access must open the pending listing."
)
expect(
    catalogDetailPostBootstrapAction(
        pending: pendingDeepLink,
        isBlockingFlowActive: true,
        hasAuthenticatedAccount: true
    ) == .wait,
    "A sensitive or modal flow must defer opening even for an authenticated account."
)
private let pendingBeforeInvalidDeepLink = pendingDeepLink
expect(
    !pendingDeepLink.enqueueCatalogDetail(validatedListingID: nil) &&
        pendingDeepLink == pendingBeforeInvalidDeepLink,
    "An invalid parsed deep link must not replace the last valid pending listing."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
private let coalescedDeepLinkDelivery = requireCatalogDetailDelivery(
    pendingDeepLink,
    "The coalesced listing must retain its delivery."
)
expect(
    pendingDeepLink.catalogDetailListingID == deepLinkListingID &&
        coalescedDeepLinkDelivery == initialDeepLinkDelivery,
    "An identical deep link received before consumption must coalesce in the single pending slot."
)
expect(
    pendingDeepLink.acknowledgeCatalogDetail(delivery: initialDeepLinkDelivery) &&
        !pendingDeepLink.acknowledgeCatalogDetail(delivery: initialDeepLinkDelivery),
    "The matching listing deep-link delivery must be acknowledged exactly once."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
private let repeatedDeepLinkDelivery = requireCatalogDetailDelivery(
    pendingDeepLink,
    "The same listing after consumption must create another delivery."
)
expect(
    repeatedDeepLinkDelivery != initialDeepLinkDelivery &&
        pendingDeepLink.acknowledgeCatalogDetail(delivery: repeatedDeepLinkDelivery),
    "The same listing received after consumption must form a new delivery."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
private let deliveryBeforeReplacement = requireCatalogDetailDelivery(
    pendingDeepLink,
    "The listing before replacement must have a delivery."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: replacementDeepLinkListingID)
private let replacementDeepLinkDelivery = requireCatalogDetailDelivery(
    pendingDeepLink,
    "The replacement listing must create a delivery."
)
expect(
    pendingDeepLink.catalogDetailListingID == replacementDeepLinkListingID &&
        replacementDeepLinkDelivery != deliveryBeforeReplacement &&
        !pendingDeepLink.isCurrentCatalogDetail(delivery: deliveryBeforeReplacement) &&
        pendingDeepLink.isCurrentCatalogDetail(delivery: replacementDeepLinkDelivery) &&
        !pendingDeepLink.acknowledgeCatalogDetail(delivery: deliveryBeforeReplacement) &&
        pendingDeepLink.acknowledgeCatalogDetail(delivery: replacementDeepLinkDelivery),
    "The last different valid listing must replace the previous pending destination."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
pendingDeepLink.enqueueRoot(destinationKey: "home")
expect(
    pendingDeepLink.rootDestinationKey == "home" &&
        pendingDeepLink.catalogDetailListingID == nil,
    "A later root deep link must replace a pending listing destination."
)
expect(
    pendingDeepLink.consumeRoot() && !pendingDeepLink.consumeRoot(),
    "A root deep link must also be claimable exactly once."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
private let deliveryBeforeSensitiveReset = requireCatalogDetailDelivery(
    pendingDeepLink,
    "The pending listing before a sensitive reset must have a delivery."
)
pendingDeepLink.clear()
expect(
    pendingDeepLink.rootDestinationKey == nil &&
        pendingDeepLink.catalogDetailListingID == nil &&
        !pendingDeepLink.isCurrentCatalogDetail(delivery: deliveryBeforeSensitiveReset) &&
        !pendingDeepLink.acknowledgeCatalogDetail(delivery: deliveryBeforeSensitiveReset) &&
        catalogDetailPostBootstrapAction(pending: pendingDeepLink) == .wait,
    "Sensitive resets must clear every pending internal deep link."
)

private func approximatelyEqual(
    _ first: CGFloat,
    _ second: CGFloat,
    tolerance: CGFloat = 0.000_001
) -> Bool {
    abs(first - second) <= tolerance
}

expect(
    CatalogDetailLayoutPolicy.sheetHeightFraction(forWidth: 0) == 0.92,
    "A zero-width transient layout must keep the mobile detail detent."
)
expect(
    CatalogDetailLayoutPolicy.sheetHeightFraction(forWidth: 599.999) == 0.92,
    "A detail sheet below the tablet breakpoint must use the 92 percent detent."
)
expect(
    CatalogDetailLayoutPolicy.sheetHeightFraction(forWidth: 600) == 0.85,
    "The 600-point breakpoint must switch to the tablet detail detent."
)
expect(
    CatalogDetailLayoutPolicy.sheetHeightFraction(forWidth: 1_024) == 0.85,
    "A tablet detail sheet must use the 85 percent detent."
)
expect(
    CatalogDetailLayoutPolicy.sheetHeightFraction(forWidth: .nan) == 0.92,
    "An invalid measured width must fail safely to the mobile detail detent."
)

expect(
    CatalogDetailLayoutPolicy.heroHeight(forSheetHeight: 500) == 320,
    "A short detail sheet must retain the 320-point hero minimum."
)
expect(
    approximatelyEqual(
        CatalogDetailLayoutPolicy.heroHeight(forSheetHeight: 800),
        464
    ),
    "A regular detail hero must occupy 58 percent of the sheet height."
)
expect(
    CatalogDetailLayoutPolicy.heroHeight(forSheetHeight: 0) == 320,
    "A transient zero-height sheet must retain the hero minimum."
)
expect(
    CatalogDetailLayoutPolicy.heroHeight(forSheetHeight: .infinity) == 320,
    "A non-finite sheet height must fail safely to the hero minimum."
)

expect(
    CatalogDetailLayoutPolicy.sheetWidth(availableWidth: 390) == 390,
    "A phone detail sheet must preserve its available width."
)
expect(
    CatalogDetailLayoutPolicy.sheetWidth(availableWidth: 640) == 640,
    "The detail sheet maximum width must remain usable exactly at its boundary."
)
expect(
    CatalogDetailLayoutPolicy.sheetWidth(availableWidth: 1_024) == 640,
    "A tablet detail sheet must be capped at 640 points."
)
expect(
    CatalogDetailLayoutPolicy.sheetWidth(availableWidth: -1) == 0,
    "A negative transient width must be clamped to zero."
)
expect(
    CatalogDetailLayoutPolicy.sheetWidth(availableWidth: .infinity) == 640,
    "An unbounded width must still respect the detail maximum."
)
expect(
    CatalogDetailLayoutPolicy.sheetWidth(availableWidth: .nan) == 0,
    "An invalid measured width must fail safely to zero."
)
expect(
    CatalogDetailLayoutPolicy.maximumContentWidth == 640,
    "The detail content width exposed to SwiftUI must be capped at 640 points."
)

private let shortDescription = "Une description courte."
expect(
    !CatalogDetailDescriptionPolicy.needsExpansion(shortDescription),
    "A short detail description must not offer expansion."
)
expect(
    CatalogDetailDescriptionPolicy.preview(shortDescription) == shortDescription,
    "A short detail description must remain unchanged."
)

private let exactLimitDescription = String(repeating: "a", count: 150)
expect(
    !CatalogDetailDescriptionPolicy.needsExpansion(exactLimitDescription),
    "A description exactly at the preview limit must not offer expansion."
)
expect(
    CatalogDetailDescriptionPolicy.preview(exactLimitDescription) == exactLimitDescription,
    "A description exactly at the preview limit must remain unchanged."
)

private let unbrokenDescription = String(repeating: "a", count: 151)
expect(
    CatalogDetailDescriptionPolicy.needsExpansion(unbrokenDescription),
    "A description above the preview limit must offer expansion."
)
expect(
    CatalogDetailDescriptionPolicy.preview(unbrokenDescription) ==
        String(repeating: "a", count: 150) + "…",
    "A long unbroken description must fall back to a safe 150-character preview."
)

private let wordBoundaryDescription =
    String(repeating: "a", count: 125) + " " + String(repeating: "b", count: 40)
expect(
    CatalogDetailDescriptionPolicy.preview(wordBoundaryDescription) ==
        String(repeating: "a", count: 125) + "…",
    "A preview must use the last word boundary between 120 and 150 characters."
)

private let minimumBoundaryDescription =
    String(repeating: "a", count: 120) + "\n" + String(repeating: "b", count: 40)
expect(
    CatalogDetailDescriptionPolicy.preview(minimumBoundaryDescription) ==
        String(repeating: "a", count: 120) + "…",
    "A whitespace boundary exactly at 120 characters must be accepted."
)

private let tooEarlyBoundaryDescription =
    String(repeating: "a", count: 119) + " " + String(repeating: "b", count: 40)
private let tooEarlyBoundaryPreview = CatalogDetailDescriptionPolicy.preview(
    tooEarlyBoundaryDescription
)
expect(
    tooEarlyBoundaryPreview.dropLast().count == 150,
    "A word boundary before 120 characters must not shorten the preview."
)
expect(
    tooEarlyBoundaryPreview.hasSuffix("…"),
    "Every collapsed long description must end with an ellipsis."
)

private let familyEmoji = "👨‍👩‍👧‍👦"
private let emojiDescription = String(repeating: familyEmoji, count: 151)
private let emojiPreview = CatalogDetailDescriptionPolicy.preview(emojiDescription)
expect(
    emojiPreview.dropLast().count == 150,
    "Unicode grapheme clusters must not be split by detail truncation."
)
expect(
    emojiPreview.dropLast().allSatisfy { String($0) == familyEmoji },
    "A composed family emoji must remain byte-logically intact in the preview."
)
expect(
    emojiPreview.hasSuffix("…"),
    "A Unicode detail preview must end with one ellipsis."
)

private let combiningGrapheme = "e\u{301}"
private let combiningDescription = String(repeating: combiningGrapheme, count: 151)
private let combiningPreview = CatalogDetailDescriptionPolicy.preview(combiningDescription)
expect(
    combiningPreview.dropLast().count == 150,
    "A combining-mark grapheme must count as one visible preview character."
)
expect(
    combiningPreview.dropLast().allSatisfy { String($0) == combiningGrapheme },
    "Combining marks must remain attached to their base character."
)

expect(
    CatalogDetailLabelPolicy.pluralizedLabel(
        count: 0,
        singular: "avis",
        plural: "avis"
    ) == "avis",
    "Zero must use the supplied French plural label."
)
expect(
    CatalogDetailLabelPolicy.pluralizedLabel(
        count: 1,
        singular: "vue",
        plural: "vues"
    ) == "vue",
    "Exactly one item must use the singular label."
)
expect(
    CatalogDetailLabelPolicy.pluralizedLabel(
        count: 2,
        singular: "like",
        plural: "likes"
    ) == "likes",
    "Counts other than one must use the plural label."
)

private let directionsURL = CatalogDetailExternalURLPolicy.url(
    for: .directions(
        latitude: 6.370293,
        longitude: 2.391236,
        label: "Fondation Zinsou"
    )
)
private let directionsComponents = directionsURL.flatMap {
    URLComponents(url: $0, resolvingAgainstBaseURL: false)
}
expect(
    directionsComponents?.scheme == "https" &&
        directionsComponents?.host == "www.google.com" &&
        directionsComponents?.path == "/maps/dir/",
    "Directions must use the official Google Maps HTTPS URL endpoint."
)
expect(
    directionsComponents?.queryItems?.first(where: { $0.name == "api" })?.value == "1",
    "The Google Maps URL must explicitly select Maps URLs API version 1."
)
expect(
    directionsComponents?.queryItems?.first(where: { $0.name == "destination" })?.value ==
        "6.370293,2.391236",
    "Directions must preserve the validated destination coordinates."
)
expect(
    CatalogDetailExternalURLPolicy.directionsURL(
        latitude: .nan,
        longitude: 2.391236,
        label: "Fondation Zinsou"
    ) == nil,
    "Non-finite direction coordinates must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.directionsURL(
        latitude: 91,
        longitude: 2.391236,
        label: "Fondation Zinsou"
    ) == nil,
    "Out-of-range latitude must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.directionsURL(
        latitude: 6.370293,
        longitude: -181,
        label: "Fondation Zinsou"
    ) == nil,
    "Out-of-range longitude must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.directionsURL(
        latitude: 6.370293,
        longitude: 2.391236,
        label: " \n"
    ) == nil,
    "A blank or control-bearing directions label must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.directionsURL(
        latitude: 6.370293,
        longitude: 2.391236,
        label: String(repeating: "a", count: 80)
    ) != nil,
    "A directions label exactly at the catalog 80-character boundary must be accepted."
)
expect(
    CatalogDetailExternalURLPolicy.directionsURL(
        latitude: 6.370293,
        longitude: 2.391236,
        label: String(repeating: "a", count: 81)
    ) == nil,
    "A directions label above the catalog 80-character boundary must fail closed."
)

expect(
    CatalogDetailExternalURLPolicy.url(for: .phone("+2290197000000"))?.absoluteString ==
        "tel:+2290197000000",
    "A valid Beninese phone number must produce a tel URL."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("2290197000000") == nil,
    "A phone number without the Beninese calling-code prefix must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("+229 01 97 00 00 00") == nil,
    "A formatted phone number must not bypass strict Beninese validation."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("+2290197000000,123") == nil,
    "Telephone pause and extension injection must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("+٢٢٩٠١٩٧٠٠٠٠٠٠") == nil,
    "Non-ASCII digits must not enter a telephone URL."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("+22912345") != nil &&
        CatalogDetailExternalURLPolicy.phoneURL("+229123456789012") != nil,
    "Beninese phone numbers at both national-length boundaries must be accepted."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("+2291234") == nil &&
        CatalogDetailExternalURLPolicy.phoneURL("+2291234567890123") == nil,
    "Beninese phone numbers outside the 5-to-12-digit national range must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.phoneURL("+2280197000000") == nil,
    "A foreign phone number must fail closed for the Benin-only catalog."
)

expect(
    CatalogDetailExternalURLPolicy.url(for: .whatsapp("+2290197000000"))?.absoluteString ==
        "https://wa.me/2290197000000",
    "A valid WhatsApp number must use the canonical wa.me HTTPS URL."
)
expect(
    CatalogDetailExternalURLPolicy.whatsappURL("+02290197000000") == nil,
    "An invalid country prefix must fail closed for WhatsApp."
)
expect(
    CatalogDetailExternalURLPolicy.whatsappURL("+2290197000000?text=secret") == nil,
    "WhatsApp query injection must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.whatsappURL("+22912345") != nil &&
        CatalogDetailExternalURLPolicy.whatsappURL("+229123456789012") != nil,
    "WhatsApp must accept both Beninese national-length boundaries."
)
expect(
    CatalogDetailExternalURLPolicy.whatsappURL("+2280197000000") == nil,
    "WhatsApp must reject a foreign phone number."
)

private let emailURL = CatalogDetailExternalURLPolicy.url(
    for: .email("bonjour+guide@KWABOR.example")
)
private let emailComponents = emailURL.flatMap {
    URLComponents(url: $0, resolvingAgainstBaseURL: false)
}
expect(
    emailComponents?.scheme == "mailto" &&
        emailComponents?.path == "bonjour+guide@kwabor.example",
    "A valid email must produce a mailto URL with a canonical domain."
)
expect(
    CatalogDetailExternalURLPolicy.emailURL("bonjour@example.com\r\nBcc:attacker@example.com") == nil,
    "Email header injection must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.emailURL("bonjour@@example.com") == nil,
    "An email with multiple separators must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.emailURL(".bonjour@example.com") == nil,
    "An invalid local email part must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.emailURL("bonjour@service.internal") == nil,
    "An internal email domain must fail closed."
)

expect(
    CatalogDetailExternalURLPolicy.url(
        for: .https("https://tickets.kwabor.example/event/42?source=app")
    ) != nil,
    "A canonical public HTTPS target must be accepted."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example:443/event/42"
    ) != nil,
    "The canonical HTTPS port must be accepted."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "http://tickets.kwabor.example/event/42"
    ) == nil,
    "Non-HTTPS external targets must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://user:password@tickets.kwabor.example/event/42"
    ) == nil,
    "Credentials in an external target must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example/event/42#checkout"
    ) == nil,
    "Fragments in an external target must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example:444/event/42"
    ) == nil,
    "A non-canonical HTTPS port must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL("https://localhost/event/42") == nil &&
        CatalogDetailExternalURLPolicy.acceptedHTTPSURL("https://service.internal/event/42") == nil &&
        CatalogDetailExternalURLPolicy.acceptedHTTPSURL("https://127.0.0.1/event/42") == nil,
    "Local, internal, and IP-shaped hosts must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://0x7f.0x0.0x0.0x1/event/42"
    ) == nil,
    "A historical hexadecimal IPv4 loopback form must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://Tickets.kwabor.example/event/42"
    ) == nil,
    "A non-canonical host must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example/%"
    ) == nil,
    "Malformed percent encoding must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example/%0Acheckout"
    ) == nil,
    "Percent-encoded controls must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example/%5Ccheckout"
    ) == nil,
    "A percent-encoded backslash must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example\\@attacker.example/event/42"
    ) == nil,
    "A literal backslash parser ambiguity must fail closed."
)
expect(
    CatalogDetailExternalURLPolicy.acceptedHTTPSURL(
        "https://tickets.kwabor.example/" + String(repeating: "a", count: 2_100)
    ) == nil,
    "Oversized external targets must fail closed before parsing."
)
