import Shared
import SwiftUI

struct AuthenticationSheet: View {
    @ObservedObject private var coordinator: OnboardingCoordinator
    @StateObject private var store: AuthenticationStore

    init(coordinator: OnboardingCoordinator) {
        self.coordinator = coordinator
        let store = AuthenticationStore(
            recoveryController: coordinator.passwordRecoveryController,
            strings: coordinator.strings,
            initialEmail: coordinator.interruptedRegistrationEmail,
            signIn: coordinator.signIn,
            onRecoveryClosed: coordinator.refreshSessionState
        )
        if coordinator.authState?.hasPasswordRecoverySession == true {
            store.resumeVerifiedRecovery(email: coordinator.authState?.currentSession?.email)
        }
        _store = StateObject(
            wrappedValue: store
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: KwaborDesignTokens.Spacing.xxl) {
                    AuthenticationHeader(
                        strings: coordinator.strings,
                        isRecovering: store.isRecovering
                    )
                    if store.isRecovering {
                        PasswordRecoveryFlowView(store: store, strings: coordinator.strings)
                    } else {
                        SignInFlowView(
                            store: store,
                            strings: coordinator.strings,
                            authErrorMessage: coordinator.authState?.errorMessage,
                            allowsAlternativeActions:
                                !coordinator.requiresProtectedAuthentication,
                            onCreateAccount: coordinator.presentRegistrationFromAuthentication,
                            onContinueAsGuest: continueAsGuest
                        )
                    }
                }
                .frame(maxWidth: authenticationContentMaxWidth)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
                .padding(.vertical, KwaborDesignTokens.Spacing.xxxl)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(KwaborDesignTokens.ColorToken.paper50)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
        }
        .interactiveDismissDisabled(
            store.requiresProtectedRecoveryCancellation ||
                store.isLoading ||
                coordinator.requiresProtectedAuthentication
        )
        .onDisappear(perform: store.resetAfterDismissal)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        if store.canGoBack {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(coordinator.strings.registrationBack, action: store.goBack)
                    .disabled(store.isLoading)
            }
        }
        if !coordinator.requiresProtectedAuthentication {
            ToolbarItem(placement: .cancellationAction) {
                Button(coordinator.strings.guestCancel, action: dismiss)
                    .disabled(store.isLoading)
            }
        }
    }

    private func dismiss() {
        guard !coordinator.requiresProtectedAuthentication else { return }
        store.prepareForDismissal {
            coordinator.dismissAuthentication()
        }
    }

    private func continueAsGuest() {
        guard !coordinator.requiresProtectedAuthentication else { return }
        store.prepareForDismissal {
            coordinator.dismissAuthentication()
            coordinator.requestGuestAccess()
        }
    }
}

struct AuthenticationHeader: View {
    let strings: OnboardingStrings
    let isRecovering: Bool

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            Image("LaunchMark")
                .resizable()
                .scaledToFit()
                .frame(width: authenticationLogoSize, height: authenticationLogoSize)
                .accessibilityLabel(strings.introAccessibilityLabel)
            Text(isRecovering ? strings.passwordRecoveryTitle : strings.authTitle)
                .font(.largeTitle.bold())
                .multilineTextAlignment(.center)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(isRecovering ? strings.passwordRecoverySubtitle : strings.authSubtitle)
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
    }
}

struct AuthenticationEmailField: View {
    let strings: OnboardingStrings
    @Binding var email: String

    var body: some View {
        TextField(strings.authEmail, text: $email)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.emailAddress)
            .textContentType(.username)
            .submitLabel(.continue)
            .kwaborAuthenticationField()
    }
}

struct AuthenticationPrimaryButton: View {
    let title: String
    let isLoading: Bool
    let isDisabled: Bool
    let isConfigured: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Text(title)
                    .opacity(isLoading ? 0 : 1)
                if isLoading {
                    ProgressView()
                        .tint(.white)
                        .accessibilityLabel(title)
                }
            }
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
        }
        .buttonStyle(.borderedProminent)
        .tint(KwaborDesignTokens.ColorToken.ink950)
        .disabled(isDisabled || isLoading || !isConfigured)
    }
}

struct AuthenticationFeedback: View {
    let strings: OnboardingStrings
    let isConfigured: Bool
    let errorMessage: String?
    let noticeMessage: String?

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.sm) {
            if !isConfigured {
                feedbackText(strings.authUnavailable, color: KwaborDesignTokens.ColorToken.ticket)
            } else if let errorMessage {
                feedbackText(errorMessage, color: KwaborDesignTokens.ColorToken.ticket)
            }
            if let noticeMessage {
                feedbackText(noticeMessage, color: KwaborDesignTokens.ColorToken.ink700)
            }
        }
    }

    private func feedbackText(_ message: String, color: Color) -> some View {
        Text(message)
            .font(.callout)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityLabel(message)
    }
}

extension View {
    func kwaborAuthenticationField() -> some View {
        padding(.horizontal, KwaborDesignTokens.Spacing.lg)
            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control))
            .overlay {
                RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                    .stroke(KwaborDesignTokens.ColorToken.ink100)
            }
    }
}

private let authenticationContentMaxWidth: CGFloat = 480
private let authenticationLogoSize: CGFloat = 88
