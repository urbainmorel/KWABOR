import Combine
import Foundation
import Shared

@MainActor
final class AuthenticationStore: ObservableObject {
    enum SignInStep {
        case email
        case password
    }

    @Published var email = ""
    @Published private(set) var signInStep = SignInStep.email
    @Published private(set) var recoveryState: PasswordRecoveryUiState?
    @Published private(set) var isRecovering = false
    @Published private(set) var isSigningIn = false
    @Published private(set) var validationErrorMessage: String?
    @Published private(set) var nowEpochMilliseconds = AuthenticationStore.currentEpochMilliseconds

    let strings: OnboardingStrings

    private let recoveryController: IosPasswordRecoveryController
    private let signIn: (String, String, @escaping (Bool) -> Void) -> Void
    private let onRecoveryClosed: () -> Void
    private var clockTask: Task<Void, Never>?

    init(
        recoveryController: IosPasswordRecoveryController,
        strings: OnboardingStrings,
        initialEmail: String?,
        signIn: @escaping (String, String, @escaping (Bool) -> Void) -> Void,
        onRecoveryClosed: @escaping () -> Void
    ) {
        self.recoveryController = recoveryController
        self.strings = strings
        self.signIn = signIn
        self.onRecoveryClosed = onRecoveryClosed
        email = initialEmail?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        recoveryController.observe { [weak self] state in
            self?.recoveryState = state
            if !state.isEmailStep {
                self?.isRecovering = true
            }
        }
        clockTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: oneSecondNanoseconds)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                self?.nowEpochMilliseconds = AuthenticationStore.currentEpochMilliseconds
            }
        }
    }

    deinit {
        clockTask?.cancel()
    }

    var trimmedEmail: String {
        email.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var isConfigured: Bool {
        recoveryController.isConfigured
    }

    var isLoading: Bool {
        isSigningIn || recoveryState?.isLoading == true
    }

    var canGoBack: Bool {
        isRecovering || signInStep == .password
    }

    var requiresProtectedRecoveryCancellation: Bool {
        isRecovering && recoveryState?.isNewPasswordStep == true
    }

    var canResendRecoveryCode: Bool {
        recoveryState?.canResendOtp(nowEpochMilliseconds: nowEpochMilliseconds) ?? false
    }

    var resendLabel: String {
        guard let availableAt = recoveryState?.resendAvailableAtEpochMilliseconds?.int64Value else {
            return strings.passwordRecoveryResendCode
        }
        let remainingMilliseconds = max(0, availableAt - nowEpochMilliseconds)
        guard remainingMilliseconds > 0 else { return strings.passwordRecoveryResendCode }
        let seconds = Int(ceil(Double(remainingMilliseconds) / millisecondsPerSecond))
        return strings.passwordRecoveryResendCountdown.replacingOccurrences(
            of: resendSecondsPlaceholder,
            with: String(seconds)
        )
    }

    func continueToPassword() {
        guard isValidEmail(trimmedEmail) else {
            validationErrorMessage = strings.authInvalidInput
            return
        }
        validationErrorMessage = nil
        email = trimmedEmail
        signInStep = .password
    }

    func returnToEmail() {
        validationErrorMessage = nil
        signInStep = .email
    }

    func submitSignIn(password: String, onCompleted: @escaping (Bool) -> Void) {
        guard !isLoading, isValidEmail(trimmedEmail), !password.isEmpty else {
            validationErrorMessage = strings.authInvalidInput
            return
        }
        validationErrorMessage = nil
        isSigningIn = true
        signIn(trimmedEmail, password) { [weak self] completed in
            self?.isSigningIn = false
            onCompleted(completed)
        }
    }

    func beginRecovery() {
        guard !isLoading else { return }
        recoveryController.reset()
        validationErrorMessage = nil
        isRecovering = true
    }

    func resumeVerifiedRecovery(email: String?) {
        recoveryController.resumeVerifiedSession(email: email)
        isRecovering = true
    }

    func requestRecoveryCode(email: String) {
        guard !isLoading else { return }
        let candidate = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isValidEmail(candidate) else {
            validationErrorMessage = strings.authInvalidInput
            return
        }
        validationErrorMessage = nil
        recoveryController.requestCode(email: candidate) { _ in }
    }

    func resendRecoveryCode() {
        guard canResendRecoveryCode, !isLoading else { return }
        recoveryController.resendCode { _ in }
    }

    func verifyRecoveryOtp(_ otpCode: String, onCompleted: @escaping (Bool) -> Void) {
        guard otpCode.count == otpCodeLength, !isLoading else { return }
        recoveryController.verifyOtp(otpCode: otpCode) { completed in
            onCompleted(completed.boolValue)
        }
    }

    func completeRecovery(
        password: String,
        confirmation: String,
        onCompleted: @escaping (Bool) -> Void
    ) {
        guard !isLoading else { return }
        recoveryController.complete(password: password, confirmation: confirmation) { completed in
            onCompleted(completed.boolValue)
        }
    }

    func goBack() {
        if isRecovering {
            leaveRecovery()
        } else {
            returnToEmail()
        }
    }

    func leaveRecovery() {
        guard isRecovering else { return }
        recoveryController.cancel { [weak self] completed in
            guard let self else { return }
            guard completed.boolValue else { return }
            recoveryController.reset()
            validationErrorMessage = nil
            isRecovering = false
            onRecoveryClosed()
        }
    }

    func prepareForDismissal(onReady: @escaping () -> Void) {
        guard isRecovering else {
            onReady()
            return
        }
        recoveryController.cancel { [weak self] completed in
            guard let self, completed.boolValue else { return }
            recoveryController.reset()
            onRecoveryClosed()
            onReady()
        }
    }

    func resetAfterDismissal() {
        email = ""
        validationErrorMessage = nil
        signInStep = .email
        isSigningIn = false
        if !requiresProtectedRecoveryCancellation {
            recoveryController.reset()
            isRecovering = false
        }
    }

    private func isValidEmail(_ candidate: String) -> Bool {
        candidate.range(of: emailValidationPattern, options: .regularExpression) != nil
    }

    private static var currentEpochMilliseconds: Int64 {
        Int64(Date().timeIntervalSince1970 * millisecondsPerSecond)
    }
}

private let otpCodeLength = 6
private let millisecondsPerSecond = 1_000.0
private let oneSecondNanoseconds: UInt64 = 1_000_000_000
private let resendSecondsPlaceholder = "{seconds}"
private let emailValidationPattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"
