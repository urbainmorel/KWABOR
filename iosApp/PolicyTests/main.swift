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
        activationCallbackInProgress: false
    ),
    "A failed cleanup must keep every temporary session hidden between retries."
)
expect(
    !PromoterActivationSessionPolicy.canExposeSession(
        cleanupRequired: false,
        activationCallbackInProgress: true
    ),
    "A callback in progress must keep its provisional session hidden."
)
expect(
    PromoterActivationSessionPolicy.canExposeSession(
        cleanupRequired: false,
        activationCallbackInProgress: false
    ),
    "Session visibility may resume only when no callback or cleanup is active."
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
