import Foundation

private func expect(
    _ condition: @autoclosure () -> Bool,
    _ message: String
) {
    guard condition() else {
        fatalError(message)
    }
}

private func repositorySource(_ path: String) -> String {
    guard let source = try? String(contentsOfFile: path, encoding: .utf8) else {
        fatalError("Unable to read repository source: \(path)")
    }
    return source
}

private func sourceSection(
    _ source: String,
    from startMarker: String,
    until endMarker: String
) -> String {
    guard let start = source.range(of: startMarker),
          let end = source.range(of: endMarker, range: start.upperBound..<source.endIndex) else {
        fatalError("Unable to locate source section from \(startMarker) to \(endMarker).")
    }
    return String(source[start.lowerBound..<end.lowerBound])
}

private func sourceContains(_ first: String, before second: String, in source: String) -> Bool {
    guard let firstRange = source.range(of: first),
          let secondRange = source.range(of: second) else {
        return false
    }
    return firstRange.lowerBound < secondRange.lowerBound
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

private var favoritesPaginationGuard = FavoritesPaginationGuard()
expect(
    !favoritesPaginationGuard.shouldLoadNext(
        cursor: "favorites-cursor-1",
        canLoadMore: true,
        isNearEnd: false,
        hasAppendError: false
    ),
    "Favorites pagination must wait until a card reaches the bounded end threshold."
)
expect(
    favoritesPaginationGuard.shouldLoadNext(
        cursor: "favorites-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "Favorites pagination must request the first eligible cursor once."
)
expect(
    !favoritesPaginationGuard.shouldLoadNext(
        cursor: "favorites-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "Favorites pagination must de-duplicate a cursor already requested."
)
expect(
    !favoritesPaginationGuard.shouldLoadNext(
        cursor: "favorites-cursor-2",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: true
    ),
    "Favorites automatic pagination must stop after an append error."
)
expect(
    favoritesPaginationGuard.shouldRetry(
        cursor: "favorites-cursor-2",
        canLoadMore: true
    ),
    "Favorites must permit an explicit retry for the failed cursor."
)
favoritesPaginationGuard.reset()
expect(
    favoritesPaginationGuard.shouldLoadNext(
        cursor: "favorites-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "A Favorites filter or refresh must reset opaque cursor de-duplication."
)
expect(
    !FavoritesPaginationPolicy.isNearEnd(index: 14, itemCount: 20) &&
        FavoritesPaginationPolicy.isNearEnd(index: 16, itemCount: 20),
    "Favorites pagination must start only inside its four-card end threshold."
)
expect(
    !FavoritesPaginationPolicy.isNearEnd(index: -1, itemCount: 20) &&
        !FavoritesPaginationPolicy.isNearEnd(index: 20, itemCount: 20) &&
        !FavoritesPaginationPolicy.isNearEnd(index: 0, itemCount: 0),
    "Favorites pagination must reject invalid visible-item positions."
)
expect(
    FavoritesGridPolicy.columnCount(
        availableWidth: 390,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: false
    ) == 2,
    "Favorites must use two virtualized grid columns on a regular phone."
)
expect(
    FavoritesGridPolicy.columnCount(
        availableWidth: 700,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: false
    ) == 3,
    "Favorites must use three virtualized grid columns on a wide layout."
)
expect(
    FavoritesGridPolicy.columnCount(
        availableWidth: 700,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: true
    ) == 1,
    "Favorites must collapse to one column for accessibility text sizes."
)
expect(
    FavoritesGridPolicy.columnCount(
        availableWidth: .nan,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: false
    ) == 1,
    "Favorites must fail closed to one column for an invalid layout width."
)
expect(
    FavoritesViewerTransitionPolicy.normalizedAccountID("  account-a  ") == "account-a" &&
        FavoritesViewerTransitionPolicy.normalizedAccountID("   ") == nil,
    "Favorites account identity must be canonical before private-state routing."
)
expect(
    FavoritesViewerTransitionPolicy.shouldHidePrivateContent(
        currentAccountID: "account-a",
        nextAccountID: "account-b"
    ) &&
        FavoritesViewerTransitionPolicy.shouldHidePrivateContent(
            currentAccountID: "account-a",
            nextAccountID: nil
        ) &&
        !FavoritesViewerTransitionPolicy.shouldHidePrivateContent(
            currentAccountID: " account-a ",
            nextAccountID: "account-a"
        ),
    "Favorites must hide owner data on account replacement or logout, but not on canonical no-op updates."
)
let endedFavoriteDecorations = FavoritesCardDecorationPolicy.visibility(
    isEventEnded: true,
    ratingLabel: "4,8"
)
expect(
    endedFavoriteDecorations.showsEndedRibbon && endedFavoriteDecorations.showsRating,
    "An ended favorite must keep both its diagonal ended ribbon and its rating."
)
expect(
    !FavoritesCardDecorationPolicy.visibility(
        isEventEnded: false,
        ratingLabel: "  "
    ).showsRating,
    "Favorites must not render an empty rating decoration."
)

expect(
    ContextualAuthenticationDismissalPolicy.action(
        hasCompleteAccount: false,
        isRegistrationPresented: true,
        registrationWasRequested: false
    ) == .keepForPresentedRegistration,
    "An incomplete federated registration must retain its contextual protected action."
)
expect(
    ContextualAuthenticationDismissalPolicy.action(
        hasCompleteAccount: true,
        isRegistrationPresented: false,
        registrationWasRequested: false
    ) == .keepForAuthenticatedReplay,
    "A successful authentication must preserve its pending protected destination until replay."
)
expect(
    ContextualAuthenticationDismissalPolicy.action(
        hasCompleteAccount: false,
        isRegistrationPresented: false,
        registrationWasRequested: true
    ) == .presentRequestedRegistration,
    "An explicit transition from authentication to registration must present registration."
)
expect(
    ContextualAuthenticationDismissalPolicy.action(
        hasCompleteAccount: false,
        isRegistrationPresented: false,
        registrationWasRequested: false
    ) == .cancel,
    "A dismissed authentication sheet without a registration transition must cancel the journey."
)
expect(
    ProtectedDestinationReplayPolicy.action(
        isGuest: true,
        hasPendingRootDeepLink: false,
        isRootDeepLinkProtected: false,
        pendingDestinationKey: "profile"
    ) == .wait,
    "A protected destination must remain pending while the viewer is a guest."
)
expect(
    ProtectedDestinationReplayPolicy.action(
        isGuest: false,
        hasPendingRootDeepLink: false,
        isRootDeepLinkProtected: false,
        pendingDestinationKey: "profile"
    ) == .select("profile"),
    "A protected destination must replay after successful authentication."
)
expect(
    ProtectedDestinationReplayPolicy.action(
        isGuest: true,
        hasPendingRootDeepLink: true,
        isRootDeepLinkProtected: true,
        pendingDestinationKey: nil
    ) == .transferRootDeepLinkToAuthentication,
    "A protected root deep link must transfer atomically into guest authentication."
)
expect(
    ProtectedDestinationReplayPolicy.action(
        isGuest: false,
        hasPendingRootDeepLink: true,
        isRootDeepLinkProtected: true,
        pendingDestinationKey: "profile"
    ) == .applyRootDeepLink(discardProtectedDestination: true),
    "A root deep link must take priority and consume an older protected destination."
)
expect(
    ProtectedDestinationReplayPolicy.action(
        isGuest: true,
        hasPendingRootDeepLink: true,
        isRootDeepLinkProtected: false,
        pendingDestinationKey: nil
    ) == .applyRootDeepLink(discardProtectedDestination: false),
    "The public home root deep link must still open for a guest."
)
expect(
    ProtectedDestinationReplayPolicy.action(
        isGuest: true,
        hasPendingRootDeepLink: false,
        isRootDeepLinkProtected: false,
        pendingDestinationKey: nil
    ) == .wait,
    "Cancelling a transferred root deep link must leave no trigger that reopens authentication."
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

private func requireRootDelivery(
    _ pending: PendingInternalDeepLink,
    _ message: String
) -> RootDeepLinkDelivery {
    guard let delivery = pending.rootDelivery else {
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
private let initialRootDelivery = requireRootDelivery(
    pendingDeepLink,
    "The first root deep link must create a delivery."
)
pendingDeepLink.enqueueRoot(destinationKey: "profile")
private let coalescedRootDelivery = requireRootDelivery(
    pendingDeepLink,
    "The coalesced root deep link must retain its delivery."
)
expect(
    pendingDeepLink.rootDestinationKey == "profile" &&
        pendingDeepLink.catalogDetailListingID == nil &&
        coalescedRootDelivery == initialRootDelivery,
    "An identical pending root deep link must coalesce into one delivery."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
expect(
    pendingDeepLink.rootDestinationKey == nil &&
        pendingDeepLink.catalogDetailListingID == deepLinkListingID &&
        !pendingDeepLink.isCurrentRoot(delivery: initialRootDelivery) &&
        !pendingDeepLink.acknowledgeRoot(delivery: initialRootDelivery),
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
private let firstHomeRootDelivery = requireRootDelivery(
    pendingDeepLink,
    "The home root deep link must create a delivery."
)
expect(
    pendingDeepLink.rootDestinationKey == "home" &&
        pendingDeepLink.catalogDetailListingID == nil,
    "A later root deep link must replace a pending listing destination."
)
expect(
    pendingDeepLink.acknowledgeRoot(delivery: firstHomeRootDelivery) &&
        !pendingDeepLink.acknowledgeRoot(delivery: firstHomeRootDelivery),
    "A matching root deep-link delivery must be acknowledged exactly once."
)
pendingDeepLink.enqueueRoot(destinationKey: "home")
private let repeatedHomeRootDelivery = requireRootDelivery(
    pendingDeepLink,
    "The repeated home root deep link must create a new delivery."
)
expect(
    repeatedHomeRootDelivery != firstHomeRootDelivery,
    "The same root destination received after acknowledgement must have a new revision."
)
pendingDeepLink.enqueueRoot(destinationKey: "profile")
private let replacementRootDelivery = requireRootDelivery(
    pendingDeepLink,
    "The replacement root deep link must create a delivery."
)
expect(
    replacementRootDelivery != repeatedHomeRootDelivery &&
        !pendingDeepLink.acknowledgeRoot(delivery: repeatedHomeRootDelivery) &&
        pendingDeepLink.isCurrentRoot(delivery: replacementRootDelivery),
    "A stale root callback must not consume a newer destination."
)
pendingDeepLink.enqueueCatalogDetail(validatedListingID: deepLinkListingID)
expect(
    !pendingDeepLink.isCurrentRoot(delivery: replacementRootDelivery) &&
        !pendingDeepLink.acknowledgeRoot(delivery: replacementRootDelivery),
    "A listing replacement must make the previous root delivery stale."
)
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

expect(
    SearchGridPolicy.columnCount(
        availableWidth: 390,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: false
    ) == 2,
    "Search uses two columns on a regular phone width."
)
expect(
    SearchGridPolicy.columnCount(
        availableWidth: 700,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: false
    ) == 3,
    "Search uses three columns at the tablet breakpoint."
)
expect(
    SearchGridPolicy.columnCount(
        availableWidth: 700,
        tabletBreakpoint: 600,
        usesAccessibilityLayout: true
    ) == 1,
    "Search must collapse to one column for accessibility text sizes."
)

let exploreV2Decorations = ExploreCardDecorationPolicy.presentation(
    isSponsoredPlacement: true,
    ratingLabel: " 4,8 ",
    eventDateLabel: " 20 juin › ",
    isEventEnded: true
)
expect(
    exploreV2Decorations.showsSponsoredBadge &&
        exploreV2Decorations.ratingLabel == nil &&
        exploreV2Decorations.eventDateLabel == "20 juin ›" &&
        exploreV2Decorations.showsEndedRibbon,
    "Explore v2 cards must let Sponsorisé replace the rating while preserving date and ended decorations."
)
let organicExploreDecorations = ExploreCardDecorationPolicy.presentation(
    isSponsoredPlacement: false,
    ratingLabel: " 4,8 ",
    eventDateLabel: "20 juin ›",
    isEventEnded: false
)
expect(
    organicExploreDecorations.ratingLabel == "4,8" &&
        !organicExploreDecorations.showsSponsoredBadge,
    "Organic Explore cards must keep the frosted rating badge."
)
expect(
    !ExploreCardDecorationPolicy.presentation(
        isSponsoredPlacement: false,
        ratingLabel: nil,
        eventDateLabel: nil,
        isEventEnded: false
    ).showsSponsoredBadge,
    "Explore must never infer a sponsored badge for an organic server placement."
)
let emptyExploreDecorations = ExploreCardDecorationPolicy.presentation(
    isSponsoredPlacement: false,
    ratingLabel: "  ",
    eventDateLabel: "\n",
    isEventEnded: false
)
expect(
    emptyExploreDecorations.ratingLabel == nil && emptyExploreDecorations.eventDateLabel == nil,
    "Explore must not render empty rating or date decorations."
)
expect(
    ExploreCardImageAccessibilityPolicy.description(
        coverImageAlt: "  Danseurs masqués sur la place  ",
        fallbackTitle: "Festival des masques"
    ) == "Danseurs masqués sur la place" &&
        ExploreCardImageAccessibilityPolicy.description(
            coverImageAlt: " ",
            fallbackTitle: "Festival des masques"
        ) == "Festival des masques",
    "Explore images must expose normalized alt text and fall back safely to the listing title."
)
expect(
    !SearchPaginationPolicy.isNearEnd(index: 14, itemCount: 20) &&
        SearchPaginationPolicy.isNearEnd(index: 16, itemCount: 20),
    "Search pagination starts only inside the bounded end threshold."
)
expect(
    !SearchPaginationPolicy.isNearEnd(index: -1, itemCount: 20) &&
        !SearchPaginationPolicy.isNearEnd(index: 20, itemCount: 20) &&
        !SearchPaginationPolicy.isNearEnd(index: 0, itemCount: 0),
    "Search pagination rejects invalid item positions."
)

private var searchPaginationGuard = SearchPaginationGuard()
expect(
    searchPaginationGuard.shouldLoadNext(
        cursor: "search-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "Search pagination requests an eligible cursor once."
)
expect(
    !searchPaginationGuard.shouldLoadNext(
        cursor: "search-cursor-1",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: false
    ),
    "Search pagination de-duplicates an in-flight cursor."
)
expect(
    !searchPaginationGuard.shouldLoadNext(
        cursor: "search-cursor-2",
        canLoadMore: true,
        isNearEnd: true,
        hasAppendError: true
    ),
    "Search pagination stops automatic loading after an append error."
)
expect(
    searchPaginationGuard.shouldRetry(cursor: "search-cursor-2", canLoadMore: true),
    "Search pagination permits an explicit retry after an append error."
)

private let iosRootSource = repositorySource(
    "shared/src/iosMain/kotlin/com/kwabor/shared/app/IosKwaborCompositionRoot.kt"
)
private let iosAuthSource = repositorySource(
    "shared/src/iosMain/kotlin/com/kwabor/shared/app/IosAccountDeletionCoordinator.kt"
)
private let kwaborAppSource = repositorySource("iosApp/Kwabor/App/KwaborApp.swift")
private let firebaseObservabilitySource = repositorySource(
    "iosApp/Kwabor/Observability/FirebaseObservability.swift"
)
private let accountDeletionSource = repositorySource(
    "iosApp/Kwabor/Onboarding/AccountDeletionView.swift"
)
private let federatedSignInSource = repositorySource(
    "iosApp/Kwabor/Onboarding/FederatedSignIn.swift"
)
private let onboardingCoordinatorSource = repositorySource(
    "iosApp/Kwabor/Onboarding/OnboardingCoordinator.swift"
)
private let rootNavigationSource = repositorySource(
    "iosApp/Kwabor/App/RootNavigation.swift"
)
private let contentViewSource = repositorySource(
    "iosApp/Kwabor/App/ContentView.swift"
)
private let catalogDetailSheetSource = repositorySource(
    "iosApp/Kwabor/Detail/CatalogDetailSheet.swift"
)
private let catalogDetailTypedContentSource = repositorySource(
    "iosApp/Kwabor/Detail/CatalogDetailTypedContent.swift"
)
private let sharedBridgeSource = repositorySource(
    "shared/src/commonMain/kotlin/com/kwabor/shared/bridge/KwaborSharedBridge.kt"
)
private let kwaborStringsSource = repositorySource(
    "shared/src/commonMain/kotlin/com/kwabor/shared/i18n/KwaborStrings.kt"
)
private let iosPackageLockSource = repositorySource(
    "iosApp/Kwabor.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
)

expect(
    rootNavigationSource.contains(
        "static let closedBetaCases: [RootDestination] = [.home, .profile]"
    ) && contentViewSource.contains(
        "ForEach(RootDestination.visibleCases(closedBetaCatalog: isClosedBetaCatalog))"
    ),
    "The closed-beta tab surface must expose only the Explorer and Account roots."
)
expect(
    kwaborStringsSource.contains("closedBetaExploreRoot = \"Explorer\"") &&
        kwaborStringsSource.contains("closedBetaAccountRoot = \"Compte\"") &&
        sharedBridgeSource.contains(
            "RootNavigationDestination.Home.label(strings, rootNavigationProfile)"
        ) && sharedBridgeSource.contains(
            "RootNavigationDestination.Profile.label(strings, rootNavigationProfile)"
        ),
    "The iOS bridge must use the exact closed-beta labels without changing the full profile."
)

private let unavailableRootDeepLinkSection = sourceSection(
    onboardingCoordinatorSource,
    from: "if bridge.isUnavailableRootDeepLink",
    until: "return requiresProtectedAuthentication"
)
expect(
    sourceContains(
        "rootNavigationNotice = bridge.rootDestinationUnavailableMessage",
        before: "pendingInternalDeepLink.enqueueRoot(destinationKey: RootDestination.home.rawValue)",
        in: unavailableRootDeepLinkSection
    ),
    "A hidden closed-beta root deep link must show a neutral notice and fall back to Explorer."
)

private let catalogDetailPrimaryActionSection = sourceSection(
    catalogDetailSheetSource,
    from: "private var primaryAction: CatalogDetailPrimaryAction?",
    until: "private var secondaryDirections: CatalogDetailDirectionsUiModel?"
)
private let catalogDetailSecondaryDirectionsSection = sourceSection(
    catalogDetailSheetSource,
    from: "private var secondaryDirections: CatalogDetailDirectionsUiModel?",
    until: "private struct CatalogDetailDemoDisclosure"
)
expect(
    sourceContains(
        "guard !state.model.isDemoContent else { return nil }",
        before: "CatalogDetailExternalURLPolicy.url(for: directions.target)",
        in: catalogDetailPrimaryActionSection
    ) && catalogDetailSecondaryDirectionsSection.contains(
        "guard !state.model.isDemoContent"
    ) && catalogDetailTypedContentSource.contains(
        "if allowsExternalActions,"
    ) && catalogDetailTypedContentSource.contains(
        "allowsExternalActions && ticketing.externalUrl.flatMap"
    ),
    "Demo listings must expose no external CTA, including directions, menu, contact, or ticketing."
)
expect(
    catalogDetailSheetSource.components(
        separatedBy: "guard !currentContentIsDemo else { return }"
    ).count - 1 == 2,
    "The iOS external launch boundary must also reject stale actions after switching to demo content."
)

expect(
    iosRootSource.components(
        separatedBy: "interactionCoordinator = sharedRoot?.interactionCoordinator"
    ).count - 1 == 3,
    "The iOS root must inject the one durable coordinator into Explore, Favorites, and Auth."
)
expect(
    iosRootSource.contains("fun applicationBecameActive()") &&
        iosRootSource.contains("sharedRoot?.interactionCoordinator?.onForeground()"),
    "The iOS root foreground hook must wake the durable interaction coordinator."
)
private let activeSceneSection = sourceSection(
    kwaborAppSource,
    from: "if phase == .active",
    until: "                }\n        }"
)
expect(
    activeSceneSection.contains("coordinator.applicationBecameActive()") &&
        activeSceneSection.contains("observability.applicationEnteredForeground()") &&
        activeSceneSection.contains("compositionRoot.applicationBecameActive()"),
    "An active iOS scene must wake maintenance, observed sessions, and durable interactions."
)
private let backgroundSceneSection = sourceSection(
    kwaborAppSource,
    from: "if phase == .background",
    until: "                }\n        }"
)
expect(
    backgroundSceneSection.contains("observability.applicationEnteredBackground()"),
    "A background iOS scene must checkpoint the app-owned observed session."
)
expect(
    firebaseObservabilitySource.contains(
        "guard isConfigured, effectiveAnalyticsAllowed, effectiveDiagnosticsAllowed else { return }"
    ) && firebaseObservabilitySource.contains(
        "let allowed = isConfigured && effectiveAnalyticsAllowed && effectiveDiagnosticsAllowed"
    ) && firebaseObservabilitySource.contains(
        "sessionTracker?.updateMeasurementEligibility(allowed: allowed)"
    ) && firebaseObservabilitySource.contains("guard revokeObservedSession() else { return false }"),
    "Observed sessions must require both effective consents and clear their checkpoint on revocation."
)
private let consentUpdateSection = sourceSection(
    firebaseObservabilitySource,
    from: "func updateConsent(_ updatedConsent: ObservabilityConsent, ownerUserId: String) -> Bool",
    until: "func revokeAllConsent() -> Bool"
)
private let consentRevocationSection = sourceSection(
    firebaseObservabilitySource,
    from: "private func attemptConsentRevocation() -> Bool",
    until: "func resetConsentForFreshInstallation() -> Bool"
)
private let consentRevocationRequestSection = sourceSection(
    firebaseObservabilitySource,
    from: "func revokeAllConsent() -> Bool",
    until: "private func attemptConsentRevocation() -> Bool"
)
private let consentRetrySection = sourceSection(
    firebaseObservabilitySource,
    from: "func retryPendingMaintenance()",
    until: "func applicationEnteredForeground()"
)
private let effectiveCollectionSection = sourceSection(
    firebaseObservabilitySource,
    from: "private var maintenanceAllowsCollection: Bool",
    until: "private var diagnosticsMaintenanceAllowsCollection: Bool"
)
expect(
    sourceContains(
        "pendingConsentMutation = mutation",
        before: "return attemptConsentUpdate(mutation)",
        in: consentUpdateSection
    ) && sourceContains(
        "pendingConsentMutation = .revoke",
        before: "return attemptConsentRevocation()",
        in: consentRevocationRequestSection
    ) && sourceContains(
        "!revokeObservedSession()",
        before: "pendingConsentMutation = nil",
        in: consentUpdateSection
    ) && sourceContains(
        "guard revokeObservedSession() else { return false }",
        before: "pendingConsentMutation = nil",
        in: consentRevocationSection
    ) && sourceContains(
        "requestFirebaseInstallationDeletion(.revokeConsent)",
        before: "pendingConsentMutation = nil",
        in: consentRevocationSection
    ) && sourceContains(
        "_ = attemptPendingConsentMutation()",
        before: "let desiredConsent = consent",
        in: consentRetrySection
    ) && effectiveCollectionSection.contains("pendingConsentMutation == nil") &&
        consentUpdateSection.contains("if case .revoke? = pendingConsentMutation"),
    "A failed observed-session clear must retain the requested revocation, retry it before old consent, " +
        "remain fail-closed on foreground, and require a later explicit regrant."
)
expect(
    iosAuthSource.contains("IosAccountDeletionPurgeAttempt(accountId, interactionLifecycle, host)") &&
        iosAuthSource.contains("lifecycle.purge(accountId) {") &&
        iosAuthSource.contains("expectedAccountId = accountId"),
    "Account deletion must purge locally and fence the remote request with the captured account."
)
expect(
    accountDeletionSource.contains("attemptPreparation: deletionStore.prepareFederatedDeletion") &&
        accountDeletionSource.contains(
            "onPreparedAttemptAborted: deletionStore.cancelPreparedFederatedDeletion"
        ),
    "Federated account deletion must prepare before provider launch and resume after provider abort."
)

private let appleStartSection = sourceSection(
    federatedSignInSource,
    from: "func startAppleSignIn()",
    until: "func completeAppleAuthorization"
)
expect(
    sourceContains("prepareDeferredAttempt", before: "launchAppleSignIn", in: appleStartSection),
    "The Apple authorization controller must never launch before deferred deletion preparation."
)
expect(
    federatedSignInSource.contains(
        "appleAuthorizationContexts[ObjectIdentifier(authorizationController)] = context"
    ) && federatedSignInSource.contains(
        "appleAuthorizationContexts.removeValue(forKey: identifier)"
    ) && federatedSignInSource.contains(
        "completeAppleAuthorization(controller: controller, result:"
    ) && !federatedSignInSource.contains(
        "preconditionFailure(\"Apple authorization requires its captured presentation anchor.\")"
    ),
    "Late Apple callbacks must resolve only their controller-scoped nonce, generation, and anchor."
)
expect(
    federatedSignInSource.contains("cancelAppleAuthorizationContexts(for: generation)") &&
        federatedSignInSource.contains(".forEach { $0.controller.cancel() }") &&
        !federatedSignInSource.contains("private func clearPendingProviderState()"),
    "Aborting one Apple picker must preserve its controller context until its own terminal callback."
)
expect(
    sourceContains(
        "prepareDeferredAttempt",
        before: "presenterProvider.presentingViewController()",
        in: appleStartSection
    ) && federatedSignInSource.contains("guard windowScene?.activationState == .foregroundActive") &&
        federatedSignInSource.contains("PresentationSceneReader(onSceneChanged: store.bindPresentationScene)") &&
        !federatedSignInSource.contains("UIApplication.shared.connectedScenes"),
    "Deferred Apple deletion must resolve its presenter from an active scene after local preparation."
)
expect(
    federatedSignInSource.contains(".allowsHitTesting(false)") &&
        federatedSignInSource.contains(".accessibilityHidden(true)") &&
        federatedSignInSource.contains(
            ".frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)"
        ),
    "The decorative native Apple button must not expose a second path around deferred preparation."
)
private let googleStartSection = sourceSection(
    federatedSignInSource,
    from: "func startGoogleSignIn()",
    until: "private func launchAppleSignIn"
)
expect(
    googleStartSection.contains("prepareDeferredAttempt") &&
        !googleStartSection.contains("GIDSignIn.sharedInstance.signIn") &&
        sourceContains(
            "prepareDeferredAttempt",
            before: "presenterProvider.presentingViewController()",
            in: googleStartSection
        ),
    "Google account deletion must finish deferred preparation before entering the SDK picker."
)
private let providerFailureSection = sourceSection(
    federatedSignInSource,
    from: "private func finishProviderFailure",
    until: "private func prepareAttempt"
)
expect(
    providerFailureSection.contains("onPreparedAttemptAborted") &&
        providerFailureSection.contains("deferredAttemptPhase = .aborting(generation)"),
    "A cancelled or unavailable federated provider must release its prepared deletion block."
)
expect(
    accountDeletionSource.contains(
        ".onDisappear(perform: federatedStore.abortDeferredAttemptForDisappearance)"
    ) &&
        federatedSignInSource.contains("case preparing(UInt64)") &&
        federatedSignInSource.contains("case providerActive(UInt64)") &&
        federatedSignInSource.contains("case submitting(UInt64)"),
    "Federated deletion disappearance must abort only tokenized pre-transport phases."
)
private let federatedPreparationSection = sourceSection(
    accountDeletionSource,
    from: "func prepareFederatedDeletion",
    until: "func cancelPreparedFederatedDeletion"
)
expect(
    federatedPreparationSection.contains("federatedPreparationErrorMessage =") &&
        !federatedPreparationSection.contains("errorMessage = strings.settings.privacyPersistenceError") &&
        !federatedPreparationSection.contains("errorMessage = latestAuthError()"),
    "A federated preparation failure must be rendered only by the federated provider store."
)
expect(
    accountDeletionSource.contains("federatedStore.clearError()") &&
        federatedSignInSource.contains("func clearError()"),
    "Starting password deletion must clear any stale federated preparation error."
)
private let federatedSubmitSection = sourceSection(
    federatedSignInSource,
    from: "private func submit(",
    until: "private func prepareDeferredAttempt"
)
expect(
    sourceContains(
        "deferredAttemptPhase = .submitting(deferredGeneration)",
        before: "onCredential(credential)",
        in: federatedSubmitSection
    ),
    "Federated deletion must cross the ambiguous transport boundary before credential submission."
)
private let accountDeletionPrivacyCleanupSection = sourceSection(
    onboardingCoordinatorSource,
    from: "private func completeAccountDeletionPrivacyCleanupIfNeeded",
    until: "func accountDeletionCompleted"
)
private let promoterCallbackProcessingSection = sourceSection(
    onboardingCoordinatorSource,
    from: "private func processPendingPromoterActivationCallbackIfPossible",
    until: "private func persistPromoterActivationMarkerThenCallShared"
)
expect(
    federatedSignInSource.contains("protocol AccountDeletionPrivacyCleanupPersisting") &&
        onboardingCoordinatorSource.contains("accountDeletionPrivacyCleanupStore.persist()") &&
        onboardingCoordinatorSource.contains("@Published private var accountDeletionPrivacyCleanupArmed") &&
        onboardingCoordinatorSource.contains("accountDeletionPrivacyCleanupArmed ||") &&
        onboardingCoordinatorSource.contains(
            "accountDeletionPrivacyCleanupStore.state != .absent"
        ) &&
        onboardingCoordinatorSource.contains("accountDeletionPrivacyCleanupArmed = true") &&
        onboardingCoordinatorSource.contains("completeAccountDeletionPrivacyCleanupIfNeeded(state)") &&
        accountDeletionPrivacyCleanupSection.contains(
            "guard isDeletingAccount || sessionRestoreCompleted"
        ) &&
        accountDeletionPrivacyCleanupSection.contains("guard !state.hasSession") &&
        accountDeletionPrivacyCleanupSection.contains(
            "let hintsCleared = federatedIdentityHintStore.clearAllHints()"
        ) &&
        accountDeletionPrivacyCleanupSection.contains(
            "let googleSessionCleared = GoogleSignInBootstrap.clearLocalSession()"
        ) &&
        accountDeletionPrivacyCleanupSection.contains("guard hintsCleared, googleSessionCleared") &&
        sourceContains(
            "accountDeletionPrivacyCleanupArmed = false",
            before: "processPendingPromoterActivationCallbackIfPossible()",
            in: accountDeletionPrivacyCleanupSection
        ) &&
        promoterCallbackProcessingSection.components(
            separatedBy: "!accountDeletionPrivacyCleanupArmed"
        ).count - 1 == 2,
    "Deletion without a session must clear provider tokens and identity hints for unknown outcomes."
)
private let googleDeletionCleanupSection = sourceSection(
    federatedSignInSource,
    from: "enum GoogleSignInBootstrap",
    until: "private struct NonceAttempt"
)
private let googleSignInPackageSection = sourceSection(
    iosPackageLockSource,
    from: "\"identity\" : \"googlesignin-ios\"",
    until: "\"identity\" : \"googleutilities\""
)
private let gtmAppAuthPackageSection = sourceSection(
    iosPackageLockSource,
    from: "\"identity\" : \"gtmappauth\"",
    until: "\"identity\" : \"interop-ios-for-google-sdks\""
)
expect(
    googleDeletionCleanupSection.contains(
        "guard UIApplication.shared.isProtectedDataAvailable else { return false }"
    ) && googleDeletionCleanupSection.contains("keychainService = \"auth\"") &&
        googleDeletionCleanupSection.contains("keychainAccount = \"OAuth\"") &&
        googleDeletionCleanupSection.contains("kSecAttrService as String: keychainService") &&
        googleDeletionCleanupSection.contains("kSecAttrAccount as String: keychainAccount") &&
        googleDeletionCleanupSection.contains("status == errSecSuccess || status == errSecItemNotFound") &&
        googleDeletionCleanupSection.contains("currentUser == nil && persistedSessionRemoved") &&
        googleSignInPackageSection.contains("\"version\" : \"9.0.0\"") &&
        gtmAppAuthPackageSection.contains("\"version\" : \"5.0.0\""),
    "Google deletion cleanup must verify the pinned SDK Keychain entry before clearing its retry marker."
)
