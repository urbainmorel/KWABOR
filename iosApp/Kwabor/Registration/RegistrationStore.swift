import Combine
import Foundation
import Shared

@MainActor
final class RegistrationStore: ObservableObject {
    @Published private(set) var state: RegistrationUiState?
    @Published var otpCode = ""
    @Published var password = ""
    @Published var cityQuery = ""
    @Published private(set) var nowEpochMilliseconds = RegistrationStore.currentEpochMilliseconds

    let strings: OnboardingStrings
    let controller: IosRegistrationController

    private let interruptedAuthJourneyStore: InterruptedAuthJourneyPersisting
    private let onCompleted: (AuthSession) -> Void
    private let onExistingAccountAuthenticated: (String?) -> Void
    private let onCancel: (Bool) -> Void
    private let onEmailMethodChosen: () -> Void
    private let onOtpValidated: () -> Void
    private let onProfileResult: (Bool) -> Void
    private var completionReported = false
    private var clockTask: Task<Void, Never>?

    init(
        controller: IosRegistrationController,
        strings: OnboardingStrings,
        interruptedAuthJourneyStore: InterruptedAuthJourneyPersisting,
        onCompleted: @escaping (AuthSession) -> Void,
        onExistingAccountAuthenticated: @escaping (String?) -> Void,
        onCancel: @escaping (Bool) -> Void,
        onEmailMethodChosen: @escaping () -> Void,
        onOtpValidated: @escaping () -> Void,
        onProfileResult: @escaping (Bool) -> Void
    ) {
        self.controller = controller
        self.strings = strings
        self.interruptedAuthJourneyStore = interruptedAuthJourneyStore
        self.onCompleted = onCompleted
        self.onExistingAccountAuthenticated = onExistingAccountAuthenticated
        self.onCancel = onCancel
        self.onEmailMethodChosen = onEmailMethodChosen
        self.onOtpValidated = onOtpValidated
        self.onProfileResult = onProfileResult

        controller.observe { [weak self] state in
            self?.receive(state)
        }
        controller.prepare(suggestedCityId: nil)
        clockTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: oneSecondNanoseconds)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                self?.nowEpochMilliseconds = RegistrationStore.currentEpochMilliseconds
            }
        }
    }

    deinit {
        clockTask?.cancel()
    }

    var canGoBack: Bool {
        guard let state else { return false }
        return state.step != .email &&
            !(state.step == .profile && state.method == .federated) &&
            state.step != .completed
    }

    var isConfigured: Bool {
        controller.isConfigured
    }

    var canCancel: Bool {
        guard let state else { return true }
        return state.step != .completed
    }

    var canResendOtp: Bool {
        state?.canResendOtp(nowEpochMilliseconds: nowEpochMilliseconds) ?? false
    }

    var resendLabel: String {
        guard let availableAt = state?.resendAvailableAtEpochMilliseconds?.int64Value else {
            return strings.authRequestOtp
        }
        let remainingMilliseconds = max(0, availableAt - nowEpochMilliseconds)
        let remainingSeconds = (remainingMilliseconds + millisecondsPerSecondInt - 1) /
            millisecondsPerSecondInt
        guard remainingSeconds > 0 else { return strings.authRequestOtp }
        return strings.registrationOtpResendCountdown.replacingOccurrences(
            of: resendSecondsPlaceholder,
            with: String(remainingSeconds)
        )
    }

    var requirementsReady: Bool {
        guard let state else { return false }
        return state.requirementsReady
    }

    var filteredCities: [City] {
        guard let cities = state?.cities else { return [] }
        let query = cityQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return cities }
        return cities.filter { city in
            city.name.localizedCaseInsensitiveContains(query)
        }
    }

    func updateEmail(_ email: String) {
        controller.dispatch(intent: IosRegistrationUpdateEmailIntent(email: email))
    }

    func updateFirstName(_ firstName: String) {
        controller.dispatch(intent: IosRegistrationUpdateFirstNameIntent(firstName: firstName))
    }

    func updateLastName(_ lastName: String) {
        controller.dispatch(intent: IosRegistrationUpdateLastNameIntent(lastName: lastName))
    }

    func selectCity(_ cityId: String) {
        controller.dispatch(intent: IosRegistrationSelectCityIntent(cityId: cityId))
    }

    func selectCurrency(_ currency: KwaborCurrency) {
        controller.dispatch(intent: IosRegistrationSelectCurrencyIntent(currency: currency))
    }

    func updateTermsAcceptance(_ accepted: Bool) {
        controller.legalAcceptance.updateTerms(accepted: accepted)
    }

    func updatePrivacyAcceptance(_ accepted: Bool) {
        controller.legalAcceptance.updatePrivacy(accepted: accepted)
    }

    func updateUgcAcceptance(_ accepted: Bool) {
        controller.legalAcceptance.updateUgc(accepted: accepted)
    }

    func submitPrimaryAction() {
        guard let state, !state.isLoading else { return }
        if state.step == .email {
            controller.dispatch(intent: IosRegistrationRequestOtpIntent.shared)
        } else if state.step == .otp {
            interruptedAuthJourneyStore.mark(.registration)
            controller.dispatch(intent: IosRegistrationVerifyOtpIntent(otpCode: otpCode))
        } else if state.step == .password {
            controller.dispatch(
                intent: IosRegistrationSetInitialPasswordIntent(
                    password: password
                )
            )
        } else if state.step == .profile {
            controller.dispatch(intent: IosRegistrationCompleteProfileIntent.shared)
        }
    }

    func resendOtp() {
        guard canResendOtp, state?.isLoading == false else { return }
        controller.dispatch(intent: IosRegistrationRequestOtpIntent.shared)
    }

    func retryRequirements() {
        guard state?.requirementsStatus == .failed else { return }
        controller.dispatch(intent: IosRegistrationLoadRequirementsIntent.shared)
    }

    func goBack() {
        guard canGoBack else { return }
        controller.dispatch(intent: IosRegistrationGoBackIntent.shared)
    }

    func requestCancellation() {
        onCancel(state?.currentSession != nil)
    }

    private func receive(_ updatedState: RegistrationUiState) {
        let previousState = state
        let previousStep = previousState?.step
        state = updatedState
        if previousStep == .email, updatedState.step == .otp {
            onEmailMethodChosen()
        }
        if previousStep == .otp,
           updatedState.step == .password || updatedState.step == .completed {
            onOtpValidated()
        }
        if previousStep == .profile, updatedState.step == .completed {
            onProfileResult(true)
        } else if previousStep == .profile,
                  previousState?.isLoading == true,
                  !updatedState.isLoading,
                  updatedState.errorMessage != nil {
            onProfileResult(false)
        }
        if previousStep == .otp, updatedState.step != .otp {
            otpCode = ""
        }
        if previousStep == .password, updatedState.step != .password {
            password = ""
        }
        guard !completionReported else { return }
        switch RegistrationSessionGate.outcome(
            previousStep: previousStep,
            updatedState: updatedState,
            interruptedJourney: interruptedAuthJourneyStore.current
        ) {
        case .none:
            return
        case .clearInterruptedJourney:
            interruptedAuthJourneyStore.clearRegistration()
        case .continueRegistration:
            interruptedAuthJourneyStore.clearRegistration()
        case .requirePasswordSignIn:
            completionReported = true
            onExistingAccountAuthenticated(updatedState.currentSession?.email)
        case .completeRegistration:
            guard let session = updatedState.currentSession else { return }
            completionReported = true
            onCompleted(session)
        }
    }

    private static var currentEpochMilliseconds: Int64 {
        Int64(Date().timeIntervalSince1970 * millisecondsPerSecond)
    }
}

private let millisecondsPerSecond = 1_000.0
private let millisecondsPerSecondInt: Int64 = 1_000
private let oneSecondNanoseconds: UInt64 = 1_000_000_000
private let resendSecondsPlaceholder = "{seconds}"
