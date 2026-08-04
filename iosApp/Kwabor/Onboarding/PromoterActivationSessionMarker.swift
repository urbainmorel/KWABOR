import Foundation

enum PromoterActivationSessionMarkerState: Equatable {
    case absent
    case marked
    case unavailable
}

enum PromoterActivationSessionBootstrapAction: Equatable {
    case proceed
    case clearTemporarySession
}

enum PromoterActivationSessionCallbackPreparationAction: Equatable {
    case rejectBeforeShared
    case queueForBootstrap
}

enum PromoterActivationSessionMarkerPersistenceAction: Equatable {
    case callShared
    case exposeErrorWithoutCallingShared
    case clearTemporarySessionBeforeExposingError
}

enum PromoterActivationSessionCallbackResolutionAction: Equatable {
    case keepMarkerBeforeExposingReady
    case clearMarkerBeforeExposingReady
    case exposeReadyWithoutMarker
    case exposeErrorWithoutMarker
    case clearTemporarySessionBeforeExposingError
}

enum PromoterActivationCallbackMarkerAction: Equatable {
    case persistBeforeShared
    case callSharedWithoutMarker
}

enum PromoterActivationFailClosedCleanupAction: Equatable {
    case cleanupCompleted
    case keepCleanupRequired
}

enum PromoterActivationSessionPolicy {
    static func bootstrapAction(
        markerState: PromoterActivationSessionMarkerState
    ) -> PromoterActivationSessionBootstrapAction {
        switch markerState {
        case .absent:
            return .proceed
        case .marked, .unavailable:
            return .clearTemporarySession
        }
    }

    static func callbackPreparationAction(
        linkAccepted: Bool
    ) -> PromoterActivationSessionCallbackPreparationAction {
        linkAccepted ? .queueForBootstrap : .rejectBeforeShared
    }

    static func markerPersistenceAction(
        persistenceSucceeded: Bool,
        rollbackSucceeded: Bool
    ) -> PromoterActivationSessionMarkerPersistenceAction {
        if persistenceSucceeded {
            return .callShared
        }
        return rollbackSucceeded
            ? .exposeErrorWithoutCallingShared
            : .clearTemporarySessionBeforeExposingError
    }

    static func callbackMarkerAction(
        hasExistingSession: Bool,
        hasPkceCode: Bool
    ) -> PromoterActivationCallbackMarkerAction {
        !hasExistingSession && hasPkceCode
            ? .persistBeforeShared
            : .callSharedWithoutMarker
    }

    static func callbackResolutionAction(
        contextAvailable: Bool,
        sessionImportedForActivation: Bool,
        markerArmed: Bool
    ) -> PromoterActivationSessionCallbackResolutionAction {
        switch (contextAvailable, sessionImportedForActivation, markerArmed) {
        case (true, true, true):
            return .keepMarkerBeforeExposingReady
        case (true, false, true):
            return .clearMarkerBeforeExposingReady
        case (false, _, true):
            return .clearTemporarySessionBeforeExposingError
        case (true, false, false):
            return .exposeReadyWithoutMarker
        case (false, _, false):
            return .exposeErrorWithoutMarker
        case (true, true, false):
            return .clearTemporarySessionBeforeExposingError
        }
    }

    static func canExposeSession(
        cleanupRequired: Bool,
        activationCallbackInProgress: Bool,
        activationPresented: Bool
    ) -> Bool {
        !cleanupRequired && !activationCallbackInProgress && !activationPresented
    }

    static func canCompleteActivation(
        resultUserID: String?,
        authenticatedUserID: String?,
        isAuthenticated: Bool,
        isAuthenticationLoading: Bool,
        cleanupInProgress: Bool,
        callbackInProgress: Bool
    ) -> Bool {
        guard isAuthenticated,
              !isAuthenticationLoading,
              !cleanupInProgress,
              !callbackInProgress,
              let resultUserID,
              let authenticatedUserID else {
            return false
        }
        return resultUserID == authenticatedUserID
    }

    static func failClosedCleanupAction(
        signOutSucceeded: Bool,
        markerCleared: Bool
    ) -> PromoterActivationFailClosedCleanupAction {
        signOutSucceeded && markerCleared
            ? .cleanupCompleted
            : .keepCleanupRequired
    }
}

