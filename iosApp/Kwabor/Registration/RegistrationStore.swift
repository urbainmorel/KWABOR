import Combine
import Foundation
import Shared

@MainActor
final class RegistrationStore: ObservableObject {
    @Published private(set) var state: RegistrationUiState?
    @Published var otpCode = ""
    @Published var password = ""
    @Published var passwordConfirmation = ""
    @Published var cityQuery = ""
    @Published var isLocationPrimerPresented = false
    @Published private(set) var locationMessage: String?
    @Published private(set) var isRequestingLocation = false
    @Published private(set) var isRequestingNotifications = false
    @Published private(set) var nowEpochMilliseconds = RegistrationStore.currentEpochMilliseconds

    let strings: OnboardingStrings
    let controller: IosRegistrationController

    private let locationProvider: RegistrationLocationProviding
    private let notificationPermissionRequester: RegistrationNotificationPermissionRequesting
    private let notificationPrimingStore: RegistrationNotificationPrimingPersisting
    private let applyObservabilityConsent: (ObservabilityConsent) -> Void
    private let onCompleted: (AuthSession) -> Void
    private let onCancel: (Bool) -> Void
    private var completionReported = false
    private var clockTask: Task<Void, Never>?

    init(
        controller: IosRegistrationController,
        strings: OnboardingStrings,
        locationProvider: RegistrationLocationProviding,
        notificationPermissionRequester: RegistrationNotificationPermissionRequesting,
        notificationPrimingStore: RegistrationNotificationPrimingPersisting,
        applyObservabilityConsent: @escaping (ObservabilityConsent) -> Void,
        onCompleted: @escaping (AuthSession) -> Void,
        onCancel: @escaping (Bool) -> Void
    ) {
        self.controller = controller
        self.strings = strings
        self.locationProvider = locationProvider
        self.notificationPermissionRequester = notificationPermissionRequester
        self.notificationPrimingStore = notificationPrimingStore
        self.applyObservabilityConsent = applyObservabilityConsent
        self.onCompleted = onCompleted
        self.onCancel = onCancel

        controller.observe { [weak self] state in
            self?.receive(state)
        }
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
            state.step != .notificationPriming &&
            state.step != .completed
    }

    var isConfigured: Bool {
        controller.isConfigured
    }

    var canCancel: Bool {
        guard let state else { return true }
        return state.step != .notificationPriming && state.step != .completed
    }

    var canResendOtp: Bool {
        state?.canResendOtp(nowEpochMilliseconds: nowEpochMilliseconds) ?? false
    }

    var requirementsReady: Bool {
        guard let state else { return false }
        return !state.cities.isEmpty &&
            state.termsDocument != nil &&
            state.privacyDocument != nil &&
            state.ugcDocument != nil
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
        locationMessage = nil
        controller.dispatch(intent: IosRegistrationSelectCityIntent(cityId: cityId))
    }

    func selectCurrency(_ currency: KwaborCurrency) {
        controller.dispatch(intent: IosRegistrationSelectCurrencyIntent(currency: currency))
    }

    func updateLegalAcceptance(_ type: LegalDocumentType, accepted: Bool) {
        controller.dispatch(
            intent: IosRegistrationUpdateLegalAcceptanceIntent(type: type, accepted: accepted)
        )
    }

    func updateAnalyticsConsent(_ allowed: Bool) {
        guard let consent = state?.observabilityConsent else { return }
        updateObservabilityConsent(
            analytics: allowed,
            diagnostics: consent.diagnosticsAllowed,
            remoteConfiguration: consent.remoteConfigurationAllowed
        )
    }

    func updateDiagnosticsConsent(_ allowed: Bool) {
        guard let consent = state?.observabilityConsent else { return }
        updateObservabilityConsent(
            analytics: consent.analyticsAllowed,
            diagnostics: allowed,
            remoteConfiguration: consent.remoteConfigurationAllowed
        )
    }

    func updateRemoteConfigurationConsent(_ allowed: Bool) {
        guard let consent = state?.observabilityConsent else { return }
        updateObservabilityConsent(
            analytics: consent.analyticsAllowed,
            diagnostics: consent.diagnosticsAllowed,
            remoteConfiguration: allowed
        )
    }

