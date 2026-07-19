import Shared
import SwiftUI

struct SignInFlowView: View {
    @ObservedObject var store: AuthenticationStore
    let strings: OnboardingStrings
    let authErrorMessage: String?
    let allowsAlternativeActions: Bool
    let onCreateAccount: () -> Void
    let onContinueAsGuest: () -> Void
    @State private var password = ""

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            AuthenticationEmailField(strings: strings, email: $store.email)
            if store.signInStep == .password {
                passwordContent
            } else {
                AuthenticationPrimaryButton(
                    title: strings.authEmailContinue,
                    isLoading: store.isLoading,
                    isDisabled: store.trimmedEmail.isEmpty,
                    isConfigured: store.isConfigured,
                    action: store.continueToPassword
                )
            }
            if allowsAlternativeActions {
                accountActions
            }
            AuthenticationFeedback(
                strings: strings,
                isConfigured: store.isConfigured,
                errorMessage: store.validationErrorMessage ?? authErrorMessage,
                noticeMessage: nil
            )
        }
        .onDisappear { password = "" }
    }

    private var passwordContent: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            SecureField(strings.authPassword, text: $password)
                .textContentType(.password)
                .submitLabel(.go)
                .onSubmit(submitSignIn)
                .kwaborAuthenticationField()
            Button(strings.authForgotPassword) {
                password = ""
                store.beginRecovery()
            }
            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            AuthenticationPrimaryButton(
                title: strings.signIn,
                isLoading: store.isLoading,
                isDisabled: store.trimmedEmail.isEmpty || password.isEmpty,
                isConfigured: store.isConfigured,
                action: submitSignIn
            )
        }
    }

    private var accountActions: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.sm) {
            Button(strings.authCreateAccount) {
                password = ""
                onCreateAccount()
            }
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            Button(strings.authContinueAsGuest) {
                password = ""
                onContinueAsGuest()
            }
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
        }
    }

    private func submitSignIn() {
        let submittedPassword = password
        store.submitSignIn(password: submittedPassword) { completed in
            if completed {
                password = ""
            }
        }
    }
}
