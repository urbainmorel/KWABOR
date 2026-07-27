import Combine
import Foundation
import Security
import Shared
import SwiftUI

struct PromoterActivationDestination: Codable, Equatable, CustomStringConvertible {
    let organizationId: String
    let listingId: String
    let businessName: String

    var description: String {
        "PromoterActivationDestination(organizationId: <redacted>, " +
            "listingId: <redacted>, businessName: <redacted>)"
    }
}

protocol PromoterActivationDestinationPersisting {
    func save(_ destination: PromoterActivationDestination) -> Bool
    func current() -> PromoterActivationDestination?
    func clear()
}

final class KeychainPromoterActivationDestinationStore: PromoterActivationDestinationPersisting {
    private let service: String

    init(service: String = Bundle.main.bundleIdentifier ?? promoterDestinationFallbackService) {
        self.service = service + promoterDestinationServiceSuffix
    }

    func save(_ destination: PromoterActivationDestination) -> Bool {
        guard let data = try? JSONEncoder().encode(destination) else { return false }
        let query = baseQuery
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else { return false }
        var insert = query
        attributes.forEach { insert[$0.key] = $0.value }
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    func current() -> PromoterActivationDestination? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else {
            return nil
        }
        return try? JSONDecoder().decode(PromoterActivationDestination.self, from: data)
    }

    func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: promoterDestinationAccount,
        ]
    }
}

@MainActor
final class PromoterActivationStore: ObservableObject {
    @Published var password = ""
    @Published var passwordConfirmation = ""
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var activationResult: PromoterActivationResult?

    let context: PromoterActivationContext
    let strings: OnboardingStrings

    private let controller: IosAuthController
    private let latestAuthError: () -> String?
    private let onActivated: (PromoterActivationResult) -> Void

    init(
        context: PromoterActivationContext,
        controller: IosAuthController,
        strings: OnboardingStrings,
        latestAuthError: @escaping () -> String?,
        onActivated: @escaping (PromoterActivationResult) -> Void
    ) {
        self.context = context
        self.controller = controller
        self.strings = strings
        self.latestAuthError = latestAuthError
        self.onActivated = onActivated
    }

    var isConfigured: Bool {
        controller.isConfigured
    }

    var canActivateWithPassword: Bool {
        password.count >= minimumPasswordLength && password == passwordConfirmation
    }

    func activateWithPassword() {
        guard canActivateWithPassword, !isLoading else { return }
        let submittedPassword = password
        password = ""
        passwordConfirmation = ""
        isLoading = true
        errorMessage = nil
        controller.activatePromoterInviteWithPassword(
            inviteToken: context.inviteToken,
            password: submittedPassword
        ) { [weak self] result in
            self?.finish(result)
        }
    }

    func activateWithFederatedCredential(
        _ credential: FederatedAuthCredential,
        onCompleted: @escaping (Bool) -> Void
    ) {
        guard !isLoading else {
            onCompleted(false)
            return
        }
        isLoading = true
        errorMessage = nil
        controller.activatePromoterInviteWithSocial(
            inviteToken: context.inviteToken,
            request: credential.sharedRequest
        ) { [weak self] result in
            if let result {
                self?.finish(result)
                onCompleted(true)
            } else {
                self?.finish(nil)
                onCompleted(false)
            }
        }
    }

    private func finish(_ result: PromoterActivationResult?) {
        isLoading = false
        guard let result else {
            errorMessage = latestAuthError() ?? strings.authPromoterInviteInvalid
            return
        }
        activationResult = result
    }

    func continueToHome() {
        guard let activationResult, !isLoading else { return }
        onActivated(activationResult)
    }
}

struct PromoterActivationView: View {
    @StateObject private var store: PromoterActivationStore
    @StateObject private var federatedStore: FederatedSignInStore
    let onCancel: () -> Void

