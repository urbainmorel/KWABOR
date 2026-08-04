import Shared
import SwiftUI

struct SignInFlowView: View {
    @ObservedObject var store: AuthenticationStore
    let strings: OnboardingStrings
    let authErrorMessage: String?
    let allowsAlternativeActions: Bool
    @ObservedObject var federatedStore: FederatedSignInStore
    let onCreateAccount: () -> Void
    let onContinueAsGuest: () -> Void
    @State private var password = ""
    @AccessibilityFocusState private var accessibilityFocus: SignInAccessibilityFocus?

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            AuthenticationEmailField(strings: strings, email: $store.email)
                .disabled(store.isLoading || federatedStore.isLoading)
                .accessibilityFocused($accessibilityFocus, equals: .email)
            if store.signInStep == .password {
                passwordContent
            } else {
                AuthenticationPrimaryButton(
                    title: strings.authEmailContinue,
                    isLoading: store.isLoading,
                    isDisabled: store.trimmedEmail.isEmpty || federatedStore.isLoading,
                    isConfigured: store.isConfigured,
                    action: store.continueToPassword
                )
            }
            FederatedSignInButtons(
                store: federatedStore,
                isDisabled: store.isLoading
            )
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
        .onAppear { updateAccessibilityFocus(for: store.signInStep) }
        .onChange(of: store.signInStep) { _, step in
            updateAccessibilityFocus(for: step)
        }
    }

    private var passwordContent: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            SecureField(strings.authPassword, text: $password)
                .textContentType(.password)
                .submitLabel(.go)
                .onSubmit(submitSignIn)
                .kwaborAuthenticationField()
                .disabled(store.isLoading || federatedStore.isLoading)
                .accessibilityFocused($accessibilityFocus, equals: .password)
            Button(strings.authForgotPassword) {
                password = ""
                store.beginRecovery()
            }
            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            .disabled(store.isLoading || federatedStore.isLoading)
            AuthenticationPrimaryButton(
                title: strings.signIn,
                isLoading: store.isLoading,
                isDisabled: store.trimmedEmail.isEmpty || password.isEmpty || federatedStore.isLoading,
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
            .disabled(store.isLoading || federatedStore.isLoading)
            Button(strings.authContinueAsGuest) {
                password = ""
                onContinueAsGuest()
            }
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            .disabled(store.isLoading || federatedStore.isLoading)
        }
    }

    private func submitSignIn() {
        guard !federatedStore.isLoading else { return }
        let submittedPassword = password
        store.submitSignIn(password: submittedPassword) { completed in
            if completed {
                password = ""
            }
        }
    }

    private func updateAccessibilityFocus(for step: AuthenticationStore.SignInStep) {
        accessibilityFocus = step == .password ? .password : .email
    }
}

private enum SignInAccessibilityFocus: Hashable {
    case email
    case password
}