struct PromoterActivationCallbackQueue {
    private var pending: [URL] = []
    private var inFlight: URL?

    @discardableResult
    mutating func enqueue(_ url: URL) -> Bool {
        guard inFlight != url, !pending.contains(url) else { return false }
        pending.append(url)
        return true
    }

    mutating func beginNextIfReady(
        sessionBootstrapCompleted: Bool,
        authOperationLoading: Bool,
        callbackInProgress: Bool,
        cleanupRequired: Bool,
        activationPresented: Bool
    ) -> URL? {
        guard sessionBootstrapCompleted,
              !authOperationLoading,
              !callbackInProgress,
              !cleanupRequired,
              !activationPresented,
              inFlight == nil,
              !pending.isEmpty else {
            return nil
        }
        let next = pending.removeFirst()
        inFlight = next
        return next
    }

    @discardableResult
    mutating func completeInFlight() -> Bool {
        guard inFlight != nil else { return false }
        inFlight = nil
        return true
    }

    mutating func clear() {
        pending.removeAll()
        inFlight = nil
    }
}

enum PromoterActivationLinkRoutingPolicy {
    static func targetsActivationHost(
        scheme: String?,
        host: String?
    ) -> Bool {
        scheme?.lowercased() == promoterActivationScheme &&
            host?.lowercased() == promoterActivationHost
    }

    static func acceptsCallback(
        scheme: String?,
        host: String?,
        path: String,
        hasExplicitFragment: Bool
    ) -> Bool {
        targetsActivationHost(scheme: scheme, host: host) &&
            path.lowercased() == promoterActivationPath &&
            !hasExplicitFragment
    }

    static func hasPkceCode(_ url: URL) -> Bool {
        guard let queryItems = URLComponents(
            url: url,
            resolvingAgainstBaseURL: false
        )?.queryItems else {
            return false
        }
        return queryItems.contains { item in
            item.name == promoterActivationPkceCodeParameter
        }
    }
}

protocol PromoterActivationSessionMarkerPersisting {
    var state: PromoterActivationSessionMarkerState { get }

    func persist() -> Bool
    func clear() -> Bool
}

final class FilePromoterActivationSessionMarkerStore: PromoterActivationSessionMarkerPersisting {
    private let fileManager: FileManager
    private let markerURL: URL?

    init(
        fileManager: FileManager = .default,
        applicationSupportURL: URL? = nil
    ) {
        self.fileManager = fileManager
        let baseURL = applicationSupportURL ??
            fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        markerURL = baseURL?
            .appendingPathComponent(markerRootDirectoryName, isDirectory: true)
            .appendingPathComponent(markerSecurityDirectoryName, isDirectory: true)
            .appendingPathComponent(markerFileName, isDirectory: false)
    }

    var state: PromoterActivationSessionMarkerState {
        guard let markerURL else { return .unavailable }
        do {
            let markerFile = try FileHandle(forReadingFrom: markerURL)
            try markerFile.close()
            return .marked
        } catch {
            return isMissingFileError(error) ? .absent : .unavailable
        }
    }

    func persist() -> Bool {
        guard let markerURL else { return false }
        do {
            try fileManager.createDirectory(
                at: markerURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(markerPayload.utf8).write(to: markerURL, options: .atomic)
            return state == .marked
        } catch {
            return false
        }
    }

    func clear() -> Bool {
        guard let markerURL else { return false }
        do {
            try fileManager.removeItem(at: markerURL)
            return true
        } catch {
            return isMissingFileError(error)
        }
    }

    private func isMissingFileError(_ error: Error) -> Bool {
        let cocoaError = error as NSError
        return cocoaError.domain == NSCocoaErrorDomain &&
            cocoaError.code == CocoaError.Code.fileNoSuchFile.rawValue
    }
}

private let markerRootDirectoryName = "Kwabor"
private let markerSecurityDirectoryName = "Security"
private let markerFileName = "promoter-activation-session.pending"
private let markerPayload = "temporary-promoter-activation-session:v1"
private let promoterActivationScheme = "kwabor"
private let promoterActivationHost = "auth"
private let promoterActivationPath = "/promoter-activate"
private let promoterActivationPkceCodeParameter = "code"
