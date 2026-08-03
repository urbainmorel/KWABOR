import Combine
import Foundation
import Shared
import SwiftUI

@MainActor
final class AccountDeletionStore: ObservableObject {
    @Published var password = ""
    @Published var confirmation = ""
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    let strings: OnboardingStrings

    private let controller: IosAuthController
    private let latestAuthError: () -> String?
    private let onDeletionStateChanged: (Bool) -> Void
    private let onDeleted: () -> Void
    private var idempotencyKey = UUID().uuidString

    init(
        controller: IosAuthController,
        strings: OnboardingStrings,
        latestAuthError: @escaping () -> String?,
        onDeletionStateChanged: @escaping (Bool) -> Void,
        onDeleted: @escaping () -> Void
    ) {
        self.controller = controller
        self.strings = strings
        self.latestAuthError = latestAuthError
        self.onDeletionStateChanged = onDeletionStateChanged
        self.onDeleted = onDeleted
    }

    var isConfigured: Bool {
        controller.isConfigured
    }

    var hasConfirmedDeletion: Bool {
        confirmation.trimmingCharacters(in: .whitespacesAndNewlines) ==
            strings.authDeleteAccountConfirmationPhrase
    }

    func deleteWithPassword() {
        guard hasConfirmedDeletion, !password.isEmpty, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        let submittedPassword = password
        password = ""
        onDeletionStateChanged(true)
        controller.deleteAccountWithPassword(
            password: submittedPassword,
            idempotencyKey: idempotencyKey
        ) { [weak self] completed in
            self?.finish(completed: completed.boolValue)
        }
    }

    func deleteWithFederatedCredential(
        _ credential: FederatedAuthCredential,
        onCompleted: @escaping (Bool) -> Void
    ) {
        guard hasConfirmedDeletion, !isLoading else {
            onCompleted(false)
            return
        }
        isLoading = true
        errorMessage = nil
        onDeletionStateChanged(true)
        controller.deleteAccountWithSocial(
            request: credential.sharedRequest,
            idempotencyKey: idempotencyKey
        ) { [weak self] completed in
            let didComplete = completed.boolValue
            self?.finish(completed: didComplete)
            onCompleted(didComplete)
        }
    }

    func reset() {
        guard !isLoading else { return }
        password = ""
        confirmation = ""
        errorMessage = nil
        idempotencyKey = UUID().uuidString
    }

    private func finish(completed: Bool) {
        isLoading = false
        guard completed else {
            errorMessage = latestAuthError() ?? strings.authAccountDeletionFailed
            onDeletionStateChanged(false)
            return
        }
        password = ""
        confirmation = ""
        idempotencyKey = UUID().uuidString
        onDeleted()
        onDeletionStateChanged(false)
    }
}

struct AccountDangerZoneSection: View {
    let controller: IosAuthController?
    let strings: OnboardingStrings
    let identityHintStore: FederatedIdentityHintPersisting?
    let latestAuthError: () -> String?
    let isSigningOut: Bool
    let signOutErrorMessage: String?
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onDeletionStateChanged: (Bool) -> Void
    let onDeleted: () -> Void
    @State private var isDeletionPresented = false
    @State private var isSignOutConfirmationPresented = false

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Text(strings.dangerZoneTitle)
                .font(.headline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)

            Button(role: .destructive) {
                isSignOutConfirmationPresented = true
            } label: {
                HStack {
                    Text(strings.authSignOut)
                    Spacer()
                    if isSigningOut {
                        ProgressView()
                            .accessibilityLabel(strings.authSignOut)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            }
            .disabled(isSigningOut)

            if let signOutErrorMessage {
                Text(signOutErrorMessage)
                    .font(.callout)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(signOutErrorMessage)
                    .onDisappear(perform: onDismissSignOutError)
            }

            if controller != nil, identityHintStore != nil {
                Divider()
                Text(strings.authDeleteAccountWarning)
                    .font(.callout)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                Button(strings.authDeleteAccount, role: .destructive) {
                    isDeletionPresented = true
                }
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .disabled(isSigningOut)
            }
        }
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .alert(strings.authSignOutTitle, isPresented: $isSignOutConfirmationPresented) {
            Button(strings.authCancel, role: .cancel) {}
            Button(strings.authConfirm, role: .destructive, action: onSignOut)
        } message: {
            Text(strings.authSignOutConfirmation)
        }
        .sheet(isPresented: $isDeletionPresented) {
            if let controller, let identityHintStore {
                AccountDeletionConfirmationView(
                    controller: controller,
                    strings: strings,
                    identityHintStore: identityHintStore,
                    latestAuthError: latestAuthError,
                    onDeletionStateChanged: onDeletionStateChanged,
                    onDeleted: {
                        isDeletionPresented = false
                        onDeleted()
                    }
                )
            }
        }
    }
}

private struct AccountDeletionConfirmationView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store: AccountDeletionStore
    @StateObject private var federatedStore: FederatedSignInStore

    init(
        controller: IosAuthController,
        strings: OnboardingStrings,
        identityHintStore: FederatedIdentityHintPersisting,
        latestAuthError: @escaping () -> String?,
        onDeletionStateChanged: @escaping (Bool) -> Void,
        onDeleted: @escaping () -> Void
    ) {
        let deletionStore = AccountDeletionStore(
            controller: controller,
            strings: strings,
            latestAuthError: latestAuthError,
            onDeletionStateChanged: onDeletionStateChanged,
            onDeleted: onDeleted
        )
        _store = StateObject(wrappedValue: deletionStore)
        _federatedStore = StateObject(
            wrappedValue: FederatedSignInStore(
                strings: strings,
                presenterProvider: WindowScenePresentingViewControllerProvider(),
                identityHintStore: identityHintStore,
                reportsSubmissionFailure: false,
                onCredential: deletionStore.deleteWithFederatedCredential
            )
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                    Text(store.strings.authDeleteAccountWarning)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    Text(
                        store.strings.authDeleteAccountConfirmationPrompt.replacingOccurrences(
                            of: confirmationPlaceholder,
                            with: store.strings.authDeleteAccountConfirmationPhrase
                        )
                    )
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    TextField(
                        store.strings.authDeleteAccountConfirmationPhrase,
                        text: $store.confirmation
                    )
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .kwaborAuthenticationField()

                    SecureField(store.strings.authDeleteAccountPasswordPrompt, text: $store.password)
                        .textContentType(.password)
                        .kwaborAuthenticationField()
                    Button(store.strings.authDeleteAccountConfirm, role: .destructive) {
                        store.deleteWithPassword()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ticket)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .disabled(
                        !store.hasConfirmedDeletion || store.password.isEmpty ||
                            store.isLoading || !store.isConfigured
                    )

                    FederatedSignInButtons(
                        store: federatedStore,
                        isDisabled: !store.hasConfirmedDeletion || store.isLoading || !store.isConfigured
                    )

                    if let errorMessage = store.errorMessage {
                        Text(errorMessage)
                            .font(.callout)
                            .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                            .accessibilityLabel(errorMessage)
                    }
                }
                .padding(KwaborDesignTokens.Spacing.xxl)
            }
            .background(KwaborDesignTokens.ColorToken.paper50)
            .navigationTitle(store.strings.authDeleteAccount)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(store.strings.authCancel) {
                        store.reset()
                        dismiss()
                    }
                    .disabled(store.isLoading || federatedStore.isLoading)
                }
            }
        }
        .interactiveDismissDisabled(store.isLoading || federatedStore.isLoading)
    }
}

private let confirmationPlaceholder = "{phrase}"
