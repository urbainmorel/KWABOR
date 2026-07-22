import Shared
import SwiftUI

struct PasswordRecoveryFlowView: View {
    @ObservedObject var store: AuthenticationStore
    let strings: OnboardingStrings
    @State private var email: String
    @State private var otpCode = ""
    @State private var newPassword = ""
    @State private var confirmation = ""

    init(store: AuthenticationStore, strings: OnboardingStrings) {
        self.store = store
        self.strings = strings
        _email = State(initialValue: store.trimmedEmail)
    }

    var body: some View {
        Group {
            if let state = store.recoveryState {
                recoveryContent(state: state)
            } else {
                ProgressView()
                    .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            }
        }
        .onDisappear(perform: clearSecrets)
    }

    @ViewBuilder
    private func recoveryContent(state: PasswordRecoveryUiState) -> some View {
        if state.isEmailStep {
            emailStep
        } else if state.isOtpStep {
            otpStep(state: state)
        } else if state.isNewPasswordStep {
            newPasswordStep
        } else if state.isCompletedStep {
            completedStep
        } else {
            ProgressView()
                .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
        }
    }

    private var emailStep: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            AuthenticationEmailField(strings: strings, email: $email)
            AuthenticationPrimaryButton(
                title: strings.passwordRecoverySendCode,
                isLoading: store.isLoading,
                isDisabled: email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                isConfigured: store.isConfigured,
                action: { store.requestRecoveryCode(email: email) }
            )
            recoveryFeedback
        }
    }

    private func otpStep(state: PasswordRecoveryUiState) -> some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            otpField
            AuthenticationPrimaryButton(
                title: strings.authVerifyOtp,
                isLoading: store.isLoading,
                isDisabled: otpCode.count != otpCodeLength,
                isConfigured: store.isConfigured,
                action: verifyOtp
            )
            Button(store.resendLabel, action: store.resendRecoveryCode)
                .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .disabled(!store.canResendRecoveryCode || state.isLoading)
            recoveryFeedback
        }
    }

    private var newPasswordStep: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            SecureField(strings.passwordRecoveryNewPassword, text: $newPassword)
                .textContentType(.newPassword)
                .kwaborAuthenticationField()
            SecureField(strings.passwordRecoveryConfirmation, text: $confirmation)
                .textContentType(.newPassword)
                .submitLabel(.done)
                .onSubmit(submitNewPassword)
                .kwaborAuthenticationField()
            Text(strings.registrationPasswordTooShort)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            AuthenticationPrimaryButton(
                title: strings.registrationContinue,
                isLoading: store.isLoading,
                isDisabled: newPassword.count < minimumPasswordLength || newPassword != confirmation,
                isConfigured: store.isConfigured,
                action: submitNewPassword
            )
            recoveryFeedback
        }
    }

    private var completedStep: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: recoverySuccessSymbolSize))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityHidden(true)
            Text(strings.passwordRecoverySuccess)
                .multilineTextAlignment(.center)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            AuthenticationPrimaryButton(
                title: strings.passwordRecoveryBackToSignIn,
                isLoading: store.isLoading,
                isDisabled: false,
                isConfigured: store.isConfigured,
                action: store.leaveRecovery
            )
        }
    }

    private var otpField: some View {
        ZStack {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                ForEach(0..<otpCodeLength, id: \.self) { index in
                    Text(otpDigit(at: index))
                        .font(.title2.monospacedDigit())
                        .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                        .background(KwaborDesignTokens.ColorToken.surface0)
                        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control))
                        .overlay {
                            RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                                .stroke(
                                    index == otpCode.count
                                        ? KwaborDesignTokens.ColorToken.ink950
                                        : KwaborDesignTokens.ColorToken.ink100
                                )
                        }
                        .accessibilityHidden(true)
                }
            }
            TextField(strings.passwordRecoveryCode, text: otpBinding)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .foregroundStyle(.clear)
                .tint(.clear)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .accessibilityLabel(strings.passwordRecoveryCode)
                .accessibilityValue(otpCode)
        }
    }

    private var recoveryFeedback: some View {
        AuthenticationFeedback(
            strings: strings,
            isConfigured: store.isConfigured,
            errorMessage: store.validationErrorMessage ?? store.recoveryState?.errorMessage,
            noticeMessage: store.recoveryState?.noticeMessage
        )
    }

    private var otpBinding: Binding<String> {
        Binding(
            get: { otpCode },
            set: { value in
                otpCode = String(value.filter(\.isNumber).prefix(otpCodeLength))
            }
        )
    }

    private func verifyOtp() {
        let submittedOtp = otpCode
        store.verifyRecoveryOtp(submittedOtp) { completed in
            if completed {
                otpCode = ""
            }
        }
    }

    private func submitNewPassword() {
        let submittedPassword = newPassword
        let submittedConfirmation = confirmation
        store.completeRecovery(
            password: submittedPassword,
            confirmation: submittedConfirmation
        ) { completed in
            if completed {
                newPassword = ""
                confirmation = ""
            }
        }
    }

    private func clearSecrets() {
        otpCode = ""
        newPassword = ""
        confirmation = ""
    }

    private func otpDigit(at index: Int) -> String {
        String(otpCode.dropFirst(index).prefix(1))
    }
}

private let recoverySuccessSymbolSize: CGFloat = 52
private let otpCodeLength = 6
private let minimumPasswordLength = 8
