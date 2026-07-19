import Foundation
import Shared

enum InterruptedAuthJourney: String {
    case registration
}

protocol InterruptedAuthJourneyPersisting {
    var current: InterruptedAuthJourney? { get }

    func mark(_ journey: InterruptedAuthJourney)

    func clear(_ journey: InterruptedAuthJourney)
}

struct UserDefaultsInterruptedAuthJourneyStore: InterruptedAuthJourneyPersisting {
    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    var current: InterruptedAuthJourney? {
        userDefaults.string(forKey: interruptedAuthJourneyKey)
            .flatMap(InterruptedAuthJourney.init(rawValue:))
    }

    func mark(_ journey: InterruptedAuthJourney) {
        userDefaults.set(journey.rawValue, forKey: interruptedAuthJourneyKey)
    }

    func clear(_ journey: InterruptedAuthJourney) {
        guard current == journey else { return }
        userDefaults.removeObject(forKey: interruptedAuthJourneyKey)
    }
}

enum RegistrationSessionGate {
    enum Outcome {
        case none
        case clearInterruptedJourney
        case continueRegistration
        case requirePasswordSignIn
        case completeRegistration
    }

    static func outcome(
        previousStep: RegistrationStep?,
        updatedState: RegistrationUiState,
        interruptedJourney: InterruptedAuthJourney?
    ) -> Outcome {
        if interruptedJourney == .registration,
           previousStep == .otp,
           updatedState.step == .otp,
           !updatedState.isLoading,
           updatedState.currentSession == nil,
           updatedState.errorMessage != nil {
            return .clearInterruptedJourney
        }
        guard let session = updatedState.currentSession else { return .none }
        if previousStep == .otp, session.accountSetupStatus == .onboardingRequired {
            return .continueRegistration
        }
        if interruptedJourney == .registration,
           updatedState.step == .completed,
           session.accountSetupStatus == .complete {
            return .requirePasswordSignIn
        }
        if updatedState.step == .completed {
            return .completeRegistration
        }
        return .none
    }
}

enum AuthSessionBootstrapAction {
    case clearLocalSession
    case restoreSession
}

enum AuthSessionBootstrapPolicy {
    static func action(hasInstallationMarker: Bool) -> AuthSessionBootstrapAction {
        hasInstallationMarker ? .restoreSession : .clearLocalSession
    }

    static func canExposeAuthenticatedSession(freshInstallCleanupCompleted: Bool) -> Bool {
        freshInstallCleanupCompleted
    }
}

private let interruptedAuthJourneyKey = "kwabor.auth.interrupted_journey_v1"