    init(
        context: PromoterActivationContext,
        controller: IosAuthController,
        strings: OnboardingStrings,
        identityHintStore: FederatedIdentityHintPersisting,
        latestAuthError: @escaping () -> String?,
        onActivated: @escaping (PromoterActivationResult) -> Void,
        onCancel: @escaping () -> Void
    ) {
        let activationStore = PromoterActivationStore(
            context: context,
            controller: controller,
            strings: strings,
            latestAuthError: latestAuthError,
            onActivated: onActivated
        )
        _store = StateObject(wrappedValue: activationStore)
        _federatedStore = StateObject(
            wrappedValue: FederatedSignInStore(
                strings: strings,
                presenterProvider: WindowScenePresentingViewControllerProvider(),
                identityHintStore: identityHintStore,
                reportsSubmissionFailure: false,
                onCredential: activationStore.activateWithFederatedCredential
            )
        )
        self.onCancel = onCancel
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                if store.activationResult != nil {
                    successContent
                } else {
                    activationContent
                }
            }
            .background(KwaborDesignTokens.ColorToken.paper50)
            .navigationTitle(store.strings.promoterActivationTitle)
            .toolbar {
                if store.activationResult == nil {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(store.strings.authCancel, action: onCancel)
                            .disabled(store.isLoading || federatedStore.isLoading)
                    }
                }
            }
        }
        .interactiveDismissDisabled(true)
    }

    private var activationContent: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
            Image(systemName: "building.2.fill")
                .font(.largeTitle)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityHidden(true)
            Text(store.strings.promoterActivationBusinessName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            Text(store.context.businessName)
                .font(.title2.bold())
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(store.strings.promoterActivationInvitePrompt)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)

            SecureField(store.strings.promoterActivationPasswordPrompt, text: $store.password)
                .textContentType(.newPassword)
                .kwaborAuthenticationField()
            SecureField(
                store.strings.registrationPasswordConfirmation,
                text: $store.passwordConfirmation
            )
            .textContentType(.newPassword)
            .submitLabel(.continue)
            .onSubmit(store.activateWithPassword)
            .kwaborAuthenticationField()
            Text(store.strings.registrationPasswordTooShort)
                .font(.caption)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            if !store.passwordConfirmation.isEmpty,
               store.password != store.passwordConfirmation {
                Text(store.strings.registrationPasswordMismatch)
                    .font(.caption)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(store.strings.registrationPasswordMismatch)
            }
            Button(store.strings.authContinueWithPassword) {
                store.activateWithPassword()
            }
            .buttonStyle(.borderedProminent)
            .tint(KwaborDesignTokens.ColorToken.ink950)
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .disabled(!store.canActivateWithPassword || store.isLoading || !store.isConfigured)

            FederatedSignInButtons(
                store: federatedStore,
                isDisabled: store.isLoading || !store.isConfigured
            )

            if let errorMessage = store.errorMessage {
                Text(errorMessage)
                    .font(.callout)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(errorMessage)
            }
        }
        .frame(maxWidth: promoterActivationMaxWidth, alignment: .leading)
        .frame(maxWidth: .infinity)
        .padding(KwaborDesignTokens.Spacing.xxl)
    }

    private var successContent: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.xxl) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: promoterSuccessSymbolSize))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityHidden(true)
            Text(store.strings.promoterActivationSuccess)
                .font(.title.bold())
                .multilineTextAlignment(.center)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(store.context.businessName)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            Button(store.strings.home, action: store.continueToHome)
                .buttonStyle(.borderedProminent)
                .tint(KwaborDesignTokens.ColorToken.ink950)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
        }
        .frame(maxWidth: promoterActivationMaxWidth)
        .frame(maxWidth: .infinity)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .accessibilityElement(children: .contain)
    }
}

private let minimumPasswordLength = 8
private let promoterActivationMaxWidth: CGFloat = 560
private let promoterSuccessSymbolSize: CGFloat = 72
private let promoterDestinationFallbackService = "com.kwabor.ios"
private let promoterDestinationServiceSuffix = ".promoter-destination"
private let promoterDestinationAccount = "current"