    func submitPrimaryAction() {
        guard let state, !state.isLoading else { return }
        if state.step == .email {
            controller.dispatch(intent: IosRegistrationRequestOtpIntent.shared)
        } else if state.step == .otp {
            controller.dispatch(intent: IosRegistrationVerifyOtpIntent(otpCode: otpCode))
        } else if state.step == .password {
            controller.dispatch(
                intent: IosRegistrationSetInitialPasswordIntent(
                    password: password,
                    confirmation: passwordConfirmation
                )
            )
        } else if state.step == .identity {
            if requirementsReady {
                controller.dispatch(intent: IosRegistrationContinueFromIdentityIntent.shared)
            } else {
                controller.dispatch(intent: IosRegistrationLoadRequirementsIntent.shared)
            }
        } else if state.step == .city {
            controller.dispatch(intent: IosRegistrationContinueFromCityIntent.shared)
        } else if state.step == .currency {
            controller.dispatch(intent: IosRegistrationContinueFromCurrencyIntent.shared)
        } else if state.step == .legal {
            controller.dispatch(intent: IosRegistrationContinueFromLegalIntent.shared)
        } else if state.step == .observability {
            applyObservabilityConsent(state.observabilityConsent)
            controller.dispatch(intent: IosRegistrationCompleteOnboardingIntent.shared)
        }
    }

    func resendOtp() {
        guard canResendOtp, state?.isLoading == false else { return }
        controller.dispatch(intent: IosRegistrationRequestOtpIntent.shared)
    }

    func goBack() {
        guard canGoBack else { return }
        controller.dispatch(intent: IosRegistrationGoBackIntent.shared)
    }

    func requestCancellation() {
        onCancel(state?.currentSession != nil)
    }

    func presentLocationPrimer() {
        isLocationPrimerPresented = true
    }

    func requestLocationAfterPrimer() {
        isLocationPrimerPresented = false
        guard !isRequestingLocation else { return }
        isRequestingLocation = true
        locationMessage = nil
        let provider = locationProvider
        Task { [weak self, provider] in
            let result = await provider.requestCurrentLocation()
            guard let self else { return }
            isRequestingLocation = false
            switch result {
            case let .coordinate(coordinate):
                controller.dispatch(
                    intent: IosRegistrationSelectNearestCityIntent(
                        latitude: coordinate.latitude,
                        longitude: coordinate.longitude
                    )
                )
            case .permissionDenied:
                locationMessage = strings.registrationLocationPermissionDenied
            case .unavailable:
                locationMessage = strings.registrationLocationUnavailable
            }
        }
    }

    func enableNotifications() {
        guard state?.step == .notificationPriming,
              !isRequestingNotifications else {
            return
        }
        isRequestingNotifications = true
        let requester = notificationPermissionRequester
        Task { [weak self, requester] in
            let result = await requester.requestPermission()
            guard let self else { return }
            switch result {
            case .granted, .denied, .unavailable:
                notificationPrimingStore.markResolved()
                isRequestingNotifications = false
                controller.dispatch(intent: IosRegistrationFinishNotificationPrimingIntent.shared)
            }
        }
    }

    func skipNotifications() {
        guard state?.step == .notificationPriming,
              !isRequestingNotifications else {
            return
        }
        notificationPrimingStore.markResolved()
        controller.dispatch(intent: IosRegistrationFinishNotificationPrimingIntent.shared)
    }

    private func receive(_ updatedState: RegistrationUiState) {
        let previousStep = state?.step
        state = updatedState
        if previousStep == .otp, updatedState.step != .otp {
            otpCode = ""
        }
        if previousStep == .password, updatedState.step != .password {
            password = ""
            passwordConfirmation = ""
        }
        if updatedState.step == .completed,
           !completionReported,
           let session = updatedState.currentSession {
            completionReported = true
            onCompleted(session)
        }
    }

    private func updateObservabilityConsent(
        analytics: Bool,
        diagnostics: Bool,
        remoteConfiguration: Bool
    ) {
        controller.dispatch(
            intent: IosRegistrationUpdateObservabilityConsentIntent(
                consent: ObservabilityConsent(
                    analyticsAllowed: analytics,
                    diagnosticsAllowed: diagnostics,
                    remoteConfigurationAllowed: remoteConfiguration
                )
            )
        )
    }

    private static var currentEpochMilliseconds: Int64 {
        Int64(Date().timeIntervalSince1970 * millisecondsPerSecond)
    }
}

private let millisecondsPerSecond = 1_000.0
private let oneSecondNanoseconds: UInt64 = 1_000_000_000
